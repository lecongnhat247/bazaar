import { timingSafeEqual } from "node:crypto";
import { cookies } from "next/headers";
import { NextResponse } from "next/server";
import { cookieOptions } from "@/lib/bff";
import { callbackUrl, exchangeCode } from "@/lib/keycloak";
import { safeReturn } from "@/lib/return-path";

export const dynamic = "force-dynamic";

export async function GET(request: Request) {
  const url = new URL(request.url);
  const code = url.searchParams.get("code");
  const state = url.searchParams.get("state");
  const jar = await cookies();
  const expected = jar.get("bazaar_state")?.value;
  const verifier = jar.get("bazaar_pkce")?.value;
  const returnTo = safeReturn(jar.get("bazaar_return")?.value);
  if (!code || !state || !expected || !verifier || !same(state, expected)) {
    return NextResponse.redirect(new URL("/login?error=auth", request.url));
  }
  try {
    const tokens = await exchangeCode(code, verifier, callbackUrl(request));
    const response = NextResponse.redirect(new URL(returnTo, publicOrigin(request)));
    response.cookies.set("bazaar_access", tokens.access_token, cookieOptions(tokens.expires_in ?? 300));
    if (tokens.refresh_token) {
      response.cookies.set("bazaar_refresh", tokens.refresh_token, cookieOptions(60 * 30));
    }
    response.cookies.delete("bazaar_pkce");
    response.cookies.delete("bazaar_state");
    response.cookies.delete("bazaar_return");
    return response;
  } catch {
    return NextResponse.redirect(new URL("/login?error=auth", request.url));
  }
}

function publicOrigin(request: Request): string {
  const host = request.headers.get("x-forwarded-host") ?? request.headers.get("host") ?? "localhost:3002";
  const proto = request.headers.get("x-forwarded-proto") ?? "http";
  return `${proto}://${host}`;
}

function same(left: string, right: string): boolean {
  const a = Buffer.from(left);
  const b = Buffer.from(right);
  return a.length === b.length && timingSafeEqual(a, b);
}
