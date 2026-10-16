import { auth, currentUser } from "@clerk/nextjs/server";
import { NextResponse } from "next/server";

import { notifyOwner } from "@/lib/notify";

export const runtime = "nodejs";

const RATE_WINDOW_MS = 60_000;
const RATE_MAX = 5;
const ipHits = new Map<string, { count: number; resetAt: number }>();

interface WaitlistBody {
  name?: string;
  email?: string;
  company?: string;
  role?: string;
  notes?: string;
  website?: string; // honeypot
}

function clientIp(request: Request): string {
  const forwarded = request.headers.get("x-forwarded-for");
  if (forwarded) {
    return forwarded.split(",")[0]?.trim() || "unknown";
  }
  return request.headers.get("x-real-ip")?.trim() || "unknown";
}

function rateLimited(ip: string): boolean {
  const now = Date.now();
  const entry = ipHits.get(ip);
  if (!entry || entry.resetAt <= now) {
    ipHits.set(ip, { count: 1, resetAt: now + RATE_WINDOW_MS });
    return false;
  }
  entry.count += 1;
  return entry.count > RATE_MAX;
}

function isValidEmail(email: string): boolean {
  return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email);
}

export async function POST(request: Request) {
  const ip = clientIp(request);
  if (rateLimited(ip)) {
    return NextResponse.json({ error: "Too many requests" }, { status: 429 });
  }

  let body: WaitlistBody;
  try {
    body = (await request.json()) as WaitlistBody;
  } catch {
    return NextResponse.json({ error: "Invalid JSON body" }, { status: 400 });
  }

  // Honeypot: bots fill hidden fields
  if (typeof body.website === "string" && body.website.trim()) {
    return NextResponse.json({ ok: true });
  }

  const name = typeof body.name === "string" ? body.name.trim() : "";
  const email = typeof body.email === "string" ? body.email.trim().toLowerCase() : "";
  const company = typeof body.company === "string" ? body.company.trim() : "";
  const role = typeof body.role === "string" ? body.role.trim() : "";
  const notes = typeof body.notes === "string" ? body.notes.trim() : "";

  if (!name || name.length < 2) {
    return NextResponse.json({ error: "name is required" }, { status: 400 });
  }
  if (!email || !isValidEmail(email)) {
    return NextResponse.json({ error: "valid email is required" }, { status: 400 });
  }
  if (notes.length > 2000) {
    return NextResponse.json({ error: "notes are too long" }, { status: 400 });
  }

  let userId: string | undefined;
  try {
    const session = await auth();
    userId = session.userId ?? undefined;
    if (userId) {
      const user = await currentUser();
      // Prefer Clerk email when signed in, but keep form email as primary contact
      void user;
    }
  } catch {
    // Public waitlist — Clerk optional
  }

  try {
    await notifyOwner({
      type: "waitlist",
      email,
      name,
      company: company || undefined,
      role: role || undefined,
      notes: notes || undefined,
      userId,
      context: "waitlist",
    });
    return NextResponse.json({ ok: true });
  } catch (error) {
    const detail = error instanceof Error ? error.message : "Failed to join waitlist";
    return NextResponse.json({ error: detail }, { status: 502 });
  }
}
