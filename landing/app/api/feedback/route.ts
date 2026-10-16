import { auth, currentUser } from "@clerk/nextjs/server";
import { NextResponse } from "next/server";

import { notifyOwner } from "@/lib/notify";

export const runtime = "nodejs";

type FeedbackContext = "trial_limit" | "docs" | "general";

interface FeedbackBody {
  rating?: number | string;
  message?: string;
  context?: FeedbackContext;
  page?: string;
}

export async function POST(request: Request) {
  const { userId } = await auth();
  if (!userId) {
    return NextResponse.json({ error: "Sign in required" }, { status: 401 });
  }

  let body: FeedbackBody;
  try {
    body = (await request.json()) as FeedbackBody;
  } catch {
    return NextResponse.json({ error: "Invalid JSON body" }, { status: 400 });
  }

  const message = typeof body.message === "string" ? body.message.trim() : "";
  if (!message || message.length < 3) {
    return NextResponse.json({ error: "message is required" }, { status: 400 });
  }
  if (message.length > 4000) {
    return NextResponse.json({ error: "message is too long" }, { status: 400 });
  }

  const context: FeedbackContext =
    body.context === "trial_limit" || body.context === "docs" || body.context === "general"
      ? body.context
      : "general";

  const user = await currentUser();
  const email =
    user?.primaryEmailAddress?.emailAddress ||
    user?.emailAddresses?.[0]?.emailAddress ||
    "";
  if (!email) {
    return NextResponse.json({ error: "Account email required" }, { status: 400 });
  }
  const name =
    [user?.firstName, user?.lastName].filter(Boolean).join(" ").trim() ||
    user?.username ||
    email;

  try {
    await notifyOwner({
      type: "feedback",
      email,
      name,
      userId,
      context,
      rating: body.rating,
      message,
      page: typeof body.page === "string" ? body.page.slice(0, 200) : undefined,
    });
    return NextResponse.json({ ok: true });
  } catch (error) {
    const detail = error instanceof Error ? error.message : "Failed to send feedback";
    return NextResponse.json({ error: detail }, { status: 502 });
  }
}
