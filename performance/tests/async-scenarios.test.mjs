// node --test performance/tests/async-scenarios.test.mjs
// Simulated k6 runtime: no Docker, application services, credentials or AI calls.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const common = readFileSync(new URL('../async-job.js', import.meta.url), 'utf8');

function simulate(service, mode, overrides = {}) {
  let now = 0;
  let polls = 0;
  const metrics = new Map();
  const calls = [];
  const isVton = service === 'vton';
  const idField = isVton ? 'fittingId' : 'requestId';
  const pending = isVton ? 'PENDING' : 'PROCESSING';
  class Metric {
    constructor(name) { this.values = []; metrics.set(name, this.values); }
    add(value) { this.values.push(value); }
  }
  const response = (status, body) => ({ status, json() {
    if (mode === 'bad_json') throw new Error('invalid JSON');
    return body;
  } });
  const context = vm.createContext({
    __ENV: { ACCESS_TOKEN: 'mock-only', PHOTO_ID: '1', PRODUCT_IDS: '101,201',
      WAIT_TIMEOUT_SECONDS: '1', POLL_INTERVAL_SECONDS: '0.1', ...overrides },
    Date: { now: () => now }, console: { error() {} },
    Trend: Metric, Rate: Metric, Counter: Metric,
    sleep(seconds) { now += seconds * 1000; },
    check(value, checks) { return Object.values(checks).every(check => check(value)); },
    http: {
      post(url, payload, params) {
        calls.push({ url, payload: JSON.parse(payload), params });
        now += 5;
        return response(mode === 'post_error' ? 503 : 202,
          { [idField]: mode === 'bad_acceptance' ? null : 7, status: pending });
      },
      get(url, params) {
        calls.push({ url, params });
        polls++;
        now += mode === 'http_timeout' ? params.timeout : 5;
        let status = isVton ? 'COMPLETED' : 'DONE';
        if (mode === 'timeout' || polls === 1) status = pending;
        if (mode === 'failed') status = 'FAILED';
        if (mode === 'unknown') status = 'UNKNOWN';
        const body = { [idField]: mode === 'wrong_id' ? 8 : 7, status,
          items: [], combos: [], resultImageUrl: 'https://example.test/image.png' };
        if (mode === 'missing_result') delete body[isVton ? 'resultImageUrl' : 'items'];
        return response(mode === 'http_error' || mode === 'http_timeout' ? 503 : 200, body);
      },
    },
  });
  const entry = readFileSync(new URL(`../scenario-${service}.js`, import.meta.url), 'utf8');
  const source = (common + entry).replace(/^import .*;\r?$/gm, '')
    .replace(/export default function/g, 'function execute').replace(/export /g, '');
  vm.runInContext(`${source}\nglobalThis.scenarioOptions = options; execute();`, context);
  return { metrics, calls, options: context.scenarioOptions };
}

for (const service of ['recommendation', 'vton']) {
  for (const mode of ['success', 'failed', 'unknown', 'timeout', 'http_timeout', 'post_error',
    'bad_acceptance', 'http_error', 'bad_json', 'wrong_id', 'missing_result']) {
    test(`${service}: ${mode}`, () => {
      const { metrics, calls, options } = simulate(service, mode);
      const prefix = `fitzza_${service}`;
      const succeeded = mode === 'success';
      assert.deepEqual(metrics.get(`${prefix}_success`), [succeeded]);
      assert.equal(metrics.get(`${prefix}_complete_ms`).length, Number(succeeded));
      assert.equal(metrics.get(`${prefix}_failures`).at(-1), Number(!succeeded));
      assert.equal(metrics.get(`${prefix}_timeouts`).at(-1), Number(['timeout', 'http_timeout'].includes(mode)));
      assert.equal(metrics.get(`${prefix}_accept_ms`).length,
        Number(!['post_error', 'bad_acceptance', 'bad_json'].includes(mode)));
      assert.equal(calls[0].params.headers.Authorization, 'Bearer mock-only');
      assert.equal(calls[0].url, `http://localhost:8080/api/v1/${service === 'vton' ? 'vton' : 'recommendations'}`);
      if (service === 'vton') assert.equal(JSON.stringify(calls[0].payload), '{"photoId":1,"productIds":[101,201]}');
      else assert.ok(calls[0].payload.query);
      assert.equal(options.scenarios[service].maxDuration, '61s');
      assert.ok(calls.every(call => call.params.timeout > 0 && call.params.timeout <= 1000));
      assert.ok(calls.every(call => !call.params.tags.name.includes('/7')));
    });
  }
}

for (const settings of [{ ACCESS_TOKEN: '' }, { BASE_URL: 'http://user:pass@localhost' },
  { VUS: '0' }, { ITERATIONS: '0' }, { VUS: '2', ITERATIONS: '1' },
  { POLL_INTERVAL_SECONDS: 'NaN' }, { WAIT_TIMEOUT_SECONDS: '-1' },
  { HTTP_TIMEOUT_SECONDS: '0' }, { ACCEPT_P95_MS: '0' }, { COMPLETE_P95_MS: 'no' },
  { PHOTO_ID: '' }, { PRODUCT_IDS: '1,1' }, { PRODUCT_IDS: '1' },
  { PRODUCT_IDS: '1,2,3,4,5,6' }, { PRODUCT_IDS: '1,-2' }]) {
  test(`reject invalid settings: ${Object.keys(settings).join(',')}`, () => {
    assert.throws(() => simulate('vton', 'success', settings));
  });
}

test('reject blank recommendation query', () => {
  assert.throws(() => simulate('recommendation', 'success', { QUERY: ' ' }));
});

test('optional thresholds and iteration budget', () => {
  const { options } = simulate('recommendation', 'success', {
    VUS: '2', ITERATIONS: '5', ACCEPT_P95_MS: '500', COMPLETE_P95_MS: '2000',
  });
  assert.equal(options.scenarios.recommendation.maxDuration, '183s');
  assert.equal(options.thresholds.fitzza_recommendation_accept_ms[0], 'p(95)<500');
  assert.equal(options.thresholds.fitzza_recommendation_complete_ms[0], 'p(95)<2000');
});
