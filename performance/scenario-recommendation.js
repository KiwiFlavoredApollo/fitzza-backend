/**
 * 추천 요청 접수 시간과 추천 완료 시간 측정
 * k6 run performance/scenario-recommendation.js
 * ACCESS_TOKEN: 필수, Bearer 접두사 없이 환경 변수로 전달한다.
 * BASE_URL=http://localhost:8080, VUS=1, ITERATIONS=1
 * POLL_INTERVAL_SECONDS=2, WAIT_TIMEOUT_SECONDS=600, HTTP_TIMEOUT_SECONDS=30
 * ACCEPT_P95_MS / COMPLETE_P95_MS: 선택적 p95 기준(ms).
 * QUERY: 자연어 질의. 빈 DONE 결과도 완료로 처리한다. VU는 계정·질의를 공유한다.
 * 완료 시간은 큐 대기·생성·저장·폴링 지연을 포함한다. 이미지 다운로드는 제외한다.
 * 실제 AI 호출과 데이터 저장이 발생하며, 생성된 데이터는 자동 삭제하지 않는다.
 */
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const baseUrl = readBaseUrl();
const accessToken = readAccessToken();
const vus = readPositiveNumber('VUS', 1, true);
const iterations = readPositiveNumber('ITERATIONS', 1, true);
const pollIntervalSeconds = readPositiveNumber('POLL_INTERVAL_SECONDS', 2);
const waitTimeoutSeconds = readPositiveNumber('WAIT_TIMEOUT_SECONDS', 600);
const httpTimeoutSeconds = readPositiveNumber('HTTP_TIMEOUT_SECONDS', 30);
const endpoint = `${baseUrl}/api/v1/recommendations`;
const query = readQuery();

if (iterations < vus) {
  throw new Error('ITERATIONS must be at least VUS');
}

const acceptanceTime = new Trend('fitzza_recommendation_accept_ms', true);
const completionTime = new Trend('fitzza_recommendation_complete_ms', true);
const completionSuccess = new Rate('fitzza_recommendation_success');
const failureCount = new Counter('fitzza_recommendation_failures');
const timeoutCount = new Counter('fitzza_recommendation_timeouts');

export const options = {
  scenarios: {
    recommendation: {
      executor: 'shared-iterations',
      vus,
      iterations,
      maxDuration: `${Math.ceil(iterations / vus) * (waitTimeoutSeconds + 60)}s`,
    },
  },
  thresholds: buildThresholds(),
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  // 작업마다 달라지는 URL 대신 고정된 요청 이름으로 메트릭을 집계한다.
  systemTags: ['status', 'method', 'name', 'scenario', 'expected_response', 'error_code'],
};

export default function () {
  const startedAt = Date.now();
  const deadline = startedAt + waitTimeoutSeconds * 1000;
  let succeeded = false;
  let timedOut = false;

  failureCount.add(0);
  timeoutCount.add(0);

  try {
    const requestId = requestRecommendation(deadline);
    acceptanceTime.add(Date.now() - startedAt);

    waitForRecommendation(requestId, deadline);
    // 실패 작업은 완료 시간에 포함하지 않고 성공률·실패 횟수로 기록한다.
    completionTime.add(Date.now() - startedAt);
    succeeded = true;
  } catch (error) {
    timedOut = Date.now() >= deadline;
    // 응답 본문에는 개인정보·서명 URL이 있을 수 있어 오류 종류만 출력한다.
    const message = timedOut ? 'Job deadline exceeded' : error.message;
    console.error(`recommendation: ${message}`);
  } finally {
    completionSuccess.add(succeeded);
    if (!succeeded) {
      failureCount.add(1);
    }
    if (timedOut) {
      timeoutCount.add(1);
    }
    check(succeeded, {
      'recommendation completed successfully': value => value,
    });
  }
}

function requestRecommendation(deadline) {
  const response = http.post(
    endpoint,
    JSON.stringify({ query }),
    requestParams('POST', deadline),
  );
  ensureWithinDeadline(deadline);
  const accepted = parseResponse(response, 202);
  if (!accepted || !Number.isSafeInteger(accepted.requestId)
      || accepted.requestId <= 0 || accepted.status !== 'PROCESSING') {
    throw new Error('Invalid acceptance ID or status');
  }
  return accepted.requestId;
}

function waitForRecommendation(requestId, deadline) {
  while (Date.now() < deadline) {
    const response = http.get(`${endpoint}/${requestId}`, requestParams('GET', deadline));
    ensureWithinDeadline(deadline);
    const result = parseResponse(response, 200);
    if (!result || result.requestId !== requestId) {
      throw new Error('Invalid result ID');
    }
    if (result.status === 'DONE') {
      if (!Array.isArray(result.items) || !Array.isArray(result.combos)) {
        throw new Error('Invalid completed result');
      }
      return;
    }
    if (result.status === 'FAILED') {
      throw new Error('Job failed');
    }
    if (result.status !== 'PROCESSING') {
      throw new Error('Invalid job status');
    }
    const remainingSeconds = Math.max(0, (deadline - Date.now()) / 1000);
    sleep(Math.min(pollIntervalSeconds, remainingSeconds));
  }
  throw new Error('Job deadline exceeded');
}

function requestParams(method, deadline) {
  const remainingMilliseconds = deadline - Date.now();
  return {
    headers: {
      Authorization: `Bearer ${accessToken}`,
      'Content-Type': 'application/json',
      Accept: 'application/json',
    },
    tags: { name: `${method} /api/v1/recommendations${method === 'GET' ? '/{id}' : ''}` },
    timeout: Math.max(1, Math.min(httpTimeoutSeconds * 1000, remainingMilliseconds)),
    redirects: 0,
  };
}

function parseResponse(response, expectedStatus) {
  if (response.status !== expectedStatus) {
    throw new Error(`Unexpected HTTP status ${response.status}`);
  }
  try {
    return response.json();
  } catch (_) {
    throw new Error('Invalid JSON response');
  }
}

function ensureWithinDeadline(deadline) {
  if (Date.now() >= deadline) {
    throw new Error('Job deadline exceeded');
  }
}

function buildThresholds() {
  const thresholds = { fitzza_recommendation_success: ['rate>=0.95'] };
  if (__ENV.ACCEPT_P95_MS !== undefined) {
    const limit = readPositiveNumber('ACCEPT_P95_MS');
    thresholds.fitzza_recommendation_accept_ms = [`p(95)<${limit}`];
  }
  if (__ENV.COMPLETE_P95_MS !== undefined) {
    const limit = readPositiveNumber('COMPLETE_P95_MS');
    thresholds.fitzza_recommendation_complete_ms = [`p(95)<${limit}`];
  }
  return thresholds;
}

function readPositiveNumber(name, fallback, integer = false) {
  const value = Number(__ENV[name] === undefined ? fallback : __ENV[name]);
  if (!Number.isFinite(value) || value <= 0 || (integer && !Number.isSafeInteger(value))) {
    throw new Error(`${name} must be a positive ${integer ? 'integer' : 'number'}`);
  }
  return value;
}

function readBaseUrl() {
  const value = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/$/, '');
  if (!/^https?:\/\/[a-zA-Z0-9.-]+(?::[0-9]{1,5})?$/.test(value)) {
    throw new Error('BASE_URL must be an HTTP(S) origin without credentials or a path');
  }
  return value;
}

function readAccessToken() {
  const value = (__ENV.ACCESS_TOKEN || '').trim();
  if (!value || /^Bearer\s/i.test(value)) {
    throw new Error('ACCESS_TOKEN is required without Bearer prefix');
  }
  return value;
}

function readQuery() {
  const defaultQuery = '주말 나들이에 어울리는 캐주얼 코디를 추천해주세요.';
  const value = (__ENV.QUERY === undefined ? defaultQuery : __ENV.QUERY).trim();
  if (!value) {
    throw new Error('QUERY must not be blank');
  }
  return value;
}
