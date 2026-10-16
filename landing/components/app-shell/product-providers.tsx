"use client";

import type { ReactNode } from "react";
import { AndroConfigProvider } from "@/hooks/use-andro-config";
import { TrialProvider } from "@/hooks/use-trial";
import { ThemeProvider } from "@/hooks/use-theme";
import { Toaster, useToast } from "@/components/ui/use-toast";

function ProductProvidersInner({ children }: { children: ReactNode }) {
  const { toast } = useToast();

  return (
    <ThemeProvider>
      <TrialProvider>
        <AndroConfigProvider
          onSave={() =>
            toast({
              title: "Changes saved",
              description: "Product settings were saved locally for this browser.",
            })
          }
        >
          {children}
        </AndroConfigProvider>
      </TrialProvider>
    </ThemeProvider>
  );
}

export function ProductProviders({ children }: { children: ReactNode }) {
  return (
    <Toaster>
      <ProductProvidersInner>{children}</ProductProvidersInner>
    </Toaster>
  );
}
