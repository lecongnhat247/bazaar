import { NextResponse } from "next/server";
import { gatewayPath } from "@/lib/allow";
import { proxy } from "@/lib/bff";

export const dynamic = "force-dynamic";

async function forward(request: Request, context: { params: Promise<{ path: string[] }> }) {
  const { path } = await context.params;
  const allowed = gatewayPath(request.method, path);
  if (!allowed) {
    return NextResponse.json({ error: "That Admin Portal request is not available." }, { status: 404 });
  }
  const incoming = new URL(request.url);
  if (incoming.search.length > 500) {
    return NextResponse.json({ error: "The query is too long." }, { status: 400 });
  }
  const body = request.method === "GET" || request.method === "HEAD" ? undefined : await request.text();
  return proxy(`${allowed}${incoming.search}`, {
    method: request.method,
    body: body && body.length > 0 ? body : undefined,
    headers: body ? { "Content-Type": request.headers.get("content-type") ?? "application/json" } : undefined,
  });
}

export const GET = forward;
export const POST = forward;
export const PUT = forward;
export const PATCH = forward;
