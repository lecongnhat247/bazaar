import { toNotice } from "./map-event";

const ORDER = "8f1c2a10-6b3e-4a1d-9c20-0a0b0c0d9001";
const EVENT = "8f1c2a10-6b3e-4a1d-9c20-0a0b0c0d9002";

describe("toNotice", () => {
  it("maps a confirmed order to the customer on the event", () => {
    const notice = toNotice({
      eventId: EVENT,
      eventType: "order.confirmed",
      aggregateId: ORDER,
      aggregateType: "order",
      occurredAt: "2026-11-08T00:00:00Z",
      payload: { customerId: "customer-a", status: "CONFIRMED" },
    });
    expect(notice).toMatchObject({
      eventId: EVENT,
      type: "ORDER_CONFIRMED",
      orderId: ORDER,
      customerId: "customer-a",
      summary: "The order is confirmed.",
    });
  });

  it("uses the payment payload order id", () => {
    const notice = toNotice({
      eventId: EVENT,
      eventType: "payment.succeeded",
      aggregateId: "8f1c2a10-6b3e-4a1d-9c20-0a0b0c0d9003",
      aggregateType: "payment",
      occurredAt: "2026-11-08T00:00:00Z",
      payload: { customerId: "customer-a", orderId: ORDER },
    });
    expect(notice?.type).toBe("PAYMENT_SUCCEEDED");
    expect(notice?.orderId).toBe(ORDER);
  });

  it("ignores events that do not name a customer", () => {
    expect(toNotice({
      eventId: EVENT,
      eventType: "order.confirmed",
      aggregateId: ORDER,
      aggregateType: "order",
      payload: {},
    })).toBeNull();
    expect(toNotice({
      eventId: EVENT,
      eventType: "order.failed",
      aggregateId: ORDER,
      aggregateType: "order",
      payload: { customerId: "customer-a" },
    })).toBeNull();
  });
});
