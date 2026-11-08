import { NextResponse } from "next/server";

export const dynamic = "force-dynamic";

export function GET() {
  return NextResponse.json({
    url: process.env.NOTIFICATION_PUBLIC_URL ?? "http://localhost:8086",
  });
}
