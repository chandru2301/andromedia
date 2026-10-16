import { siteConfig } from "@/lib/site-content";

export type DocsScreenshotSlot = {
  src: string;
  alt: string;
  width: number;
  height: number;
  /** When true, render a labeled placeholder instead of the image. */
  placeholder: boolean;
  placeholderLabel: string;
};

export const docsMeta = {
  title: "Docs",
  description:
    "Get started with Andro in under 10 minutes — connect your team's tools (Git, Slack, Notion, Jira, Linear, Zoho CRM), ask grounded questions, and read evidence-backed answers.",
};

export const docsQuickStart = {
  heading: "Get started in under 10 minutes",
  steps: [
    `Visit ${siteConfig.trialUrl}`,
    "Connect your sources (Git, Slack, Notion, Jira, Linear, or Zoho CRM — read-only, a couple minutes each)",
    "Wait for indexing (~1–2 minutes for most orgs)",
    "Ask your first question",
  ],
  cta: { label: "Start now", href: siteConfig.trialUrl },
};

export const docsConnectingZoho = {
  eyebrow: "Setup",
  heading: "Connecting a source",
  intro:
    "Every connector follows the same flow: Sources → Configure → Test connection → Index. Below is Zoho CRM end to end — it has the deepest setup (an OAuth self client) — but Git, Slack, Notion, Jira, and Linear all use the same Configure/Test/Index steps with a simpler token or app-install step in place of Zoho's grant token exchange. Andro only ever needs read-only access.",
  steps: [
    {
      title: "Create a Self Client",
      body: 'Go to api-console.zoho.com. Click "Add Client" → "Self Client" → "CREATE NOW".',
      image: {
        src: "/images/zoho_self_client.png",
        alt: "Zoho API Console: Choose a Client Type with Self Client highlighted",
        width: 1920,
        height: 1080,
        placeholder: false,
        placeholderLabel: "Screenshot: Zoho Add Client → Self Client",
      } satisfies DocsScreenshotSlot,
    },
    {
      title: "Generate a grant token",
      body: "On the Generate Code tab, enter the scope below, set expiry (e.g. 10 minutes), then click CREATE.",
      code: "ZohoCRM.modules.ALL,ZohoCRM.settings.ALL",
      image: {
        src: "/images/zoho_scope.png",
        alt: "Zoho Self Client Generate Code tab with CRM scopes filled in",
        width: 1920,
        height: 1080,
        placeholder: false,
        placeholderLabel: "Screenshot: Zoho grant token scopes",
      } satisfies DocsScreenshotSlot,
    },
    {
      title: "Paste into Andro",
      body: "Open Sources → Zoho → Configure. Choose your region, paste Client ID, Client secret, and the one-time grant token, then exchange the grant token.",
      image: {
        src: "/images/zoho_configure.png",
        alt: "Andro Zoho CRM configure modal with region, client ID, secret, and grant token fields",
        width: 1920,
        height: 1080,
        placeholder: false,
        placeholderLabel: "Screenshot: Andro Zoho config modal",
      } satisfies DocsScreenshotSlot,
    },
    {
      title: "Test, index, and go live",
      body: 'Click "test connection", then index. When it finishes, Zoho shows LIVE on Sources — usually 1–2 minutes depending on org size.',
      image: {
        src: "/images/zoho_enable.png",
        alt: "Andro Sources page with Zoho CRM connected and showing LIVE badge",
        width: 1920,
        height: 1080,
        placeholder: false,
        placeholderLabel: "Screenshot: Zoho LIVE on Sources",
      } satisfies DocsScreenshotSlot,
    },
  ],
};

export const docsQuestions = {
  eyebrow: "Expectations",
  heading: "What kind of questions work well",
  intro:
    "Andro is built for grounded reasoning across your connected sources, not simple data lookup. Try questions like:",
  good: [
    "Which deal should I follow up on this week?",
    "Why is this account marked at risk?",
    "What did we decide about pricing in Slack last month?",
    "What's still open from last sprint's Jira/Linear tickets?",
  ],
  note: 'Andro will tell you honestly when data is too sparse to answer confidently — that\'s intentional, not a bug. If you see "insufficient data", your connected sources don\'t have enough to answer safely.',
};

export const docsReadingAnswers = {
  eyebrow: "Answers",
  heading: "Understanding your answers",
  intro: "Every answer includes:",
  parts: [
    {
      title: "Summary",
      description: "The direct answer.",
    },
    {
      title: "Evidence",
      description:
        "The actual records used (Slack messages, Notion pages, tickets, CRM deals, commits, etc.), with quality flags (missing_contacts, stale_record, etc.).",
    },
    {
      title: "Trust state",
      description:
        "Whether the answer is confidently grounded or needs more data.",
    },
  ],
  image: {
    src: "/images/andro.png",
    alt: "Andro investigate answer with summary, evidence, and grounded deal fields",
    width: 1920,
    height: 1080,
    placeholder: false,
    placeholderLabel: "Screenshot: Andro answer with evidence highlighted",
  } satisfies DocsScreenshotSlot,
  note: 'If you see "insufficient_data" — that means your connected sources don\'t have enough info to answer safely. This is intentional.',
};

export const docsLimitations = {
  eyebrow: "Limits",
  heading: "What Andro doesn't do yet",
  items: [
    'Doesn\'t detect duplicate records across sources ("Infosys" vs "Infosys Ltd" are separate)',
    "Doesn't sync in real-time — re-index to pick up recent changes",
    "Connect Slack, Notion, Jira, Linear, and Zoho in the hosted trial (Salesforce, HubSpot coming)",
  ],
  closing: "We're actively building. Tell us what breaks.",
};

export const docsFeedback = {
  eyebrow: "Feedback",
  heading: "Found something broken?",
  body: "That's exactly what we want to hear. Sign in if you have a trial account so we can follow up.",
  prompts: [
    "What question did you ask?",
    "What answer did you get?",
    "What did you expect instead?",
  ],
  links: [
    {
      label: "DM on LinkedIn",
      href: siteConfig.linkedinUrl,
    },
  ],
};
