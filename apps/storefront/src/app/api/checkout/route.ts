import { proxy } from "@/lib/bff";

export const dynamic = "force-dynamic";

export async function POST(request: Request) {
  const key = request.headers.get("Idempotency-Key");
  const body = await request.text();
  return proxy("/api/v1/orders/checkout", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      ...(key ? { "Idempotency-Key": key } : {}),
    },
    body,
  });
}
