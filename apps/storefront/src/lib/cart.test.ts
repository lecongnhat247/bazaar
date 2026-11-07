import assert from "node:assert/strict";
import test from "node:test";
import { addLine, cartCount, cartTotal, removeLine, setQuantity, type CartLine } from "./cart.ts";

const lamp: CartLine = {
  productId: "lamp",
  name: "Desk lamp",
  unitPrice: "25.00",
  currency: "USD",
  quantity: 1,
};

test("the same product adds quantity and stays within 100", () => {
  const once = addLine([], lamp);
  const twice = addLine(once, { ...lamp, quantity: 2 });
  assert.equal(cartCount(twice), 3);
  assert.deepEqual(cartTotal(twice), { amount: "75.00", currency: "USD" });

  const capped = addLine(twice, { ...lamp, quantity: 200 });
  assert.equal(capped[0].quantity, 100);
});

test("zero quantity removes the line", () => {
  const lines = setQuantity([lamp], "lamp", 0);
  assert.deepEqual(removeLine(lines, "lamp"), []);
  assert.equal(cartTotal([]), null);
});
