import assert from "node:assert/strict";
import test from "node:test";
import { gatewayPath } from "./allow.ts";

const PRODUCT = "8f1c2a10-6b3e-4a1d-9c20-0a0b0c0d1001";

test("seller reads and writes stay on the seller routes", () => {
  assert.equal(gatewayPath("GET", ["sellers", "me"]), "/api/v1/sellers/me");
  assert.equal(gatewayPath("POST", ["sellers", "me"]), "/api/v1/sellers/me");
  assert.equal(gatewayPath("PATCH", ["sellers", "me", "store"]), "/api/v1/sellers/me/store");
  assert.equal(gatewayPath("GET", ["orders", "sales"]), "/api/v1/orders/sales");
  assert.equal(gatewayPath("GET", ["products", PRODUCT]), `/api/v1/products/${PRODUCT}`);
  assert.equal(gatewayPath("PUT", ["inventory", "products", PRODUCT]), `/api/v1/inventory/products/${PRODUCT}`);
  assert.equal(gatewayPath("POST", ["inventory", "campaigns", PRODUCT, "cancel"]), `/api/v1/inventory/campaigns/${PRODUCT}/cancel`);
});

test("another seller, a customer order, and admin moderation are not proxied", () => {
  assert.equal(gatewayPath("GET", ["orders"]), null);
  assert.equal(gatewayPath("GET", ["orders", PRODUCT]), null);
  assert.equal(gatewayPath("POST", ["orders", "checkout"]), null);
  assert.equal(gatewayPath("POST", ["sellers", "seller-2", "approve"]), null);
  assert.equal(gatewayPath("GET", ["payments", PRODUCT]), null);
  assert.equal(gatewayPath("GET", ["products", "not-a-uuid"]), null);
  assert.equal(gatewayPath("POST", ["inventory", "campaigns", PRODUCT, "reconcile"]), null);
  assert.equal(gatewayPath("GET", ["sellers", "..", "me"]), null);
});
