// TEST 3 - Dependency failure: Inventory Service always returns HTTP 500.
// Sends a steady 8 requests/second for 40 seconds (below the 10 req/s rate limit,
// so this test isolates Circuit Breaker + Retry). 40s = enough for several OPEN -> HALF_OPEN cycles.
// Run: k6 run k6/dependency-failure.js      (optional: -e MODE=RANDOM)
import { callOrderService, resetInventoryStats, printInventoryStats, summary } from './lib/common.js';

export const options = {
  scenarios: {
    failure: {
      executor: 'constant-arrival-rate',
      rate: 8,
      timeUnit: '1s',
      duration: '40s',
      preAllocatedVUs: 10,
      maxVUs: 30,           // concurrency limit
      gracefulStop: '5s',
    },
  },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'max'],
};

const MODE = __ENV.MODE || 'FAILURE';

export function setup() { resetInventoryStats(); }

export default function () {
  callOrderService(MODE);
}

export function teardown() { printInventoryStats(); }

export function handleSummary(data) { return summary(data, `TEST 3 DEPENDENCY FAILURE (mode=${MODE})`); }
