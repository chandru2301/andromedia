import { auth, clerkClient } from "@clerk/nextjs/server";
import { NextResponse } from "next/server";

export const runtime = "nodejs";

/**
 * Mint a short-lived Clerk sign-in token for transferring a web session into the
 * Electron desktop app via andro://auth/callback?ticket=…
 */
export async function POST() {
  const { userId } = await auth();
  if (!userId) {
    return NextResponse.json({ error: "Sign in required" }, { status: 401 });
  }

  try {
    const client = await clerkClient();
    const token = await client.signInTokens.createSignInToken({
      userId,
      expiresInSeconds: 300,
    });

    const ticket = token.token;
    if (!ticket) {
      return NextResponse.json(
        { error: "Clerk did not return a sign-in ticket" },
        { status: 502 },
      );
    }

    return NextResponse.json({ ticket });
  } catch (caught) {
    const message =
      caught instanceof Error ? caught.message : "Failed to create desktop auth ticket";
    return NextResponse.json({ error: message }, { status: 500 });
  }
}
