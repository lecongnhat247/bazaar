import { createHmac, createPublicKey, timingSafeEqual, verify, type JsonWebKey } from "node:crypto";

export class TokenVerifier {
  async subject(token: string | undefined): Promise<string | null> {
    if (!token || token.split(".").length !== 3) {
      return null;
    }
    try {
      const secret = process.env.JWT_HMAC_SECRET;
      return secret ? hmacSubject(token, secret) : await rsaSubject(token);
    } catch {
      return null;
    }
  }
}

export function bearerOrCookie(authorization: string | undefined, cookie: string | undefined, authToken?: unknown): string | undefined {
  if (typeof authToken === "string" && authToken.length > 0) {
    return authToken;
  }
  if (authorization?.startsWith("Bearer ")) {
    return authorization.slice("Bearer ".length);
  }
  const match = cookie?.match(/(?:^|;\s*)bazaar_access=([^;]+)/);
  return match ? decodeURIComponent(match[1]) : undefined;
}

let cachedKeys: { keys: JsonWebKey[]; at: number } | null = null;

async function rsaSubject(token: string): Promise<string | null> {
  const [headerPart, payloadPart, signaturePart] = token.split(".");
  const header = JSON.parse(decode(headerPart).toString("utf8")) as { alg?: string; kid?: string };
  if (header.alg !== "RS256" || !header.kid) {
    return null;
  }
  const jwk = (await jwks()).find((key) => key.kid === header.kid);
  if (!jwk) {
    return null;
  }
  const valid = verify(
    "RSA-SHA256",
    Buffer.from(`${headerPart}.${payloadPart}`),
    createPublicKey({ key: jwk, format: "jwk" }),
    decode(signaturePart),
  );
  return valid ? subjectClaim(payloadPart) : null;
}

function hmacSubject(token: string, secret: string): string | null {
  const [headerPart, payloadPart, signaturePart] = token.split(".");
  const expected = createHmac("sha256", secret).update(`${headerPart}.${payloadPart}`).digest();
  const actual = decode(signaturePart);
  if (expected.length !== actual.length || !timingSafeEqual(expected, actual)) {
    return null;
  }
  return subjectClaim(payloadPart);
}

function subjectClaim(payloadPart: string): string | null {
  const claims = JSON.parse(decode(payloadPart).toString("utf8")) as { sub?: string; iss?: string; exp?: number };
  if (!claims.sub || claims.iss !== process.env.KEYCLOAK_ISSUER) {
    return null;
  }
  if (typeof claims.exp === "number" && claims.exp * 1000 < Date.now()) {
    return null;
  }
  return claims.sub;
}

async function jwks(): Promise<JsonWebKey[]> {
  if (cachedKeys && Date.now() - cachedKeys.at < 300_000) {
    return cachedKeys.keys;
  }
  const response = await fetch(process.env.KEYCLOAK_JWKS_URI ?? "");
  if (!response.ok) {
    throw new Error("The identity keys could not be read.");
  }
  const body = await response.json() as { keys?: JsonWebKey[] };
  cachedKeys = { keys: body.keys ?? [], at: Date.now() };
  return cachedKeys.keys;
}

function decode(value: string): Buffer {
  return Buffer.from(value, "base64url");
}
