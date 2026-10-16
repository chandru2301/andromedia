import type { ReactNode } from "react";
import { auth } from "@clerk/nextjs/server";
import { ProductLayoutChrome } from "@/components/app-shell/product-layout-chrome";
import { ProductProviders } from "@/components/app-shell/product-providers";

/** Local-only: skip Clerk gate so /app works as classic service=local without trial. */
const isLocalDevMode = process.env.NEXT_PUBLIC_ANDRO_DEV_MODE === "true";

export default async function ProductLayout({ children }: { children: ReactNode }) {
  if (!isLocalDevMode) {
    await auth.protect();
  }

  return (
    <ProductProviders>
      <div className="flex min-h-screen flex-col bg-background text-foreground">
        <ProductLayoutChrome>{children}</ProductLayoutChrome>
      </div>
    </ProductProviders>
  );
}
