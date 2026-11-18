import { proxy } from "@/lib/bff";
import { recommendationHomePath } from "@/lib/recommendations";

export const dynamic = "force-dynamic";

export async function GET() {
  return proxy(recommendationHomePath);
}
