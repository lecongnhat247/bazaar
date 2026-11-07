import { NextResponse } from "next/server";

export const dynamic = "force-dynamic";

export async function GET(request: Request) {
  const host = request.headers.get("x-forwarded-host") ?? request.headers.get("host") ?? "localhost:3000";
  const proto = request.headers.get("x-forwarded-proto") ?? "http";
  const response = NextResponse.redirect(`${proto}://${host}/`);
  response.cookies.delete("bazaar_access");
  response.cookies.delete("bazaar_refresh");
  response.cookies.delete("bazaar_pkce");
  response.cookies.delete("bazaar_state");
  response.cookies.delete("bazaar_return");
  return response;
}
