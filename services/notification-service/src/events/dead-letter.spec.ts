import { backoffMs, failureAction } from "./dead-letter";

describe("notification failures", () => {
  it("retries a transient error and then stops", () => {
    expect(failureAction(1, false)).toBe("retry");
    expect(failureAction(2, false)).toBe("retry");
    expect(failureAction(3, false)).toBe("dead");
  });

  it("does not retry a permanent error", () => {
    expect(failureAction(1, true)).toBe("dead");
  });

  it("waits longer after each attempt and stays bounded", () => {
    expect(backoffMs(1)).toBe(200);
    expect(backoffMs(2)).toBe(400);
    expect(backoffMs(6)).toBe(2000);
  });
});
