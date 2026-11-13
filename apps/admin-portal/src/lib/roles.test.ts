import assert from "node:assert/strict";
import test from "node:test";
import { isAdmin, isStaff, tokenRoles } from "./roles.ts";

function token(payload: object): string {
  return `header.${Buffer.from(JSON.stringify(payload)).toString("base64url")}.signature`;
}

test("admin can write and an operator can only read", () => {
  const admin = tokenRoles(token({ realm_access: { roles: ["ADMIN"] } }));
  const operator = tokenRoles(token({ realm_access: { roles: ["OPERATOR"] } }));
  const seller = tokenRoles(token({ realm_access: { roles: ["SELLER"] } }));
  assert.equal(isStaff(admin), true);
  assert.equal(isAdmin(admin), true);
  assert.equal(isStaff(operator), true);
  assert.equal(isAdmin(operator), false);
  assert.equal(isStaff(seller), false);
  assert.deepEqual(tokenRoles("not-a-token"), []);
});
