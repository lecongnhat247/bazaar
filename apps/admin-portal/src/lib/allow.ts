const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const SUBJECT = /^[A-Za-z0-9._:-]{1,64}$/;

const COUPON = /^[A-Za-z0-9_-]{3,40}$/;

const SELLER_ACTION = new Set(["approve", "reject", "suspend", "reactivate"]);

const FINANCE_READ = new Set([
  "orders/finance/ledger",
  "orders/finance/reconciliation",
  "orders/finance/commission-rates",
  "orders/finance/settlements",
]);

const FINANCE_WRITE = new Set([
  "orders/finance/commission-rates",
  "orders/finance/settlements",
  "orders/finance/refunds",
]);

export function gatewayPath(method: string, segments: string[]): string | null {
  if (segments.length === 0 || segments.some((segment) => segment.length === 0 || segment.includes("..") || segment.includes("/"))) {
    return null;
  }
  const path = segments.join("/");
  const userId = segments.length >= 3 && segments[0] === "identity" && segments[1] === "users" && UUID.test(segments[2]);
  const productId = segments.length >= 2 && segments[0] === "products" && UUID.test(segments[1]);
  const categoryId = segments.length === 2 && segments[0] === "categories" && UUID.test(segments[1]);
  const sellerSubject = segments.length >= 2 && segments[0] === "sellers" && SUBJECT.test(segments[1]) && segments[1] !== "me";
  const campaignId = segments.length >= 3 && segments[0] === "inventory" && segments[1] === "campaigns" && UUID.test(segments[2]);
  const paymentId = segments.length === 2 && segments[0] === "payments" && UUID.test(segments[1]);
  const allowed =
    (method === "GET" && path === "identity/users")
    || (method === "GET" && userId && segments.length === 3)
    || (method === "PATCH" && userId && segments.length === 4 && segments[3] === "status")
    || (method === "GET" && path === "identity/audit")
    || (method === "GET" && path === "sellers")
    || (method === "GET" && sellerSubject && segments.length === 2)
    || (method === "POST" && sellerSubject && segments.length === 3 && SELLER_ACTION.has(segments[2]))
    || (method === "GET" && path === "products")
    || (method === "GET" && productId && segments.length === 2)
    || (method === "PATCH" && productId && segments.length === 3 && segments[2] === "status")
    || (method === "GET" && productId && segments.length === 3 && segments[2] === "moderation")
    || (method === "GET" && path === "categories")
    || (method === "POST" && path === "categories")
    || (method === "PATCH" && categoryId)
    || (method === "GET" && path === "inventory/campaigns/monitor")
    || (method === "POST" && campaignId && segments.length === 4 && (segments[3] === "schedule" || segments[3] === "cancel"))
    || (method === "GET" && path === "orders/monitor")
    || (method === "GET" && path === "orders/coupons")
    || (method === "POST" && path === "orders/coupons")
    || (method === "PATCH" && segments.length === 3 && segments[0] === "orders" && segments[1] === "coupons" && COUPON.test(segments[2]))
    || (method === "GET" && FINANCE_READ.has(path))
    || (method === "POST" && FINANCE_WRITE.has(path))
    || (method === "GET" && path === "payments/monitor")
    || (method === "GET" && paymentId);
  return allowed ? `/api/v1/${path}` : null;
}
