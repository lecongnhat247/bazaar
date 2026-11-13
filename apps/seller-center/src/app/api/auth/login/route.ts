import { NextResponse } from "next/server";
import { cookieOptions } from "@/lib/bff";
import { authorizationUrl } from "@/lib/keycloak";

export const dynamic = "force-dynamic";

export async function GET(request: Request) {
  const returnTo = new URL(request.url).searchParams.get("return");
  const start = await authorizationUrl(request, returnTo);
  const response = NextResponse.redirect(start.url);
  response.cookies.set("bazaar_pkce", start.verifier, cookieOptions(300));
  response.cookies.set("bazaar_state", start.state, cookieOptions(300));
  response.cookies.set("bazaar_return", start.returnTo, cookieOptions(300));
  return response;
}
