import http from "k6/http";
import { check, sleep } from "k6";

export const options = {
  vus: 2,
  iterations: 4,
  thresholds: {
    http_req_failed: ["rate<0.05"],
    checks: ["rate>0.95"],
  },
};

const base = __ENV.BASE_URL || "http://host.docker.internal:8080";
const keycloak = __ENV.KEYCLOAK_URL || "http://host.docker.internal:8088";
const shirt = "8f1c2a10-6b3e-4a1d-9c20-0a0b0c0d1002";

export function setup() {
  const token = http.post(
    `${keycloak}/realms/bazaar/protocol/openid-connect/token`,
    {
      client_id: "bazaar-dev",
      grant_type: "password",
      username: "customer",
      password: "customer_local",
    },
    { headers: { "Content-Type": "application/x-www-form-urlencoded" } },
  );
  check(token, { "token is 200": (result) => result.status === 200 });
  return { access: token.json("access_token") };
}

export default function (data) {
  const key = `m6k6${__VU}i${__ITER}fail`;
  const response = http.post(
    `${base}/api/v1/orders/checkout`,
    JSON.stringify({ items: [{ productId: shirt, quantity: 1 }], outcome: "FAILURE" }),
    {
      headers: {
        Authorization: `Bearer ${data.access}`,
        "Content-Type": "application/json",
        "Idempotency-Key": key,
      },
    },
  );
  check(response, { "checkout accepted": (result) => result.status === 201 });
  sleep(0.2);
}
