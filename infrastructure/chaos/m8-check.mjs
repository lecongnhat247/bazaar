// Controlled failure checks for this laptop. Not a chaos platform and not a capacity test.
// Stops one Compose service at a time and starts it again. Does not remove volumes.
// Does not checkout the lamp or shirt seeds.

import { execFile } from 'node:child_process';
import { writeFileSync } from 'node:fs';

const root = 'D:/bazaar';
const gateway = 'http://localhost:8080';
const keycloak = 'http://localhost:8088';
const notifications = 'http://localhost:8086';
const lamp = '8f1c2a10-6b3e-4a1d-9c20-0a0b0c0d1001';
const shirt = '8f1c2a10-6b3e-4a1d-9c20-0a0b0c0d1002';
const home = '8f1c2a10-6b3e-4a1d-9c20-0a0b0c0d0002';
const report = [];

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function sh(args, timeout = 120000) {
  return new Promise((resolve, reject) => {
    execFile('docker', args, { cwd: root, timeout, windowsHide: true }, (error, stdout, stderr) => {
      const out = `${stdout || ''}${stderr || ''}`.trim();
      if (error) {
        reject(new Error(out || error.message));
        return;
      }
      resolve(out);
    });
  });
}

function record(name, fields) {
  const row = { name, ...fields };
  report.push(row);
  console.log(JSON.stringify(row));
}

async function psql(db, sql) {
  return sh(['exec', 'bazaar-postgres-1', 'psql', '-U', 'bazaar', '-d', db, '-t', '-A', '-c', sql]);
}

async function stock(productId) {
  const line = await psql(
    'inventory_db',
    `SELECT available || ',' || reserved FROM inventory_item WHERE product_id = '${productId}'`,
  );
  const [available, reserved] = line.split(',').map(Number);
  return { available, reserved };
}

async function orderCount() {
  const line = await psql('order_db', 'SELECT count(*) FROM customer_order');
  return Number(line);
}

async function token(username, password) {
  const body = new URLSearchParams({
    grant_type: 'password',
    client_id: 'bazaar-dev',
    username,
    password,
  });
  const response = await fetch(`${keycloak}/realms/bazaar/protocol/openid-connect/token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body,
  });
  if (!response.ok) {
    throw new Error(`token ${username} ${response.status}`);
  }
  const json = await response.json();
  return json.access_token;
}

async function request(url, options = {}, timeoutMs = 20000) {
  const started = Date.now();
  try {
    const response = await fetch(url, { ...options, signal: AbortSignal.timeout(timeoutMs) });
    const text = await response.text();
    return { status: response.status, text, body: text.slice(0, 240), ms: Date.now() - started };
  } catch (error) {
    return { status: 0, text: '', body: String(error.message || error).slice(0, 240), ms: Date.now() - started };
  }
}

async function health(container) {
  return sh([
    'inspect',
    '--format',
    '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}',
    container,
  ]);
}

async function waitHealthy(container, timeoutMs = 120000) {
  const started = Date.now();
  let last = '';
  while (Date.now() - started < timeoutMs) {
    last = await health(container);
    if (last === 'healthy') {
      return Date.now() - started;
    }
    await sleep(2000);
  }
  throw new Error(`${container} stayed ${last}`);
}

async function withStopped(service, body) {
  await sh(['compose', 'stop', service]);
  try {
    return await body();
  } finally {
    await sh(['compose', 'start', service]);
  }
}

async function main() {
  const beforeOrders = await orderCount();
  const beforeLamp = await stock(lamp);
  const beforeShirt = await stock(shirt);
  record('snapshot', { orders: beforeOrders, lamp: beforeLamp, shirt: beforeShirt });

  const seller = await token('seller', 'seller_local');
  const customer = await token('customer', 'customer_local');
  const jsonHeaders = (bearer) => ({
    Authorization: `Bearer ${bearer}`,
    'Content-Type': 'application/json',
  });

  const anonymous = await request(`${notifications}/api/v1/notifications`);
  const ownNotices = await request(`${notifications}/api/v1/notifications`, {
    headers: { Authorization: `Bearer ${customer}` },
  });
  record('notification-auth', { anonymous: anonymous.status, customer: ownNotices.status });

  const catalogDown = await withStopped('catalog-service', () =>
    request(`${gateway}/api/v1/orders/checkout`, {
      method: 'POST',
      headers: { ...jsonHeaders(customer), 'Idempotency-Key': 'm8catalogdown01' },
      body: JSON.stringify({
        items: [{ productId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1', quantity: 1 }],
        outcome: 'SUCCESS',
      }),
    }),
  );
  const ordersAfterCatalog = await orderCount();
  await waitHealthy('bazaar-catalog-service-1');
  record('catalog-stopped', {
    checkout: catalogDown.status,
    ms: catalogDown.ms,
    body: catalogDown.body,
    ordersBefore: beforeOrders,
    ordersAfter: ordersAfterCatalog,
  });

  const orderDown = await withStopped('order-service', () =>
    request(`${gateway}/api/v1/orders/checkout`, {
      method: 'POST',
      headers: { ...jsonHeaders(customer), 'Idempotency-Key': 'm8orderdown0001' },
      body: JSON.stringify({
        items: [{ productId: lamp, quantity: 1 }],
        outcome: 'FAILURE',
      }),
    }),
  );
  await waitHealthy('bazaar-order-service-1');
  record('order-stopped', { checkout: orderDown.status, ms: orderDown.ms, body: orderDown.body });

  const redisRead = await withStopped('redis', () =>
    request(`${gateway}/api/v1/products/${lamp}`),
  );
  await waitHealthy('bazaar-redis-1');
  record('redis-stopped', { product: redisRead.status, ms: redisRead.ms });

  const sku = `M8${Date.now()}`;
  const created = await request(`${gateway}/api/v1/products`, {
    method: 'POST',
    headers: jsonHeaders(seller),
    body: JSON.stringify({
      categoryId: home,
      name: 'Chaos sample',
      description: 'Disposable product for the M8 check',
      sku,
      price: 11,
      currency: 'USD',
    }),
  });
  if (created.status !== 201) {
    throw new Error(`product create ${created.status} ${created.body}`);
  }
  const productId = JSON.parse(created.text).id;
  await request(`${gateway}/api/v1/products/${productId}/status`, {
    method: 'PATCH',
    headers: jsonHeaders(seller),
    body: JSON.stringify({ status: 'ACTIVE' }),
  });
  await request(`${gateway}/api/v1/inventory/products/${productId}`, {
    method: 'PUT',
    headers: jsonHeaders(seller),
    body: JSON.stringify({ onHand: 4 }),
  });

  const key = 'm8kafkarecovery1';
  const accepted = await withStopped('kafka', () =>
    request(`${gateway}/api/v1/orders/checkout`, {
      method: 'POST',
      headers: { ...jsonHeaders(customer), 'Idempotency-Key': key },
      body: JSON.stringify({
        items: [{ productId, quantity: 1 }],
        outcome: 'SUCCESS',
      }),
    }),
  );
  await waitHealthy('bazaar-kafka-1', 90000);
  let confirmed = null;
  const deadline = Date.now() + 45000;
  while (Date.now() < deadline) {
    const line = await psql(
      'order_db',
      `SELECT status FROM customer_order WHERE idempotency_key = '${key}'`,
    );
    if (line === 'CONFIRMED') {
      confirmed = line;
      break;
    }
    confirmed = line;
    await sleep(2000);
  }
  const replay = await request(`${gateway}/api/v1/orders/checkout`, {
    method: 'POST',
    headers: { ...jsonHeaders(customer), 'Idempotency-Key': key },
    body: JSON.stringify({
      items: [{ productId, quantity: 1 }],
      outcome: 'SUCCESS',
    }),
  });
  const copies = await psql(
    'order_db',
    `SELECT count(*) FROM customer_order WHERE idempotency_key = '${key}'`,
  );
  const orderId = await psql(
    'order_db',
    `SELECT id FROM customer_order WHERE idempotency_key = '${key}'`,
  );
  const payments = await psql(
    'payment_db',
    `SELECT count(*) FROM payment WHERE order_id = '${orderId}' AND status = 'SUCCEEDED'`,
  );
  const pendingOutbox = await psql(
    'order_db',
    `SELECT count(*) FROM outbox WHERE aggregate_id = '${orderId}' AND status <> 'PUBLISHED'`,
  );
  const sampleStock = await stock(productId);
  record('kafka-stopped', {
    checkout: accepted.status,
    ms: accepted.ms,
    statusAfterRecovery: confirmed,
    replay: replay.status,
    orderRows: Number(copies),
    succeededPayments: Number(payments),
    unpublishedOutbox: Number(pendingOutbox),
    stock: sampleStock,
  });

  let lag = 'not-read';
  try {
    lag = await sh([
      'exec',
      'bazaar-kafka-1',
      '/opt/kafka/bin/kafka-consumer-groups.sh',
      '--bootstrap-server',
      'localhost:9092',
      '--describe',
      '--all-groups',
    ], 20000);
  } catch (error) {
    lag = String(error.message || error).slice(0, 300);
  }
  const lagLines = lag.split('\n').filter((line) => line && !line.startsWith('GROUP') && !line.includes('has no active members'));
  record('consumer-lag', { sample: lagLines.slice(0, 8) });

  const stats = await sh(['stats', '--no-stream', '--format', '{{.Name}} {{.MemUsage}} {{.CPUPerc}}']);
  record('resources', { containers: stats.split('\n').filter((line) => line.startsWith('bazaar-')) });

  const postgresDown = await withStopped('postgres', () => request(`${gateway}/api/v1/products/${lamp}`));
  await waitHealthy('bazaar-postgres-1', 120000);
  await waitHealthy('bazaar-keycloak-1', 180000);
  await waitHealthy('bazaar-catalog-service-1', 180000);
  await waitHealthy('bazaar-order-service-1', 180000);
  await waitHealthy('bazaar-api-gateway-1', 180000);
  const postgresBack = await request(`${gateway}/api/v1/products/${lamp}`);
  const afterLamp = await stock(lamp);
  const afterShirt = await stock(shirt);
  record('postgres-stopped', {
    productWhileDown: postgresDown.status,
    ms: postgresDown.ms,
    productAfter: postgresBack.status,
    lamp: afterLamp,
    shirt: afterShirt,
  });

  writeFileSync(
    `${root}/infrastructure/k6/results/m8-chaos.json`,
    JSON.stringify({ at: new Date().toISOString(), report }, null, 2),
  );
}

main().catch((error) => {
  console.error(error);
  writeFileSync(
    `${root}/infrastructure/k6/results/m8-chaos.json`,
    JSON.stringify({ at: new Date().toISOString(), error: String(error), report }, null, 2),
  );
  process.exit(1);
});
