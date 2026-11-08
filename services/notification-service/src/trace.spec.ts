import { childSpanId, traceIdFromHeader } from "./trace";

describe("trace context", () => {
  it("reads the trace id from a W3C traceparent", () => {
    const header = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
    expect(traceIdFromHeader(header)).toBe("4bf92f3577b34da6a3ce929d0e0e4736");
  });

  it("ignores a header that is not a traceparent", () => {
    expect(traceIdFromHeader("not-a-trace")).toBe("");
    expect(traceIdFromHeader(undefined)).toBe("");
  });

  it("creates an 8-byte span id", () => {
    expect(childSpanId()).toMatch(/^[0-9a-f]{16}$/);
  });
});
