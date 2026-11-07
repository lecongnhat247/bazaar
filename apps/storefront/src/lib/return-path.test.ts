import assert from "node:assert/strict";
import test from "node:test";
import { safeReturn } from "./return-path.ts";

test("only a local path is kept", () => {
  assert.equal(safeReturn("/cart"), "/cart");
  assert.equal(safeReturn("/orders/1"), "/orders/1");
  assert.equal(safeReturn("https://evil.example/cart"), "/");
  assert.equal(safeReturn("//evil.example"), "/");
  assert.equal(safeReturn(null), "/");
});
