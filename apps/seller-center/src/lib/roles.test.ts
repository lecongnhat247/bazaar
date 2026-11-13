import assert from "node:assert/strict";
import test from "node:test";
import { isSeller, tokenRoles } from "./roles.ts";

function token(payload: object): string {
  return `header.${Buffer.from(JSON.stringify(payload)).toString("base64url")}.signature`;
}

test("seller role comes from the token and a customer is not a seller", () => {
  const seller = tokenRoles(token({ realm_access: { roles: ["SELLER"] } }));
  const customer = tokenRoles(token({ realm_access: { roles: ["CUSTOMER"] } }));
  assert.equal(isSeller(seller), true);
  assert.equal(isSeller(customer), false);
  assert.deepEqual(tokenRoles("not-a-token"), []);
});
