import { NextResponse } from "next/server";
import { checkGateway } from "@/lib/gateway";

export const dynamic = "force-dynamic";

export async function GET() {
  const result = await checkGateway();
  const status = result.state === "up" ? 200 : 503;
  return NextResponse.json(result, { status });
}
