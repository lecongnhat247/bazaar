import assert from "node:assert/strict";
import test from "node:test";

import { mergeNotices, type LiveNotice } from "./notifications.ts";

const first: LiveNotice = {
  eventId: "event-1",
  type: "ORDER_CREATED",
  orderId: "order-1",
  summary: "The order was saved.",
  createdAt: "2026-11-08T00:00:00Z",
};

test("mergeNotices keeps one copy of an event and appends a new one", () => {
  const again = mergeNotices([first], [first, { ...first, eventId: "event-2", type: "ORDER_CONFIRMED", summary: "The order is confirmed." }]);
  assert.deepEqual(again.map((item) => item.eventId), ["event-1", "event-2"]);
});
