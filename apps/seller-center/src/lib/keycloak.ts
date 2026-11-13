import { codeChallenge, createVerifier } from "@/lib/pkce";
import { safeReturn } from "@/lib/return-path";

const CLIENT_ID = "bazaar-dev";

export type TokenSet = {
  access_token: string;
  refresh_token?: string;
  expires_in?: number;
};

export function callbackUrl(request: Request): string {
  const host = request.headers.get("x-forwarded-host") ?? request.headers.get("host") ?? "localhost:3002";
  const proto = request.headers.get("x-forwarded-proto") ?? "http";
  return `${proto}://${host}/api/auth/callback`;
}

export async function authorizationUrl(request: Request, returnTo: string | null): Promise<{
  url: string;
  verifier: string;
  state: string;
  returnTo: string;
}> {
  const verifier = createVerifier();
  const state = createVerifier();
  const url = new URL(`${publicRealm()}/protocol/openid-connect/auth`);
  url.searchParams.set("client_id", CLIENT_ID);
  url.searchParams.set("response_type", "code");
  url.searchParams.set("scope", "openid profile");
  url.searchParams.set("redirect_uri", callbackUrl(request));
  url.searchParams.set("state", state);
  url.searchParams.set("code_challenge", await codeChallenge(verifier));
  url.searchParams.set("code_challenge_method", "S256");
  url.searchParams.set("prompt", "login");
  return { url: url.toString(), verifier, state, returnTo: safeReturn(returnTo) };
}

export async function exchangeCode(code: string, verifier: string, redirectUri: string): Promise<TokenSet> {
  return tokenRequest({
    grant_type: "authorization_code",
    client_id: CLIENT_ID,
    code,
    redirect_uri: redirectUri,
    code_verifier: verifier,
  });
}

export async function refreshTokens(refreshToken: string): Promise<TokenSet | null> {
  try {
    return await tokenRequest({
      grant_type: "refresh_token",
      client_id: CLIENT_ID,
      refresh_token: refreshToken,
    });
  } catch {
    return null;
  }
}

export function displayName(accessToken: string): string | undefined {
  const payload = accessToken.split(".")[1];
  if (!payload) {
    return undefined;
  }
  try {
    const claims = JSON.parse(Buffer.from(payload, "base64url").toString("utf8")) as {
      preferred_username?: string;
      name?: string;
    };
    return claims.preferred_username || claims.name;
  } catch {
    return undefined;
  }
}

async function tokenRequest(fields: Record<string, string>): Promise<TokenSet> {
  const response = await fetch(`${internalRealm()}/protocol/openid-connect/token`, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams(fields),
    cache: "no-store",
  });
  if (!response.ok) {
    throw new Error("Keycloak rejected the token request.");
  }
  return (await response.json()) as TokenSet;
}

function publicRealm(): string {
  const base = (process.env.KEYCLOAK_PUBLIC_URL ?? "http://localhost:8088").replace(/\/$/, "");
  return `${base}/realms/bazaar`;
}

function internalRealm(): string {
  const base = (process.env.KEYCLOAK_INTERNAL_URL ?? process.env.KEYCLOAK_PUBLIC_URL ?? "http://localhost:8088").replace(/\/$/, "");
  return `${base}/realms/bazaar`;
}
