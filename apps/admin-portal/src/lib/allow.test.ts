import assert from "node:assert/strict";
import test from "node:test";
import { gatewayPath } from "./allow.ts";

const ID = "8f1c2a10-6b3e-4a1d-9c20-0a0b0c0d1001";

test("staff reads and admin writes stay on the portal routes", () => {
  assert.equal(gatewayPath("GET", ["identity", "users"]), "/api/v1/identity/users");
  assert.equal(gatewayPath("PATCH", ["identity", "users", ID, "status"]), `/api/v1/identity/users/${ID}/status`);
  assert.equal(gatewayPath("GET", ["identity", "audit"]), "/api/v1/identity/audit");
  assert.equal(gatewayPath("GET", ["sellers"]), "/api/v1/sellers");
  assert.equal(gatewayPath("POST", ["sellers", "seller-1", "approve"]), "/api/v1/sellers/seller-1/approve");
  assert.equal(gatewayPath("GET", ["products", ID, "moderation"]), `/api/v1/products/${ID}/moderation`);
  assert.equal(gatewayPath("POST", ["categories"]), "/api/v1/categories");
  assert.equal(gatewayPath("GET", ["inventory", "campaigns", "monitor"]), "/api/v1/inventory/campaigns/monitor");
  assert.equal(gatewayPath("POST", ["inventory", "campaigns", ID, "cancel"]), `/api/v1/inventory/campaigns/${ID}/cancel`);
  assert.equal(gatewayPath("GET", ["orders", "monitor"]), "/api/v1/orders/monitor");
  assert.equal(gatewayPath("GET", ["payments", "monitor"]), "/api/v1/payments/monitor");
  assert.equal(gatewayPath("GET", ["payments", ID]), `/api/v1/payments/${ID}`);
});

test("payment changes, checkout, and seller self-service are not proxied", () => {
  assert.equal(gatewayPath("POST", ["payments", ID, "simulate-success"]), null);
  assert.equal(gatewayPath("POST", ["payments", ID, "simulate-failure"]), null);
  assert.equal(gatewayPath("POST", ["payments", ID, "cancel"]), null);
  assert.equal(gatewayPath("POST", ["orders", "checkout"]), null);
  assert.equal(gatewayPath("POST", ["orders", ID, "cancel"]), null);
  assert.equal(gatewayPath("GET", ["orders", ID]), null);
  assert.equal(gatewayPath("GET", ["sellers", "me"]), null);
  assert.equal(gatewayPath("POST", ["sellers", "me"]), null);
  assert.equal(gatewayPath("POST", ["products"]), null);
  assert.equal(gatewayPath("GET", ["identity", "users", "not-a-uuid"]), null);
  assert.equal(gatewayPath("GET", ["sellers", "..", "approve"]), null);
});
