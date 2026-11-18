import { NextResponse } from "next/server";
import { proxy } from "@/lib/bff";
import { relatedRecommendationPath } from "@/lib/recommendations";

export const dynamic = "force-dynamic";

export async function GET(_request: Request, context: { params: Promise<{ id: string }> }) {
  const { id } = await context.params;
  const path = relatedRecommendationPath(id);
  if (!path) {
    return NextResponse.json({ error: "productId is invalid" }, { status: 400 });
  }
  return proxy(path);
}
