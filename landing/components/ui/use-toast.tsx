"use client";

import * as React from "react";
import { cn } from "@/lib/utils";

type ToastMessage = {
  id: string;
  title: string;
  description?: string;
};

type ToastContextValue = {
  toast: (input: { title: string; description?: string }) => void;
};

const ToastContext = React.createContext<ToastContextValue | null>(null);

export function useToast() {
  const ctx = React.useContext(ToastContext);
  if (!ctx) {
    throw new Error("useToast must be used within Toaster");
  }
  return ctx;
}

export function Toaster({ children }: { children: React.ReactNode }) {
  const [messages, setMessages] = React.useState<ToastMessage[]>([]);

  const toast = React.useCallback((input: { title: string; description?: string }) => {
    const id = crypto.randomUUID();
    setMessages((prev) => [...prev, { id, ...input }]);
    window.setTimeout(() => {
      setMessages((prev) => prev.filter((message) => message.id !== id));
    }, 3200);
  }, []);

  return (
    <ToastContext.Provider value={{ toast }}>
      {children}
      <div className="pointer-events-none fixed bottom-4 left-1/2 z-[100] flex w-full max-w-sm -translate-x-1/2 flex-col gap-2 px-4">
        {messages.map((message) => (
          <div
            key={message.id}
            className={cn(
              "pointer-events-auto rounded-xl border border-border bg-card px-4 py-3 text-sm text-foreground shadow-[0_0_0_1px_rgba(255,255,255,0.02),0_20px_50px_-30px_rgba(0,0,0,0.9)]",
            )}
          >
            <div className="font-medium">{message.title}</div>
            {message.description ? (
              <div className="mt-1 text-xs text-muted-foreground">{message.description}</div>
            ) : null}
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}
