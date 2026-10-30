/**
 * Natural-language recommendation: acceptance and completion latency.
 * ACCESS_TOKEN must be supplied through the local environment (without Bearer).
 * k6 run -e VUS=1 -e ITERATIONS=1 performance/scenario-recommendation.js
 * Optional: BASE_URL, QUERY, POLL_INTERVAL_SECONDS=2, WAIT_TIMEOUT_SECONDS=600,
 * HTTP_TIMEOUT_SECONDS=30, ACCEPT_P95_MS, COMPLETE_P95_MS.
 * No latency SLO is imposed unless configured; completion success must be >=95%.
 * Completion includes queue, inference, persistence and polling delay, not image downloads.
 * Empty DONE results count as completed. Generated data is retained.
 * VUs share the account/query: personalization and caching can affect results.
 */
import { asyncScenario } from './async-job.js';

const query = (__ENV.QUERY === undefined ? '주말 나들이에 어울리는 캐주얼 코디를 추천해주세요.' : __ENV.QUERY).trim();
if (!query) throw new Error('QUERY must not be blank');
const scenario = asyncScenario(
  'recommendation', '/api/v1/recommendations', 'requestId', 'PROCESSING', 'DONE',
  { query }, result => Array.isArray(result.items) && Array.isArray(result.combos),
);
export const options = scenario.options;
export default function () { scenario.run(); }
