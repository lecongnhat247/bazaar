import http from "k6/http";
import { check, sleep } from "k6";

export const options = {
  vus: 2,
  duration: "15s",
  thresholds: {
    http_req_failed: ["rate<0.05"],
    http_req_duration: ["p(95)<1000"],
  },
};

const base = __ENV.BASE_URL || "http://host.docker.internal:8080";

export default function () {
  const response = http.get(`${base}/api/v1/products?page=0&size=20`);
  check(response, { "catalog list is 200": (result) => result.status === 200 });
  sleep(0.3);
}
