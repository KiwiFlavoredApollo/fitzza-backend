/**
 * 가상피팅 요청 접수 시간과 이미지 생성 완료 시간 측정
 * k6 run -e PHOTO_ID=123 -e PRODUCT_IDS=101,201 performance/scenario-vton.js
 * ACCESS_TOKEN: 필수, Bearer 접두사 없이 환경 변수로 전달한다.
 * BASE_URL=http://localhost:8080, VUS=1, ITERATIONS=1
 * POLL_INTERVAL_SECONDS=2, WAIT_TIMEOUT_SECONDS=600, HTTP_TIMEOUT_SECONDS=30
 * ACCEPT_P95_MS / COMPLETE_P95_MS: 선택적 p95 기준(ms).
 * 예시 ID를 계정 소유 사진과 서로 다른 카테고리의 실제 상품 2~5개로 바꾼다.
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
const endpoint = `${baseUrl}/api/v1/vton`;
const photoId = readPositiveNumber('PHOTO_ID', undefined, true);
const productIds = readProductIds();

if (iterations < vus) {
  throw new Error('ITERATIONS must be at least VUS');
}

const acceptanceTime = new Trend('fitzza_vton_accept_ms', true);
const completionTime = new Trend('fitzza_vton_complete_ms', true);
const completionSuccess = new Rate('fitzza_vton_success');
const failureCount = new Counter('fitzza_vton_failures');
const timeoutCount = new Counter('fitzza_vton_timeouts');

export const options = {
  scenarios: {
    vton: {
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
    const fittingId = requestFitting(deadline);
    acceptanceTime.add(Date.now() - startedAt);

    waitForFitting(fittingId, deadline);
    // 실패 작업은 완료 시간에 포함하지 않고 성공률·실패 횟수로 기록한다.
    completionTime.add(Date.now() - startedAt);
    succeeded = true;
  } catch (error) {
    timedOut = Date.now() >= deadline;
    // 응답 본문에는 개인정보·서명 URL이 있을 수 있어 오류 종류만 출력한다.
    const message = timedOut ? 'Job deadline exceeded' : error.message;
    console.error(`vton: ${message}`);
  } finally {
    completionSuccess.add(succeeded);
    if (!succeeded) {
      failureCount.add(1);
    }
    if (timedOut) {
      timeoutCount.add(1);
    }
    check(succeeded, {
      'vton completed successfully': value => value,
    });
  }
}

function requestFitting(deadline) {
  const response = http.post(
    endpoint,
    JSON.stringify({ photoId, productIds }),
    requestParams('POST', deadline),
  );
  ensureWithinDeadline(deadline);
  const accepted = parseResponse(response, 202);
  if (!accepted || !Number.isSafeInteger(accepted.fittingId)
      || accepted.fittingId <= 0 || accepted.status !== 'PENDING') {
    throw new Error('Invalid acceptance ID or status');
  }
  return accepted.fittingId;
}

function waitForFitting(fittingId, deadline) {
  while (Date.now() < deadline) {
    const response = http.get(`${endpoint}/${fittingId}`, requestParams('GET', deadline));
    ensureWithinDeadline(deadline);
    const result = parseResponse(response, 200);
    if (!result || result.fittingId !== fittingId) {
      throw new Error('Invalid result ID');
    }
    if (result.status === 'COMPLETED') {
      if (typeof result.resultImageUrl !== 'string' || !result.resultImageUrl.trim()) {
        throw new Error('Invalid completed result');
      }
      return;
    }
    if (result.status === 'FAILED') {
      throw new Error('Job failed');
    }
    if (result.status !== 'PENDING') {
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
    tags: { name: `${method} /api/v1/vton${method === 'GET' ? '/{id}' : ''}` },
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
  const thresholds = { fitzza_vton_success: ['rate>=0.95'] };
  if (__ENV.ACCEPT_P95_MS !== undefined) {
    const limit = readPositiveNumber('ACCEPT_P95_MS');
    thresholds.fitzza_vton_accept_ms = [`p(95)<${limit}`];
  }
  if (__ENV.COMPLETE_P95_MS !== undefined) {
    const limit = readPositiveNumber('COMPLETE_P95_MS');
    thresholds.fitzza_vton_complete_ms = [`p(95)<${limit}`];
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

function readProductIds() {
  const values = (__ENV.PRODUCT_IDS || '').split(',').map(value => Number(value.trim()));
  const validCount = values.length >= 2 && values.length <= 5;
  const validIds = values.every(value => Number.isSafeInteger(value) && value > 0);
  const uniqueIds = new Set(values).size === values.length;
  if (!validCount || !validIds || !uniqueIds) {
    throw new Error('PRODUCT_IDS must contain 2 to 5 distinct positive integers separated by commas');
  }
  return values;
}
