import { randomBytes } from "node:crypto";

const TRACEPARENT = /^[\da-f]{2}-([\da-f]{32})-([\da-f]{16})-[\da-f]{2}$/;

export function traceIdFromHeader(value: string | undefined): string {
  if (!value) {
    return "";
  }
  const match = TRACEPARENT.exec(value.trim());
  return match?.[1] ?? "";
}

export function childSpanId(): string {
  return randomBytes(8).toString("hex");
}
