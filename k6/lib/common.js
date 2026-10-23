// Shared helpers for all k6 tests: safety guard, metrics and the final summary table.
import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.1.0/index.js';

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
export const INVENTORY_URL = __ENV.INVENTORY_URL || 'http://localhost:8081';

// ---- SAFETY GUARD: only ever target our own local services ----
const ALLOWED_HOSTS = ['localhost', '127.0.0.1', 'host.docker.internal', 'order-service', 'inventory-service'];
for (const url of [BASE_URL, INVENTORY_URL]) {
  const host = url.replace(/^https?:\/\//, '').split(/[:/]/)[0];
  if (!ALLOWED_HOSTS.includes(host)) {
    throw new Error(`Refusing to load-test ${url}: only local demo services are allowed.`);
  }
}

// ---- Custom metrics (only for /api/orders/check calls, not setup/teardown) ----
const orderRequests = new Counter('order_requests');
const orderSuccess = new Counter('order_success');      // HTTP 200
const orderFailed = new Counter('order_failed');        // anything else
const status429 = new Counter('status_429');            // rate limited
const status500 = new Counter('status_500');            // dependency failed
const status503 = new Counter('status_503');            // circuit open / unreachable
const orderDuration = new Trend('order_duration', true);

/** Calls GET /api/orders/check and records the metrics. */
export function callOrderService(mode) {
  const url = mode ? `${BASE_URL}/api/orders/check?mode=${mode}` : `${BASE_URL}/api/orders/check`;
  const res = http.get(url, { timeout: '10s' });

  orderRequests.add(1);
  orderDuration.add(res.timings.duration);
  if (res.status === 200) orderSuccess.add(1); else orderFailed.add(1);
  if (res.status === 429) status429.add(1);
  if (res.status === 500) status500.add(1);
  if (res.status === 503) status503.add(1);

  check(res, { 'status is 200': (r) => r.status === 200 });
  return res;
}

/** setup(): reset Inventory Service counters so each run starts from zero. */
export function resetInventoryStats() {
  http.post(`${INVENTORY_URL}/api/inventory/stats/reset`);
}

/** teardown(): show how many calls actually reached the Inventory Service. */
export function printInventoryStats() {
  const res = http.get(`${INVENTORY_URL}/api/inventory/stats`);
  console.log(`\n=== Calls that reached inventory-service: ${res.body} ===\n`);
}

/** Prints the default k6 summary followed by a compact results table. */
export function summary(data, testName) {
  const m = data.metrics;
  const count = (name) => (m[name] ? m[name].values.count : 0);
  const fmt = (n) => (n === undefined ? '-' : n.toFixed(1));

  const table = `
================ ${testName} : RESULTS ================
Requests per second   : ${fmt(m.order_requests ? m.order_requests.values.rate : 0)}
Total requests        : ${count('order_requests')}
Successful (200)      : ${count('order_success')}
Failed (non-200)      : ${count('order_failed')}
Average response time : ${fmt(m.order_duration && m.order_duration.values.avg)} ms
p95 response time     : ${fmt(m.order_duration && m.order_duration.values['p(95)'])} ms
HTTP 429 (rate limit) : ${count('status_429')}
HTTP 500 (dependency) : ${count('status_500')}
HTTP 503 (cb open)    : ${count('status_503')}
=======================================================
`;
  return { stdout: textSummary(data, { indent: ' ', enableColors: true }) + '\n' + table };
}
