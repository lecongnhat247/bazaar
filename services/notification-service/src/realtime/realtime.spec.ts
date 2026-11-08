import { createHmac } from "node:crypto";

import { INestApplication } from "@nestjs/common";
import { NestFactory } from "@nestjs/core";
import { io, Socket } from "socket.io-client";

import { AppModule } from "../app.module";
import { NotificationService } from "../notifications/notification.service";

const SECRET = "test-secret-at-least-32-characters";
const ORDER = "8f1c2a10-6b3e-4a1d-9c20-0a0b0c0d9101";

describe("notification delivery", () => {
  let app: INestApplication;
  let base: string;

  beforeAll(async () => {
    process.env.JWT_HMAC_SECRET = SECRET;
    process.env.KEYCLOAK_ISSUER = "http://localhost:8088/realms/bazaar";
    delete process.env.DATABASE_URL;
    delete process.env.KAFKA_BROKERS;
    delete process.env.REDIS_HOST;
    app = await NestFactory.create(AppModule, { logger: false });
    await app.listen(0);
    const address = app.getHttpServer().address();
    if (!address || typeof address === "string") {
      throw new Error("The test server did not bind a port.");
    }
    base = `http://127.0.0.1:${address.port}`;
  });

  afterAll(async () => {
    await app.close();
  });

  it("rejects a socket without a token and keeps customers apart", async () => {
    const stranger = io(base, { transports: ["websocket"], reconnection: false });
    await disconnected(stranger);

    const first = io(base, { transports: ["websocket"], reconnection: false, auth: { token: await token("customer-a") } });
    const second = io(base, { transports: ["websocket"], reconnection: false, auth: { token: await token("customer-b") } });
    await connected(first);
    await connected(second);

    const seenByFirst: unknown[] = [];
    const seenBySecond: unknown[] = [];
    first.on("notification", (body) => seenByFirst.push(body));
    second.on("notification", (body) => seenBySecond.push(body));

    const eventId = "8f1c2a10-6b3e-4a1d-9c20-0a0b0c0d9102";
    await app.get(NotificationService).accept(orderEvent(eventId, "customer-a"));
    await app.get(NotificationService).accept(orderEvent(eventId, "customer-a"));
    await waitFor(() => seenByFirst.length === 1);

    expect(seenByFirst).toEqual([expect.objectContaining({ eventId, type: "ORDER_CONFIRMED", orderId: ORDER })]);
    expect(seenBySecond).toEqual([]);
    expect(JSON.stringify(seenByFirst)).not.toContain("customer-a");

    const listed = await fetch(`${base}/api/v1/notifications`, { headers: { Authorization: `Bearer ${await token("customer-a")}` } });
    expect(listed.status).toBe(200);
    const body = await listed.json() as { items: { eventId: string }[] };
    expect(body.items.map((item) => item.eventId)).toEqual([eventId]);

    const other = await fetch(`${base}/api/v1/notifications`, { headers: { Authorization: `Bearer ${await token("customer-b")}` } });
    expect(await other.json()).toEqual({ items: [] });
    const denied = await fetch(`${base}/api/v1/notifications`);
    expect(denied.status).toBe(401);

    first.close();
    const missed = "8f1c2a10-6b3e-4a1d-9c20-0a0b0c0d9103";
    await app.get(NotificationService).accept(orderEvent(missed, "customer-a", "order.payment-failed"));
    const again = io(base, { transports: ["websocket"], reconnection: false, auth: { token: await token("customer-a") } });
    const afterReconnect: unknown[] = [];
    again.on("notification", (notice) => afterReconnect.push(notice));
    await connected(again);
    const history = await fetch(`${base}/api/v1/notifications`, { headers: { Authorization: `Bearer ${await token("customer-a")}` } });
    const historyBody = await history.json() as { items: { eventId: string }[] };
    expect(historyBody.items.map((item) => item.eventId)).toEqual([eventId, missed]);
    expect(afterReconnect).toEqual([]);

    const live = "8f1c2a10-6b3e-4a1d-9c20-0a0b0c0d9104";
    await app.get(NotificationService).accept(orderEvent(live, "customer-a", "order.cancelled"));
    await waitFor(() => afterReconnect.length === 1);
    expect(afterReconnect).toEqual([expect.objectContaining({ eventId: live, type: "ORDER_CANCELLED" })]);

    second.close();
    again.close();
  });
});

function orderEvent(eventId: string, customerId: string, eventType = "order.confirmed") {
  return {
    eventId,
    eventType,
    aggregateId: ORDER,
    aggregateType: "order",
    occurredAt: "2026-11-08T00:00:00Z",
    payload: { customerId, status: "CONFIRMED" },
  };
}

function token(subject: string): string {
  const header = Buffer.from(JSON.stringify({ alg: "HS256", typ: "JWT" })).toString("base64url");
  const payload = Buffer.from(JSON.stringify({
    sub: subject,
    iss: "http://localhost:8088/realms/bazaar",
    exp: Math.floor(Date.now() / 1000) + 120,
  })).toString("base64url");
  const signature = createHmac("sha256", SECRET).update(`${header}.${payload}`).digest("base64url");
  return `${header}.${payload}.${signature}`;
}

function connected(socket: Socket): Promise<void> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error("socket did not connect")), 5000);
    socket.on("connect", () => {
      clearTimeout(timer);
      resolve();
    });
  });
}

function disconnected(socket: Socket): Promise<void> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error("socket was not rejected")), 5000);
    socket.on("disconnect", () => {
      clearTimeout(timer);
      resolve();
    });
  });
}

async function waitFor(ready: () => boolean): Promise<void> {
  const started = Date.now();
  while (!ready()) {
    if (Date.now() - started > 5000) {
      throw new Error("timed out waiting for a notification");
    }
    await new Promise((resolve) => setTimeout(resolve, 20));
  }
}
