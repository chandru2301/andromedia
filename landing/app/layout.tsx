import type { Metadata } from "next";
import Script from "next/script";
import { Geist, IBM_Plex_Mono } from "next/font/google";
import { ClerkProviderRoot } from "@/components/clerk-provider-root";
import { ThemeProviderRoot } from "@/components/theme-provider-root";
import { TooltipProvider } from "@/components/ui/tooltip";
import { getSiteUrl } from "@/lib/site-url";
import "./globals.css";

const geistSans = Geist({
  variable: "--font-geist-sans",
  subsets: ["latin"],
});

const plexMono = IBM_Plex_Mono({
  variable: "--font-plex-mono",
  subsets: ["latin"],
  weight: ["400", "500", "600"],
});

const siteUrl = getSiteUrl();

export const metadata: Metadata = {
  metadataBase: new URL(siteUrl),
  title: {
    default: "Andro — Your AI trusts one tool. The truth lives in five.",
    template: "%s — Andro",
  },
  description:
    "Andro indexes Slack, Notion, Jira, Linear and Zoho CRM into one local memory, then grounds Cursor and Claude Code over MCP — so answers come from real records, not blind trust.",
  keywords: [
    "Andro",
    "unified company memory",
    "conflict detection",
    "MCP server",
    "Cursor MCP",
    "AI coding agent context",
    "grounded answers",
    "local-first developer tools",
    "Zoho CRM AI",
  ],
  alternates: {
    canonical: siteUrl,
  },
  openGraph: {
    title: "Andro — Your AI trusts one tool. The truth lives in five.",
    description:
      "One local memory across Slack, Notion, Jira, Linear and Zoho CRM. Ground Cursor and Claude Code with real records — not blind trust.",
    url: siteUrl,
    siteName: "Andro",
    type: "website",
    images: [
      {
        url: "/images/andro.png",
        width: 1200,
        height: 630,
        alt: "Andro investigate showing a grounded CRM License Renewal answer",
      },
    ],
  },
  twitter: {
    card: "summary_large_image",
    title: "Andro — Your AI trusts one tool. The truth lives in five.",
    description:
      "One local memory across your team's tools — grounded answers, not blind trust.",
    images: ["/images/andro.png"],
  },
  icons: {
    icon: "/icon.png",
  },
};

const themeInitScript = `(function(){try{var t=localStorage.getItem('andro-theme');var theme=(t==='light'||t==='dark')?t:'dark';var r=document.documentElement;r.setAttribute('data-theme',theme);r.classList.toggle('dark',theme==='dark');}catch(e){document.documentElement.setAttribute('data-theme','dark');document.documentElement.classList.add('dark');}})();`;

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html
      lang="en"
      className={`${geistSans.variable} ${plexMono.variable} h-full antialiased`}
      suppressHydrationWarning
    >
      <body className="min-h-full flex flex-col bg-background text-foreground">
        <Script id="theme-init" strategy="beforeInteractive">
          {themeInitScript}
        </Script>
        <ThemeProviderRoot>
          <ClerkProviderRoot>
            <TooltipProvider delayDuration={150}>{children}</TooltipProvider>
          </ClerkProviderRoot>
        </ThemeProviderRoot>
      </body>
    </html>
  );
}
