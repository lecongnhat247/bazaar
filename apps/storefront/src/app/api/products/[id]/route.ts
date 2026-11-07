import { proxy } from "@/lib/bff";

export const dynamic = "force-dynamic";

export async function GET(_request: Request, context: { params: Promise<{ id: string }> }) {
  const { id } = await context.params;
  return proxy(`/api/v1/products/${encodeURIComponent(id)}`);
}
