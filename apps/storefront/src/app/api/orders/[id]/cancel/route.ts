import { proxy } from "@/lib/bff";

export const dynamic = "force-dynamic";

export async function POST(_request: Request, context: { params: Promise<{ id: string }> }) {
  const { id } = await context.params;
  return proxy(`/api/v1/orders/${encodeURIComponent(id)}/cancel`, { method: "POST" });
}
