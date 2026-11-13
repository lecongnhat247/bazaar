import assert from "node:assert/strict";
import test from "node:test";
import { safeReturn } from "./return-path.ts";

test("only a local path is kept", () => {
  assert.equal(safeReturn("/orders"), "/orders");
  assert.equal(safeReturn("/products/1"), "/products/1");
  assert.equal(safeReturn("https://evil.example/orders"), "/");
  assert.equal(safeReturn("//evil.example"), "/");
  assert.equal(safeReturn(null), "/");
});
