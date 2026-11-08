import { NextResponse } from "next/server";

import { fetchService } from "@/lib/bff";

export const dynamic = "force-dynamic";

export async function GET() {
  const url = process.env.NOTIFICATION_URL;
  if (!url) {
    return NextResponse.json({ items: [] });
  }
  return fetchService(`${url}/api/v1/notifications`);
}
