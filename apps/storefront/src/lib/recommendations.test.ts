import assert from "node:assert/strict";
import test from "node:test";
import { productViewPath, recommendationHomePath, relatedRecommendationPath } from "./recommendations.ts";

const lamp = "8f1c2a10-6b3e-4a1d-9c20-0a0b0c0d1001";

test("recommendation paths stay on the recommendation and catalog routes", () => {
  assert.equal(recommendationHomePath, "/api/v1/recommendations/home");
  assert.equal(relatedRecommendationPath(lamp), `/api/v1/recommendations/products/${lamp}/related`);
  assert.equal(productViewPath(lamp), `/api/v1/products/${lamp}/views`);
  assert.equal(relatedRecommendationPath("../orders"), null);
  assert.equal(productViewPath("lamp"), null);
});
