import assert from "node:assert/strict";
import test from "node:test";
import { codeChallenge, createVerifier } from "./pkce.ts";

test("a verifier is long and url safe", () => {
  const verifier = createVerifier();
  assert.match(verifier, /^[A-Za-z0-9_-]{43,128}$/);
});

test("the challenge is not the verifier", async () => {
  const verifier = createVerifier();
  const challenge = await codeChallenge(verifier);
  assert.notEqual(challenge, verifier);
  assert.match(challenge, /^[A-Za-z0-9_-]+$/);
});
