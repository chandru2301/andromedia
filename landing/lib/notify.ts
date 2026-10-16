import { Resend } from "resend";

import { siteConfig } from "@/lib/site-content";

export type SheetRowType = "feedback" | "waitlist";

export interface NotifyPayload {
  type: SheetRowType;
  email: string;
  name: string;
  company?: string;
  role?: string;
  userId?: string;
  context?: string;
  rating?: number | string;
  message?: string;
  page?: string;
  notes?: string;
}

function resendClient(): Resend | null {
  const key = process.env.RESEND_API_KEY?.trim();
  if (!key) {
    return null;
  }
  return new Resend(key);
}

function feedbackTo(): string {
  return (
    process.env.FEEDBACK_TO_EMAIL?.trim() ||
    siteConfig.feedbackEmail ||
    "chandru.v2301@gmail.com"
  );
}

function fromAddress(): string {
  return process.env.RESEND_FROM?.trim() || "Andro <onboarding@resend.dev>";
}

export async function sendNotifyEmail(payload: NotifyPayload): Promise<void> {
  const client = resendClient();
  if (!client) {
    throw new Error("RESEND_API_KEY is not configured");
  }

  const subject =
    payload.type === "waitlist"
      ? `Andro waitlist: ${payload.name} <${payload.email}>`
      : `Andro feedback (${payload.context ?? "general"}): ${payload.name || payload.email}`;

  const lines = [
    `type: ${payload.type}`,
    `name: ${payload.name}`,
    `email: ${payload.email}`,
    payload.company ? `company: ${payload.company}` : null,
    payload.role ? `role: ${payload.role}` : null,
    payload.userId ? `userId: ${payload.userId}` : null,
    payload.context ? `context: ${payload.context}` : null,
    payload.rating != null && payload.rating !== "" ? `rating: ${payload.rating}` : null,
    payload.page ? `page: ${payload.page}` : null,
    "",
    payload.message || payload.notes || "(no message)",
  ].filter((line): line is string => line !== null);

  const { error } = await client.emails.send({
    from: fromAddress(),
    to: [feedbackTo()],
    replyTo: payload.email,
    subject,
    text: lines.join("\n"),
  });

  if (error) {
    throw new Error(error.message || "Resend send failed");
  }
}

export async function appendSheetRow(payload: NotifyPayload): Promise<void> {
  const url = process.env.FEEDBACK_SHEET_WEBHOOK_URL?.trim();
  if (!url) {
    return;
  }

  const body = {
    timestamp: new Date().toISOString(),
    type: payload.type,
    email: payload.email,
    name: payload.name,
    company: payload.company ?? "",
    role: payload.role ?? "",
    userId: payload.userId ?? "",
    context: payload.context ?? "",
    rating: payload.rating ?? "",
    message: payload.message ?? payload.notes ?? "",
    page: payload.page ?? "",
  };

  const response = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });

  if (!response.ok) {
    const text = await response.text().catch(() => "");
    throw new Error(`Sheet webhook failed (${response.status}): ${text.slice(0, 200)}`);
  }
}

export async function notifyOwner(payload: NotifyPayload): Promise<void> {
  await Promise.all([sendNotifyEmail(payload), appendSheetRow(payload)]);
}
