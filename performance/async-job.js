import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

export function positiveNumber(name, fallback, integer = false) {
  const value = Number(__ENV[name] === undefined ? fallback : __ENV[name]);
  if (!Number.isFinite(value) || value <= 0 || (integer && !Number.isSafeInteger(value))) {
    throw new Error(`${name} must be a positive ${integer ? 'integer' : 'number'}`);
  }
  return value;
}

export function asyncScenario(service, path, idField, pending, completed, payload, validateResult) {
  const baseUrl = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/$/, '');
  if (!/^https?:\/\/[a-zA-Z0-9.-]+(?::[0-9]{1,5})?$/.test(baseUrl)) {
    throw new Error('BASE_URL must be an HTTP(S) origin without credentials or a path');
  }
  const token = (__ENV.ACCESS_TOKEN || '').trim();
  if (!token || /^Bearer\s/i.test(token)) throw new Error('ACCESS_TOKEN is required without Bearer prefix');
  const vus = positiveNumber('VUS', 1, true);
  const iterations = positiveNumber('ITERATIONS', 1, true);
  if (iterations < vus) throw new Error('ITERATIONS must be at least VUS');
  const interval = positiveNumber('POLL_INTERVAL_SECONDS', 2);
  const wait = positiveNumber('WAIT_TIMEOUT_SECONDS', 600);
  const requestTimeout = positiveNumber('HTTP_TIMEOUT_SECONDS', 30);
  const prefix = `fitzza_${service}`;
  const accept = new Trend(`${prefix}_accept_ms`, true);
  const complete = new Trend(`${prefix}_complete_ms`, true);
  const success = new Rate(`${prefix}_success`);
  const failures = new Counter(`${prefix}_failures`);
  const timeouts = new Counter(`${prefix}_timeouts`);
  const thresholds = { [`${prefix}_success`]: ['rate>=0.95'] };
  for (const [variable, metric] of [['ACCEPT_P95_MS', 'accept_ms'], ['COMPLETE_P95_MS', 'complete_ms']]) {
    if (__ENV[variable] !== undefined) thresholds[`${prefix}_${metric}`] = [`p(95)<${positiveNumber(variable)}`];
  }
  const options = {
    scenarios: {
      [service]: { executor: 'shared-iterations', vus, iterations,
        maxDuration: `${Math.ceil(iterations / vus) * (wait + 60)}s` },
    },
    thresholds,
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
    // Do not create one metric series per generated job URL.
    systemTags: ['status', 'method', 'name', 'scenario', 'expected_response', 'error_code'],
  };

  function run() {
    const started = Date.now();
    const deadline = started + wait * 1000;
    let ok = false;
    let timedOut = false;
    failures.add(0);
    timeouts.add(0);
    const params = method => ({
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json', Accept: 'application/json' },
      tags: { name: `${method} ${path}${method === 'GET' ? '/{id}' : ''}` },
      timeout: Math.max(1, Math.min(requestTimeout * 1000, deadline - Date.now())),
      redirects: 0,
    });
    function parse(res, status) {
      if (res.status !== status) throw new Error(`Unexpected HTTP status ${res.status}`);
      try { return res.json(); } catch (_) { throw new Error('Invalid JSON response'); }
    }
    try {
      const accepted = parse(http.post(`${baseUrl}${path}`, JSON.stringify(payload), params('POST')), 202);
      if (Date.now() >= deadline) throw new Error('Job deadline exceeded');
      if (!accepted || !Number.isSafeInteger(accepted[idField]) || accepted[idField] <= 0 || accepted.status !== pending) {
        throw new Error('Invalid acceptance ID or status');
      }
      accept.add(Date.now() - started);
      while (Date.now() < deadline) {
        const detail = parse(http.get(`${baseUrl}${path}/${accepted[idField]}`, params('GET')), 200);
        if (Date.now() >= deadline) break;
        if (!detail || detail[idField] !== accepted[idField]) throw new Error('Invalid result ID');
        if (detail.status === completed) {
          if (!validateResult(detail)) throw new Error('Invalid completed result');
          complete.add(Date.now() - started);
          ok = true;
          break;
        }
        if (detail.status !== pending) throw new Error(detail.status === 'FAILED' ? 'Job failed' : 'Invalid job status');
        sleep(Math.min(interval, Math.max(0, (deadline - Date.now()) / 1000)));
      }
      if (!ok) throw new Error('Job deadline exceeded');
    } catch (error) {
      timedOut = Date.now() >= deadline;
      // Never print response bodies, credentials, user input, or signed URLs.
      console.error(`${service}: ${timedOut ? 'Job deadline exceeded' : error.message}`);
    } finally {
      success.add(ok);
      if (!ok) failures.add(1);
      if (timedOut) timeouts.add(1);
      check(ok, { [`${service} completed successfully`]: value => value });
    }
  }
  return { options, run };
}
