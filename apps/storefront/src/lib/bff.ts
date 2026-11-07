import { cookies } from "next/headers";
import { NextResponse } from "next/server";
import { readGatewayUrl } from "@/lib/gateway";
import { refreshTokens, type TokenSet } from "@/lib/keycloak";

export function cookieOptions(maxAge: number) {
  return {
    httpOnly: true,
    sameSite: "lax" as const,
    path: "/",
    maxAge,
    secure: false,
  };
}

export async function proxy(path: string, init: RequestInit = {}): Promise<NextResponse> {
  const gatewayUrl = readGatewayUrl();
  if (!gatewayUrl) {
    return NextResponse.json({ error: "API_GATEWAY_URL is not set." }, { status: 503 });
  }
  const jar = await cookies();
  let access = jar.get("bazaar_access")?.value;
  let response = await call(gatewayUrl, path, init, access);
  if (response.status === 401) {
    const renewed = await renew(jar.get("bazaar_refresh")?.value);
    if (renewed) {
      jar.set("bazaar_access", renewed.access_token, cookieOptions(renewed.expires_in ?? 300));
      if (renewed.refresh_token) {
        jar.set("bazaar_refresh", renewed.refresh_token, cookieOptions(60 * 30));
      }
      access = renewed.access_token;
      response = await call(gatewayUrl, path, init, access);
    }
  }
  const body = await response.text();
  return new NextResponse(body, {
    status: response.status,
    headers: { "Content-Type": response.headers.get("content-type") ?? "application/json" },
  });
}

async function renew(refresh: string | undefined): Promise<TokenSet | null> {
  if (!refresh) {
    return null;
  }
  return refreshTokens(refresh);
}

async function call(gatewayUrl: string, path: string, init: RequestInit, access: string | undefined): Promise<Response> {
  const headers = new Headers(init.headers);
  if (access) {
    headers.set("Authorization", `Bearer ${access}`);
  }
  return fetch(`${gatewayUrl}${path}`, {
    ...init,
    headers,
    cache: "no-store",
  });
}
