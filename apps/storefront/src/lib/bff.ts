import { cookies, headers } from "next/headers";
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
  return fetchService(`${gatewayUrl}${path}`, init);
}

export async function fetchService(url: string, init: RequestInit = {}): Promise<NextResponse> {
  const jar = await cookies();
  let access = jar.get("bazaar_access")?.value;
  let response = await call(url, init, access);
  if (response.status === 401) {
    const renewed = await renew(jar.get("bazaar_refresh")?.value);
    if (renewed) {
      jar.set("bazaar_access", renewed.access_token, cookieOptions(renewed.expires_in ?? 300));
      if (renewed.refresh_token) {
        jar.set("bazaar_refresh", renewed.refresh_token, cookieOptions(60 * 30));
      }
      access = renewed.access_token;
      response = await call(url, init, access);
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

async function call(url: string, init: RequestInit, access: string | undefined): Promise<Response> {
  const outbound = new Headers(init.headers);
  if (access) {
    outbound.set("Authorization", `Bearer ${access}`);
  }
  const incoming = await headers();
  const traceparent = incoming.get("traceparent");
  if (traceparent && /^[\da-f]{2}-[\da-f]{32}-[\da-f]{16}-[\da-f]{2}$/.test(traceparent)) {
    outbound.set("traceparent", traceparent);
  }
  const correlationId = incoming.get("x-correlation-id");
  if (correlationId && /^[A-Za-z0-9._:-]{8,80}$/.test(correlationId)) {
    outbound.set("X-Correlation-Id", correlationId);
  } else if (!outbound.has("X-Correlation-Id")) {
    outbound.set("X-Correlation-Id", crypto.randomUUID());
  }
  return fetch(url, {
    ...init,
    headers: outbound,
    cache: "no-store",
  });
}
