const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export function gatewayPath(method: string, segments: string[]): string | null {
  if (segments.length === 0 || segments.some((segment) => segment.length === 0 || segment.includes("..") || segment.includes("/"))) {
    return null;
  }
  const path = segments.join("/");
  const productId = segments.length === 2 && segments[0] === "products" && UUID.test(segments[1]);
  const stockId = segments.length === 3 && segments[0] === "inventory" && segments[1] === "products" && UUID.test(segments[2]);
  const campaignAction = segments.length === 4
    && segments[0] === "inventory"
    && segments[1] === "campaigns"
    && UUID.test(segments[2])
    && (segments[3] === "schedule" || segments[3] === "cancel");
  const allowed =
    (method === "GET" && path === "sellers/me")
    || (method === "POST" && path === "sellers/me")
    || (method === "PATCH" && path === "sellers/me/store")
    || (method === "GET" && path === "products")
    || (method === "POST" && path === "products")
    || (method === "GET" && productId)
    || (method === "PUT" && productId)
    || (method === "PATCH" && segments.length === 3 && segments[0] === "products" && UUID.test(segments[1]) && segments[2] === "status")
    || (method === "GET" && path === "categories")
    || ((method === "GET" || method === "PUT") && stockId)
    || (method === "GET" && path === "orders/sales")
    || (method === "GET" && path === "orders/finance/me")
    || (method === "PATCH" && segments.length === 3 && segments[0] === "orders" && UUID.test(segments[1]) && segments[2] === "fulfillment")
    || (method === "GET" && path === "inventory/campaigns")
    || (method === "POST" && path === "inventory/campaigns")
    || (method === "POST" && campaignAction);
  return allowed ? `/api/v1/${path}` : null;
}
