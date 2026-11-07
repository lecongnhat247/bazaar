import { proxy } from "@/lib/bff";

export const dynamic = "force-dynamic";

export async function GET(request: Request) {
  const url = new URL(request.url);
  const query = url.searchParams.toString();
  return proxy(`/api/v1/products${query ? `?${query}` : ""}`);
}
