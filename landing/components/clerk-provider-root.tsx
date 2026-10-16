"use client";

import { ClerkProvider } from "@clerk/nextjs";
import type { ReactNode } from "react";
import { androClerkAppearance } from "@/lib/clerk-appearance";

export function ClerkProviderRoot({ children }: { children: ReactNode }) {
  return <ClerkProvider appearance={androClerkAppearance}>{children}</ClerkProvider>;
}
