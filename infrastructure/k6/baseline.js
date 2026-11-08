// Local baseline for one laptop. This is not a capacity test.
// Warm-up is the first scenario: 1 VU for 10s, product listing only.
// Measured reads stay under the gateway limit of 120 reads per minute per client.
// Measured writes stay under 30 writes per minute per client.
// One SUCCESS checkout spends one unit of the cotton shirt. FAILURE checkouts
// are the payment-failure path and should return that stock through the saga.
// Set RUN_ID to a new short token before each run so checkout keys are not replays.
// ONLY=checkout runs just the normal and concurrent checkout scenarios.
// The last scenario probes GET /api/v1/flash-sales and expects 404.
// Campaign checkout lives under /api/v1/inventory/campaigns and is measured by flash-sale.js.

import http from 'k6/http';
import exec from 'k6/execution';
import { check, sleep } from 'k6';
import { Trend } from 'k6/metrics';

const listingDuration = new Trend('listing_duration', true);
const detailDuration = new Trend('detail_duration', true);
const searchDuration = new Trend('search_duration', true);
const categoryDuration = new Trend('category_duration', true);
const checkoutFailureDuration = new Trend('checkout_failure_duration', true);
const concurrentCheckoutDuration = new Trend('concurrent_checkout_duration', true);
const checkoutSuccessDuration = new Trend('checkout_success_duration', true);
const reservationDuration = new Trend('reservation_duration', true);
const releaseDuration = new Trend('reservation_release_duration', true);
const orderListDuration = new Trend('order_list_duration', true);
const orderDetailDuration = new Trend('order_detail_duration', true);

const base = __ENV.BASE_URL || 'http://host.docker.internal:8080';
const keycloak = __ENV.KEYCLOAK_URL || 'http://host.docker.internal:8088';
const lamp = '8f1c2a10-6b3e-4a1d-9c20-0a0b0c0d1001';
const shirt = '8f1c2a10-6b3e-4a1d-9c20-0a0b0c0d1002';
const home = '8f1c2a10-6b3e-4a1d-9c20-0a0b0c0d0002';
const knownOrder = '1dbe8277-00d8-46e5-9c41-1bca4f4b5d64';

const readPause = 1.2;
const runId = __ENV.RUN_ID || 'a';

const fullOptions = {
  summaryTrendStats: ['count', 'avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  scenarios: {
    warmup: {
      executor: 'constant-vus',
      vus: 1,
      duration: '10s',
      gracefulStop: '1s',
      exec: 'listing',
    },
    listing: {
      executor: 'constant-vus',
      vus: 2,
      duration: '30s',
      startTime: '15s',
      gracefulStop: '1s',
      exec: 'listing',
    },
    detail: {
      executor: 'constant-vus',
      vus: 2,
      duration: '30s',
      startTime: '55s',
      gracefulStop: '1s',
      exec: 'detail',
    },
    search: {
      executor: 'constant-vus',
      vus: 2,
      duration: '30s',
      startTime: '95s',
      gracefulStop: '1s',
      exec: 'search',
    },
    category: {
      executor: 'constant-vus',
      vus: 2,
      duration: '30s',
      startTime: '135s',
      gracefulStop: '1s',
      exec: 'category',
    },
    checkout: {
      executor: 'shared-iterations',
      vus: 1,
      iterations: 4,
      startTime: '175s',
      maxDuration: '40s',
      gracefulStop: '1s',
      exec: 'checkoutFailure',
    },
    concurrentCheckout: {
      executor: 'shared-iterations',
      vus: 2,
      iterations: 4,
      startTime: '220s',
      maxDuration: '40s',
      gracefulStop: '1s',
      exec: 'checkoutFailure',
    },
    reservation: {
      executor: 'shared-iterations',
      vus: 1,
      iterations: 3,
      startTime: '265s',
      maxDuration: '40s',
      gracefulStop: '1s',
      exec: 'reservation',
    },
    orderStatus: {
      executor: 'constant-vus',
      vus: 1,
      duration: '20s',
      startTime: '310s',
      gracefulStop: '1s',
      exec: 'orderStatus',
    },
    flashSale: {
      executor: 'shared-iterations',
      vus: 1,
      iterations: 1,
      startTime: '335s',
      maxDuration: '15s',
      gracefulStop: '1s',
      exec: 'flashSaleProbe',
    },
    checkoutSuccess: {
      executor: 'shared-iterations',
      vus: 1,
      iterations: 1,
      startTime: '350s',
      maxDuration: '30s',
      gracefulStop: '1s',
      exec: 'checkoutSuccess',
    },
  },
};

const checkoutOptions = {
  summaryTrendStats: ['count', 'avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  scenarios: {
    checkout: {
      executor: 'shared-iterations',
      vus: 1,
      iterations: 4,
      maxDuration: '40s',
      gracefulStop: '1s',
      exec: 'checkoutFailure',
    },
    concurrentCheckout: {
      executor: 'shared-iterations',
      vus: 2,
      iterations: 4,
      startTime: '20s',
      maxDuration: '40s',
      gracefulStop: '1s',
      exec: 'checkoutFailure',
    },
  },
};

export const options = __ENV.ONLY === 'checkout' ? checkoutOptions : fullOptions;

function params(name, extraHeaders) {
  return {
    headers: extraHeaders || {},
    tags: { name },
  };
}

export function listing() {
  const response = http.get(
    `${base}/api/v1/products?page=0&size=20`,
    params('listing'),
  );
  if (exec.scenario.name !== 'warmup') {
    listingDuration.add(response.timings.duration);
  }
  check(response, { 'listing 200': (r) => r.status === 200 });
  sleep(readPause);
}

export function detail() {
  const response = http.get(`${base}/api/v1/products/${lamp}`, params('detail'));
  detailDuration.add(response.timings.duration);
  check(response, { 'detail 200': (r) => r.status === 200 });
  sleep(readPause);
}

export function search() {
  const response = http.get(`${base}/api/v1/products?q=lamp&page=0&size=20`, params('search'));
  searchDuration.add(response.timings.duration);
  check(response, { 'search 200': (r) => r.status === 200 });
  sleep(readPause);
}

export function category() {
  const response = http.get(
    `${base}/api/v1/products?categoryId=${home}&page=0&size=20`,
    params('category'),
  );
  categoryDuration.add(response.timings.duration);
  check(response, { 'category 200': (r) => r.status === 200 });
  sleep(readPause);
}

let cachedToken;

function bearer() {
  if (!cachedToken) {
    const response = http.post(
      `${keycloak}/realms/bazaar/protocol/openid-connect/token`,
      'grant_type=password&client_id=bazaar-dev&username=customer&password=customer_local',
      {
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        tags: { name: 'token' },
      },
    );
    cachedToken = response.json('access_token');
  }
  return {
    Authorization: `Bearer ${cachedToken}`,
    'Content-Type': 'application/json',
  };
}

function checkout(outcome, name) {
  const key = `m2${runId}${exec.scenario.name}${__VU}i${__ITER}${outcome}`;
  const response = http.post(
    `${base}/api/v1/orders/checkout`,
    JSON.stringify({
      items: [{ productId: shirt, quantity: 1 }],
      outcome,
    }),
    {
      headers: Object.assign({ 'Idempotency-Key': key }, bearer()),
      tags: { name },
    },
  );
  check(response, {
    'checkout accepted': (r) => r.status === 201 || r.status === 200,
  });
  return response;
}

export function checkoutFailure() {
  const started = Date.now();
  checkout('FAILURE', 'checkout-failure');
  const elapsed = Date.now() - started;
  if (exec.scenario.name === 'concurrentCheckout') {
    concurrentCheckoutDuration.add(elapsed);
  } else {
    checkoutFailureDuration.add(elapsed);
  }
  sleep(1);
}

export function checkoutSuccess() {
  const started = Date.now();
  const response = checkout('SUCCESS', 'checkout-success');
  checkoutSuccessDuration.add(Date.now() - started);
  const id = response.json('id');
  sleep(2);
  if (!id) {
    return;
  }
  const status = http.get(`${base}/api/v1/orders/${id}`, {
    headers: bearer(),
    tags: { name: 'order-after-success' },
  });
  check(status, { 'order readable': (r) => r.status === 200 });
}

export function reservation() {
  const key = `m2${runId}res${__VU}i${__ITER}`;
  const reserved = http.post(
    `${base}/api/v1/inventory/reservations`,
    JSON.stringify({ productId: shirt, quantity: 1 }),
    {
      headers: Object.assign({ 'Idempotency-Key': key }, bearer()),
      tags: { name: 'reservation' },
    },
  );
  reservationDuration.add(reserved.timings.duration);
  const held = check(reserved, { 'reserved 201': (r) => r.status === 201 });
  const id = reserved.json('id');
  if (held && id) {
    const released = http.post(
      `${base}/api/v1/inventory/reservations/${id}/release`,
      null,
      { headers: bearer(), tags: { name: 'reservation-release' } },
    );
    releaseDuration.add(released.timings.duration);
    check(released, { 'released 200': (r) => r.status === 200 });
  }
  sleep(1);
}

export function orderStatus() {
  const list = http.get(`${base}/api/v1/orders`, {
    headers: bearer(),
    tags: { name: 'order-list' },
  });
  orderListDuration.add(list.timings.duration);
  check(list, { 'order list 200': (r) => r.status === 200 });
  const one = http.get(`${base}/api/v1/orders/${knownOrder}`, {
    headers: bearer(),
    tags: { name: 'order-detail' },
  });
  orderDetailDuration.add(one.timings.duration);
  check(one, { 'known order 200': (r) => r.status === 200 });
  sleep(1.2);
}

export function flashSaleProbe() {
  const response = http.get(`${base}/api/v1/flash-sales`, {
    tags: { name: 'flash-sale-probe' },
    responseCallback: http.expectedStatuses(404),
  });
  check(response, { 'flash sale route absent': (r) => r.status === 404 });
}
