/**
 * Centralized landing page content and launch gating.
 *
 * Anything not yet true in production (billing, a public GitHub repo,
 * a booked-demo flow, a registered domain, a real testimonial) is modeled
 * here as a typed flag rather than hardcoded into JSX, so unfinished claims
 * render as disabled/"soon"/hidden states instead of dead links or invented promises.
 */

import type { BrandId } from "@/data/integration-brands";

export const siteConfig = {
  name: "Andro",
  tagline: "Your company's memory. Ask anything — get answers with sources.",
  docsUrl: "/docs",
  faqUrl: "/faq",
  trialUrl: "/app",
  githubUrl: null as string | null,
  demoBookingUrl: null as string | null,
  getStartedUrl: "/app",
  feedbackEmail: "chandru.v2301@gmail.com",
  linkedinUrl: "https://www.linkedin.com/in/chandru-v-13a822353/",
  setupGuidePath:
    "https://github.com/andromedia/andromedia/blob/main/andromedia/docs/application-setup-guide.md",
  downloadUrl: "/download",
};

/**
 * Desktop installer distribution.
 *
 * `releasesRepo` is the PUBLIC repo whose Releases hold the installers built by
 * .github/workflows/release-desktop.yml. It is deliberately separate from the
 * (private) source repo: release assets on a private repo need an authenticated
 * token, so public download links would 404.
 *
 * Until the first release is published there, the GitHub API returns 404, the
 * fetch resolves to null, and the download page renders a "builds aren't
 * published yet" state rather than dead buttons.
 */
export const desktopRelease = {
  releasesRepo: "chandruSofttwig/andro-releases" as string | null,
  signed: false,
  platforms: [
    {
      id: "windows" as const,
      label: "Windows",
      requirement: "Windows 10 or later, 64-bit",
      extensions: [".exe"],
      steps: [
        "Run the downloaded installer.",
        "Windows SmartScreen will warn that the publisher is unknown — choose More info, then Run anyway.",
        "Andro opens on the sign-in screen. Choose Sign in to start your trial.",
      ],
    },
    {
      id: "macos" as const,
      label: "macOS",
      requirement: "macOS 11 Big Sur or later",
      extensions: [".dmg"],
      steps: [
        "Open the .dmg and drag Andro into Applications.",
        "The first launch is blocked because the build is unsigned — right-click the app and choose Open, then confirm.",
        "If macOS still refuses, run: xattr -cr /Applications/andro.app",
      ],
    },
    {
      id: "linux" as const,
      label: "Linux",
      requirement: "Any x86_64 distribution with FUSE",
      extensions: [".AppImage"],
      steps: [
        "Make it executable: chmod +x andro-*.AppImage",
        "Run it: ./andro-*.AppImage",
        "No install step — the AppImage is the app.",
      ],
    },
  ],
};

export const nav = {
  links: [
    { label: "How it works", href: "/#how-it-works" },
    { label: "Sources", href: "/#sources" },
    { label: "Docs", href: siteConfig.docsUrl },
    { label: "FAQ", href: siteConfig.faqUrl },
    { label: "Download", href: siteConfig.downloadUrl },
  ],
  primaryCta: { label: "Try Andro", href: siteConfig.trialUrl },
};

export const hero = {
  badge: "Decisions never lost. Docs never silently stale.",
  headline: ["Andro is your company's memory.", "Every answer, with its source."],
  subtext:
    "Andro is a local-first search and Q&A layer over your team's tools — Slack, Notion, Jira, Linear, Git, and Zoho CRM. Ask a question in the app or from your agent in Cursor and Claude Code, and get an answer grounded in your real records. When sources disagree, it tells you which is true, and why.",
  proofStrip: {
    text: "When Slack, Notion, and your CRM disagree, Andro tells you which one is true — and why.",
    linkLabel: "See how",
    href: "#one-memory",
  },
  primaryCta: { label: "Try Andro free", href: siteConfig.trialUrl },
  secondaryCta: {
    label: "Watch demo",
    href: siteConfig.demoBookingUrl,
    enabled: Boolean(siteConfig.demoBookingUrl),
  },
  trustChips: [
    "Runs on your infrastructure",
    "Data never leaves your infra",
    "Permission-aware",
    "You choose the model",
  ],
  image: {
    src: "/images/andro.png",
    alt: "Andro investigate: grounded answer recommending CRM License Renewal for TechNova Solutions, with WHY THIS ANSWER checks and deal fields from a local Zoho index",
    width: 1600,
    height: 1000,
    caption:
      "Same question Zia failed — Andro answered with CRM License Renewal ($250k, TechNova), grounded against the local Zoho index.",
  },
};

/**
 * The core differentiator: one memory across sources, not five separate
 * indexes. `live: true` because multi-source conflict surfacing is an
 * enforced rule in the Investigate integrity prompt today (see
 * andromedia/core/.../investigate/integrity-rules.md), not a roadmap claim.
 */
export const memoryPitch = {
  live: true,
  eyebrow: "Why Andro, not five search bars",
  heading: "One memory, not five silos.",
  body: "When Slack says X and Notion says Y, Andro tells you which is true — with receipts: the message, the doc, the timestamp, and which one supersedes the other.",
  example: {
    question: "Is our pricing page still accurate?",
    answer:
      "Notion still says ₹12,000/mo. But #sales-decisions on Aug 14 agreed ₹10,500/mo for new deals — that supersedes the doc. It just hasn't been updated yet.",
    sources: ["notion: pricing-v2 — stale", "slack: #sales-decisions, Aug 14 — supersedes"],
  },
};

export const howItWorks = {
  eyebrow: "How it works",
  heading: "Three steps from scattered tools to grounded answers.",
  steps: [
    {
      step: "01",
      title: "Connect your sources",
      description: "Point Andro at the tools your team already uses.",
      sources: ["Git", "Slack", "Notion", "Jira", "Linear", "Zoho"],
    },
    {
      step: "02",
      title: "Ask anything in Cursor or Claude Code",
      description:
        "Andro runs an MCP server your agent calls directly — no copy-pasting context between tabs.",
    },
    {
      step: "03",
      title: "Get grounded answers",
      description:
        "Every answer carries its sources: the Slack thread, the Notion doc, the ticket, the commit.",
    },
  ],
};

export const trust = {
  proofHeading: "One memory, not five silos.",
  proofBody:
    "When Slack, Notion, Jira, and your CRM disagree, Andro tells you which record is true — with receipts: the message, the doc, the timestamp, and which one supersedes the other.",
  proofHref: "#one-memory",
  proofLinkLabel: "See how it works",
  heading: "Your index stays on your infrastructure.",
  points: [
    {
      title: "Runs on infrastructure you control",
      description:
        "The Lucene index, ingestion jobs, and API run as a service you deploy — Docker Compose or your own host.",
    },
    {
      title: "No Andro-hosted data plane",
      description:
        "There is no Andro cloud that stores your indexed content. Retrieval happens against your own running instance.",
    },
    {
      title: "You choose which model sees retrieved context",
      description:
        "Grounded answers are generated by the chat provider you configure (Groq or OpenRouter today). Only the retrieved snippets for a given question are sent to that provider.",
    },
  ],
  setupHref: siteConfig.setupGuidePath,
  setupLabel: "Read the setup guide",
};

export type ConnectorStatus = "available" | "soon";

export type Connector = {
  brandId: BrandId;
  name: string;
  status: ConnectorStatus;
  /** Optional depth note shown via tooltip (e.g. Zoho FDE layer). */
  note?: string;
};

export const connectors: Connector[] = [
  { brandId: "git", name: "Git / GitHub", status: "available" },
  { brandId: "slack", name: "Slack", status: "available" },
  { brandId: "notion", name: "Notion", status: "available" },
  { brandId: "jira", name: "Jira", status: "available" },
  { brandId: "linear", name: "Linear", status: "available" },
  {
    brandId: "zoho",
    name: "Zoho CRM",
    status: "available",
    note: "Includes full CRM intelligence layer — quality scoring, relationship enrichment, demo detection.",
  },
  { brandId: "datadog", name: "Datadog", status: "soon" },
  { brandId: "pagerduty", name: "PagerDuty", status: "soon" },
  { brandId: "teams", name: "Microsoft Teams", status: "soon" },
  { brandId: "confluence", name: "Confluence", status: "soon" },
  { brandId: "salesforce", name: "Salesforce", status: "soon" },
  { brandId: "hubspot", name: "HubSpot", status: "soon" },
];

export const connectorsFootnote =
  "Zoho includes a full CRM intelligence layer. Other LIVE sources are indexed for retrieval without the same enrichment depth yet.";

/**
 * Pricing is not finalized. `billingLive: false` renders a waitlist
 * block with no dollar amounts so the page never implies a checkout
 * flow that does not exist yet.
 */
export const pricing = {
  billingLive: false,
  eyebrow: "Pricing",
  heading: "Pricing coming soon — join the waitlist.",
  waitlistBody:
    "Billing launches soon. Early design partners get free access during the pilot and a direct say in the roadmap — join and we'll reply within a week.",
  waitlistCta: {
    label: "Join the waitlist",
    href: "#pricing",
  },
  plans: [
    {
      name: "Free",
      price: "₹0",
      description: "For trying Andro on your own sources.",
      features: ["2 connectors", "Grounded answers with sources", "MCP server", "Community support"],
      cta: { label: "Start free", href: siteConfig.getStartedUrl },
    },
    {
      // No per-seat number: self-hosted infra and per-seat monthly SaaS billing
      // are incompatible pricing models, and hosted-vs-self-hosted isn't decided
      // yet. Do not put a number here until that's settled — see pricing.billingLive.
      name: "Pro",
      price: "Custom — TBD",
      description: "For teams who want the full memory.",
      features: [
        "All connectors — Slack, Notion, Jira, Linear, Zoho CRM",
        "Conflict detection & supersession",
        "Permission-aware answers",
        "Full MCP tool set",
      ],
      cta: {
        label: "Join the waitlist",
        href: "#pricing",
      },
    },
  ],
};

/** Set to a real quote to show the section; null keeps it off the page. */
export const socialProof: { quote: string; attribution: string } | null = null;

export const footerCta = {
  eyebrow: "For developers",
  command: "docker compose up",
  heading: "That's the entire setup.",
  links: [
    { label: "Read docs", href: siteConfig.docsUrl },
    { label: "GitHub", href: siteConfig.githubUrl, enabled: Boolean(siteConfig.githubUrl) },
    {
      label: "Book a demo",
      href: siteConfig.demoBookingUrl,
      enabled: Boolean(siteConfig.demoBookingUrl),
    },
  ],
};
