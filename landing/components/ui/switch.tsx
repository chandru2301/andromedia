"use client";

import type * as React from "react";
import { cn } from "@/lib/utils";

type SwitchProps = {
  checked: boolean;
  onCheckedChange?: (checked: boolean) => void;
} & Omit<React.ButtonHTMLAttributes<HTMLButtonElement>, "onChange">;

export function Switch({
  checked,
  onCheckedChange,
  className,
  disabled,
  ...props
}: SwitchProps) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      disabled={disabled}
      onClick={() => {
        if (!disabled) {
          onCheckedChange?.(!checked);
        }
      }}
      className={cn(
        "relative inline-flex h-6 w-11 shrink-0 items-center rounded-full border border-border bg-muted transition-colors disabled:cursor-not-allowed disabled:opacity-50",
        checked && "bg-foreground",
        className,
      )}
      {...props}
    >
      <span
        className={cn(
          "block size-4 translate-x-1 rounded-full bg-background transition-transform",
          checked && "translate-x-6 bg-primary-foreground",
        )}
      />
    </button>
  );
}
