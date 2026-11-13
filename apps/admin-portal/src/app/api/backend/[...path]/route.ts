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
  const headers: Record<string, string> = {};
  if (body) {
    headers["Content-Type"] = request.headers.get("content-type") ?? "application/json";
  }
  // Mock settlement runs are idempotent by key. Only a well-formed key is forwarded.
  const key = request.headers.get("idempotency-key");
  if (key && /^[A-Za-z0-9_-]{8,64}$/.test(key)) {
    headers["Idempotency-Key"] = key;
  }
  return proxy(`${allowed}${incoming.search}`, {
    method: request.method,
    body: body && body.length > 0 ? body : undefined,
    headers: Object.keys(headers).length > 0 ? headers : undefined,
  });
}

export const GET = forward;
export const POST = forward;
export const PUT = forward;
export const PATCH = forward;
