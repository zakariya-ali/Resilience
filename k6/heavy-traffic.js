// TEST 2 - Heavy traffic: ramp from 5 to 50 virtual users over ~30 seconds.
// Run: k6 run k6/heavy-traffic.js           (try also: -e MODE=SLOW)
// Run once with the "unprotected" profile and once with "protected" and compare.
import { sleep } from 'k6';
import { callOrderService, resetInventoryStats, printInventoryStats, summary } from './lib/common.js';

export const options = {
  scenarios: {
    heavy: {
      executor: 'ramping-vus',
      startVUs: 5,
      stages: [
        { duration: '10s', target: 25 },
        { duration: '10s', target: 50 },  // concurrency limit: never more than 50 VUs
        { duration: '10s', target: 50 },
      ],
      gracefulRampDown: '5s',
      gracefulStop: '5s',
    },
  },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'max'],
};

const MODE = __ENV.MODE || 'NORMAL';

export function setup() { resetInventoryStats(); }

export default function () {
  callOrderService(MODE);
  sleep(0.2); // each user sends up to ~5 req/s -> up to ~250 req/s at 50 VUs, far above the 10 req/s limit
}

export function teardown() { printInventoryStats(); }

export function handleSummary(data) { return summary(data, `TEST 2 HEAVY TRAFFIC (mode=${MODE})`); }
