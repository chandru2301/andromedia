/**
 * Clerk UI themed to Andro (near-black / warm light, mint accent, Geist).
 * Used by /sign-in, /sign-up, and ClerkProvider defaults.
 */
export const androClerkAppearance = {
  variables: {
    colorPrimary: "#3dd68c",
    colorDanger: "#ef4444",
    colorSuccess: "#3dd68c",
    colorWarning: "#f59e0b",
    colorTextOnPrimaryBackground: "#0a0a0a",
    borderRadius: "0.5rem",
    fontFamily: "var(--font-geist-sans), ui-sans-serif, system-ui, sans-serif",
    fontFamilyButtons: "var(--font-geist-sans), ui-sans-serif, system-ui, sans-serif",
    fontSize: "0.9375rem",
  },
  elements: {
    rootBox: "mx-auto w-full",
    cardBox: "w-full shadow-none",
    card: "w-full border-0 bg-transparent p-0 shadow-none",
    headerTitle: "text-xl font-semibold tracking-tight text-foreground",
    headerSubtitle: "text-sm text-muted-foreground",
    socialButtonsBlockButton:
      "border border-border bg-secondary/50 text-foreground hover:bg-secondary",
    socialButtonsBlockButtonText: "text-sm font-medium text-foreground",
    dividerLine: "bg-border",
    dividerText: "font-mono text-[11px] uppercase tracking-wider text-muted-foreground",
    formFieldLabel: "text-xs font-medium text-muted-foreground",
    formFieldInput:
      "rounded-md border border-border bg-background text-foreground placeholder:text-muted-foreground focus:border-mint focus:ring-1 focus:ring-mint",
    formButtonPrimary:
      "rounded-md bg-mint text-sm font-medium text-[#0a0a0a] hover:brightness-95",
    footerActionLink: "text-mint hover:text-mint/90",
    identityPreviewEditButton: "text-mint",
    formFieldAction: "text-mint hover:text-mint/90",
    otpCodeFieldInput: "border-border bg-background text-foreground",
    alternativeMethodsBlockButton:
      "border border-border bg-secondary/40 text-foreground hover:bg-secondary",
    alertText: "text-sm",
    footer: "bg-transparent",
    footerAction: "text-sm text-muted-foreground",
  },
};
