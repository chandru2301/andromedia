"use client";

import type { ReactNode } from "react";

import { ThemeProvider } from "@/hooks/use-theme";

export function ThemeProviderRoot({ children }: { children: ReactNode }) {
  return <ThemeProvider>{children}</ThemeProvider>;
}
