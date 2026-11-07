import { proxy } from "@/lib/bff";

export const dynamic = "force-dynamic";

export async function GET() {
  return proxy("/api/v1/orders");
}
