import { BRAND_META } from "@/data/integration-brands";
import type { IntegrationId } from "@/types/product-config";

export interface ProductIntegrationMeta {
  logo: string;
  title: string;
  description: string;
}

export const PRODUCT_INTEGRATION_META: Record<IntegrationId, ProductIntegrationMeta> = {
  git: {
    logo: BRAND_META.git.logo,
    title: BRAND_META.git.title,
    description: "Commit history, PRs, and symbol graphs from repositories.",
  },
  slack: {
    logo: BRAND_META.slack.logo,
    title: BRAND_META.slack.title,
    description: "Messages and threads from selected channels.",
  },
  teams: {
    logo: BRAND_META.teams.logo,
    title: BRAND_META.teams.title,
    description: "Team conversations and meeting notes.",
  },
  notion: {
    logo: BRAND_META.notion.logo,
    title: BRAND_META.notion.title,
    description: "Pages and databases from connected Notion workspaces.",
  },
  confluence: {
    logo: BRAND_META.confluence.logo,
    title: BRAND_META.confluence.title,
    description: "Wiki pages and documentation spaces.",
  },
  jira: {
    logo: BRAND_META.jira.logo,
    title: BRAND_META.jira.title,
    description: "Issues, epics, and sprint history.",
  },
  linear: {
    logo: BRAND_META.linear.logo,
    title: BRAND_META.linear.title,
    description: "Issues, cycles, and project updates.",
  },
  zoho: {
    logo: BRAND_META.zoho.logo,
    title: BRAND_META.zoho.title,
    description: "Deals, accounts, contacts, notes, and activities.",
  },
  datadog: {
    logo: BRAND_META.datadog.logo,
    title: BRAND_META.datadog.title,
    description: "Incidents, monitors, and observability context.",
  },
  pagerduty: {
    logo: BRAND_META.pagerduty.logo,
    title: BRAND_META.pagerduty.title,
    description: "On-call incidents and escalation policies.",
  },
};
