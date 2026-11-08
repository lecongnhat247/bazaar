// Small flash-sale check for this laptop. This is not a capacity test.
// One customer token is shared, so the per-customer limit must be at least ITERATIONS
// or the limit rejects before the quota does. Defaults stay under the gateway
// write limit of 30 requests a minute per client address.
// HTTP 201 means the order was accepted. The campaign remaining count is the
// confirmation, after the saga reserves stock. Do not use the lamp or shirt seeds.
//
// QUOTA, LIMIT, ON_HAND, VUS, ITERATIONS, DURATION, and RUN_ID are environment values.
// Example: QUOTA=5 LIMIT=8 ON_HAND=20 VUS=4 ITERATIONS=8 RUN_ID=m4a

import http from 'k6/http';
import exec from 'k6/execution';
import { check, sleep } from 'k6';

const base = __ENV.BASE_URL || 'http://host.docker.internal:8080';
const keycloak = __ENV.KEYCLOAK_URL || 'http://host.docker.internal:8088';
const home = '8f1c2a10-6b3e-4a1d-9c20-0a0b0c0d0002';
const quota = Number(__ENV.QUOTA || 5);
const limit = Number(__ENV.LIMIT || 8);
const onHand = Number(__ENV.ON_HAND || 20);
const runId = __ENV.RUN_ID || 'm4a';

export const options = {
  scenarios: {
    flash: {
      executor: 'shared-iterations',
      vus: Number(__ENV.VUS || 4),
      iterations: Number(__ENV.ITERATIONS || 8),
      maxDuration: __ENV.DURATION || '30s',
    },
  },
};

export function setup() {
  const seller = token('seller', 'seller_local');
  const sku = `FLASH${Date.now()}`;
  const created = http.post(
    `${base}/api/v1/products`,
    JSON.stringify({
      categoryId: home,
      name: 'Flash sale sample',
      description: 'Controlled campaign product',
      sku,
      price: 12.0,
      currency: 'USD',
    }),
    { headers: seller },
  );
  if (created.status !== 201) {
    throw new Error(`product create ${created.status} ${created.body}`);
  }
  const productId = created.json('id');
  const active = http.patch(
    `${base}/api/v1/products/${productId}/status`,
    JSON.stringify({ status: 'ACTIVE' }),
    { headers: seller },
  );
  if (active.status !== 200) {
    throw new Error(`product activate ${active.status} ${active.body}`);
  }
  const stock = http.put(
    `${base}/api/v1/inventory/products/${productId}`,
    JSON.stringify({ onHand }),
    { headers: seller },
  );
  if (stock.status !== 200) {
    throw new Error(`stock ${stock.status} ${stock.body}`);
  }
  const start = new Date(Date.now() - 60_000).toISOString();
  const end = new Date(Date.now() + 3_600_000).toISOString();
  const campaign = http.post(
    `${base}/api/v1/inventory/campaigns`,
    JSON.stringify({
      productId,
      price: 9.0,
      currency: 'USD',
      quota,
      perCustomerLimit: limit,
      startsAt: start,
      endsAt: end,
    }),
    { headers: seller },
  );
  if (campaign.status !== 201) {
    throw new Error(`campaign ${campaign.status} ${campaign.body}`);
  }
  const campaignId = campaign.json('id');
  const scheduled = http.post(
    `${base}/api/v1/inventory/campaigns/${campaignId}/schedule`,
    null,
    { headers: seller },
  );
  if (scheduled.status !== 200 || scheduled.json('status') !== 'ACTIVE') {
    throw new Error(`schedule ${scheduled.status} ${scheduled.body}`);
  }
  console.log(`campaign ${campaignId} product ${productId} quota ${quota} limit ${limit} onHand ${onHand}`);
  return { productId, campaignId, customer: token('customer', 'customer_local') };
}

export default function (data) {
  const key = `m4${runId}v${exec.vu.idInTest}i${exec.scenario.iterationInTest}`;
  const response = http.post(
    `${base}/api/v1/orders/checkout`,
    JSON.stringify({
      items: [{ productId: data.productId, quantity: 1 }],
      outcome: 'SUCCESS',
      campaignId: data.campaignId,
    }),
    { headers: Object.assign({ 'Idempotency-Key': key }, data.customer) },
  );
  check(response, { 'checkout accepted': (r) => r.status === 201 || r.status === 200 });
  sleep(0.2);
}

export function teardown(data) {
  const deadline = Date.now() + 20_000;
  let remaining = -1;
  while (Date.now() < deadline) {
    const response = http.get(`${base}/api/v1/inventory/campaigns/${data.campaignId}`, {
      headers: data.customer,
    });
    if (response.status === 200) {
      remaining = response.json('remaining');
      if (remaining === 0) {
        break;
      }
    }
    sleep(1);
  }
  console.log(`campaign ${data.campaignId} remaining ${remaining}`);
  check({ remaining }, { 'quota exhausted': (value) => value.remaining === 0 });
}

function token(username, password) {
  const response = http.post(
    `${keycloak}/realms/bazaar/protocol/openid-connect/token`,
    `grant_type=password&client_id=bazaar-dev&username=${username}&password=${password}`,
    { headers: { 'Content-Type': 'application/x-www-form-urlencoded' } },
  );
  if (response.status !== 200) {
    throw new Error(`token ${username} ${response.status}`);
  }
  return {
    Authorization: `Bearer ${response.json('access_token')}`,
    'Content-Type': 'application/json',
  };
}
