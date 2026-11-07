import { cookies } from "next/headers";
import { NextResponse } from "next/server";
import { cookieOptions } from "@/lib/bff";
import { displayName, refreshTokens } from "@/lib/keycloak";

export const dynamic = "force-dynamic";

export async function GET() {
  const jar = await cookies();
  let access = jar.get("bazaar_access")?.value;
  if (!access) {
    const refresh = jar.get("bazaar_refresh")?.value;
    const renewed = refresh ? await refreshTokens(refresh) : null;
    if (!renewed) {
      return NextResponse.json({ authenticated: false });
    }
    access = renewed.access_token;
    jar.set("bazaar_access", access, cookieOptions(renewed.expires_in ?? 300));
    if (renewed.refresh_token) {
      jar.set("bazaar_refresh", renewed.refresh_token, cookieOptions(60 * 30));
    }
  }
  return NextResponse.json({ authenticated: true, name: displayName(access) ?? "customer" });
}
