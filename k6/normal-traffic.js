// TEST 1 - Normal traffic: 5 virtual users for 15 seconds.
// Run: k6 run k6/normal-traffic.js          (optional: -e MODE=SLOW)
import { sleep } from 'k6';
import { callOrderService, resetInventoryStats, printInventoryStats, summary } from './lib/common.js';

export const options = {
  scenarios: {
    normal: {
      executor: 'constant-vus',
      vus: 5,                // concurrency limit
      duration: '15s',
      gracefulStop: '5s',
    },
  },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'max'],
};

const MODE = __ENV.MODE || 'NORMAL';

export function setup() { resetInventoryStats(); }

export default function () {
  callOrderService(MODE);
  sleep(1); // each user sends ~1 request per second -> ~5 req/s, well below the 10 req/s limit
}

export function teardown() { printInventoryStats(); }

export function handleSummary(data) { return summary(data, `TEST 1 NORMAL TRAFFIC (mode=${MODE})`); }
