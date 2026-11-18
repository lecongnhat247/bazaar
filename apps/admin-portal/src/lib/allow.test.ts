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
  assert.equal(gatewayPath("GET", ["orders", "coupons"]), "/api/v1/orders/coupons");
  assert.equal(gatewayPath("POST", ["orders", "coupons"]), "/api/v1/orders/coupons");
  assert.equal(gatewayPath("PATCH", ["orders", "coupons", "SPRING-10"]), "/api/v1/orders/coupons/SPRING-10");
  assert.equal(gatewayPath("GET", ["orders", "finance", "ledger"]), "/api/v1/orders/finance/ledger");
  assert.equal(gatewayPath("GET", ["orders", "finance", "reconciliation"]), "/api/v1/orders/finance/reconciliation");
  assert.equal(gatewayPath("GET", ["orders", "finance", "commission-rates"]), "/api/v1/orders/finance/commission-rates");
  assert.equal(gatewayPath("POST", ["orders", "finance", "commission-rates"]), "/api/v1/orders/finance/commission-rates");
  assert.equal(gatewayPath("GET", ["orders", "finance", "settlements"]), "/api/v1/orders/finance/settlements");
  assert.equal(gatewayPath("POST", ["orders", "finance", "settlements"]), "/api/v1/orders/finance/settlements");
  assert.equal(gatewayPath("POST", ["orders", "finance", "refunds"]), "/api/v1/orders/finance/refunds");
  assert.equal(gatewayPath("GET", ["payments", "monitor"]), "/api/v1/payments/monitor");
  assert.equal(gatewayPath("GET", ["payments", ID]), `/api/v1/payments/${ID}`);
  assert.equal(gatewayPath("GET", ["analytics", "platform", "overview"]), "/api/v1/analytics/platform/overview");
  assert.equal(gatewayPath("GET", ["analytics", "platform", "sales"]), "/api/v1/analytics/platform/sales");
  assert.equal(gatewayPath("GET", ["analytics", "flash-sales", "performance"]), "/api/v1/analytics/flash-sales/performance");
  assert.equal(gatewayPath("GET", ["analytics", "sellers", "seller-1", "sales"]), "/api/v1/analytics/sellers/seller-1/sales");
  assert.equal(gatewayPath("POST", ["analytics", "rebuild"]), "/api/v1/analytics/rebuild");
});

test("payment changes, checkout, and seller self-service are not proxied", () => {
  assert.equal(gatewayPath("POST", ["payments", ID, "simulate-success"]), null);
  assert.equal(gatewayPath("POST", ["payments", ID, "simulate-failure"]), null);
  assert.equal(gatewayPath("POST", ["payments", ID, "cancel"]), null);
  assert.equal(gatewayPath("POST", ["orders", "checkout"]), null);
  assert.equal(gatewayPath("POST", ["orders", ID, "cancel"]), null);
  assert.equal(gatewayPath("GET", ["orders", ID]), null);
  assert.equal(gatewayPath("PATCH", ["orders", ID, "fulfillment"]), null);
  assert.equal(gatewayPath("GET", ["orders", "finance", "me"]), null);
  assert.equal(gatewayPath("DELETE", ["orders", "coupons", "SPRING-10"]), null);
  assert.equal(gatewayPath("PATCH", ["orders", "coupons", "bad code"]), null);
  assert.equal(gatewayPath("PUT", ["orders", "finance", "refunds"]), null);
  assert.equal(gatewayPath("GET", ["orders", "finance", "refunds"]), null);
  assert.equal(gatewayPath("POST", ["orders", "finance", "ledger"]), null);
  assert.equal(gatewayPath("GET", ["sellers", "me"]), null);
  assert.equal(gatewayPath("POST", ["sellers", "me"]), null);
  assert.equal(gatewayPath("POST", ["products"]), null);
  assert.equal(gatewayPath("GET", ["identity", "users", "not-a-uuid"]), null);
  assert.equal(gatewayPath("GET", ["sellers", "..", "approve"]), null);
  assert.equal(gatewayPath("GET", ["analytics", "sellers", "me", "sales"]), null);
  assert.equal(gatewayPath("POST", ["analytics", "platform", "overview"]), null);
  assert.equal(gatewayPath("GET", ["analytics", "sellers", "seller-1", "sales", "extra"]), null);
});
