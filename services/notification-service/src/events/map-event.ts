const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

const TYPES = {
  "order.pending-inventory": "ORDER_CREATED",
  "payment.succeeded": "PAYMENT_SUCCEEDED",
  "order.payment-failed": "PAYMENT_FAILED",
  "order.confirmed": "ORDER_CONFIRMED",
  "order.cancelled": "ORDER_CANCELLED",
} as const;

const SUMMARIES: Record<NoticeType, string> = {
  ORDER_CREATED: "The order was saved.",
  PAYMENT_SUCCEEDED: "The payment succeeded.",
  PAYMENT_FAILED: "The payment did not succeed.",
  ORDER_CONFIRMED: "The order is confirmed.",
  ORDER_CANCELLED: "The order was cancelled.",
};

export type NoticeType = (typeof TYPES)[keyof typeof TYPES];

export type Notice = {
  eventId: string;
  type: NoticeType;
  orderId: string;
  customerId: string;
  summary: string;
  createdAt: string;
};

type Envelope = {
  eventId?: unknown;
  eventType?: unknown;
  aggregateId?: unknown;
  aggregateType?: unknown;
  occurredAt?: unknown;
  payload?: { customerId?: unknown; orderId?: unknown };
};

export function toNotice(raw: unknown): Notice | null {
  if (!raw || typeof raw !== "object") {
    return null;
  }
  const event = raw as Envelope;
  const type = typeof event.eventType === "string" ? TYPES[event.eventType as keyof typeof TYPES] : undefined;
  const eventId = typeof event.eventId === "string" ? event.eventId : "";
  const customerId = typeof event.payload?.customerId === "string" ? event.payload.customerId : "";
  const orderFromPayload = typeof event.payload?.orderId === "string" ? event.payload.orderId : "";
  const orderFromAggregate = event.aggregateType === "order" && typeof event.aggregateId === "string" ? event.aggregateId : "";
  const orderId = orderFromPayload || orderFromAggregate;
  if (!type || !UUID.test(eventId) || !customerId || !UUID.test(orderId)) {
    return null;
  }
  const createdAt = typeof event.occurredAt === "string" && !Number.isNaN(Date.parse(event.occurredAt))
    ? event.occurredAt
    : new Date().toISOString();
  return {
    eventId,
    type,
    orderId,
    customerId,
    summary: SUMMARIES[type],
    createdAt,
  };
}
