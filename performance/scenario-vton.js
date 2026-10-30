/**
 * VTON: acceptance and generated-image completion latency (no image download).
 * ACCESS_TOKEN must be supplied through the local environment (without Bearer).
 * k6 run -e PHOTO_ID=123 -e PRODUCT_IDS=101,201 performance/scenario-vton.js
 * Replace example IDs: photo must belong to the account, products must be real,
 * fit-compatible products from distinct categories (2 to 5 IDs).
 * Optional: BASE_URL=http://localhost:8080, VUS=1, ITERATIONS=1,
 * POLL_INTERVAL_SECONDS=2, WAIT_TIMEOUT_SECONDS=600, HTTP_TIMEOUT_SECONDS=30,
 * ACCEPT_P95_MS, COMPLETE_P95_MS. ITERATIONS must be >=VUS.
 * Each iteration makes real AI calls and retains its DB records and generated image.
 * Completion includes queue, inference, storage and polling delay (up to one interval
 * plus network time). Interpret successful latency together with success/failure counts.
 */
import { asyncScenario, positiveNumber } from './async-job.js';

const photoId = positiveNumber('PHOTO_ID', undefined, true);
const productIds = (__ENV.PRODUCT_IDS || '').split(',').map(value => Number(value.trim()));
if (productIds.length < 2 || productIds.length > 5
    || productIds.some(value => !Number.isSafeInteger(value) || value <= 0)
    || new Set(productIds).size !== productIds.length) {
  throw new Error('PRODUCT_IDS must contain 2 to 5 distinct positive integers separated by commas');
}
const scenario = asyncScenario(
  'vton', '/api/v1/vton', 'fittingId', 'PENDING', 'COMPLETED', { photoId, productIds },
  result => typeof result.resultImageUrl === 'string' && result.resultImageUrl.trim().length > 0,
);
export const options = scenario.options;
export default function () { scenario.run(); }
