import type { IntegrationId } from "@/types/product-config";

export type BrandId = IntegrationId | "salesforce" | "hubspot";

export interface BrandMeta {
  logo: string;
  title: string;
}

export const BRAND_META: Record<BrandId, BrandMeta> = {
  git: {
    logo: "/brands/github-icon.svg",
    title: "Git / GitHub",
  },
  slack: {
    logo: "/brands/slack-icon.svg",
    title: "Slack",
  },
  teams: {
    logo: "/brands/microsoft-teams.svg",
    title: "Microsoft Teams",
  },
  notion: {
    logo: "/brands/notion-icon.svg",
    title: "Notion",
  },
  confluence: {
    logo: "/brands/confluence.svg",
    title: "Confluence",
  },
  jira: {
    logo: "/brands/jira.svg",
    title: "Jira",
  },
  linear: {
    logo: "/brands/linear-icon.svg",
    title: "Linear",
  },
  zoho: {
    logo: "/brands/zoho-icon.svg",
    title: "Zoho CRM",
  },
  datadog: {
    logo: "/brands/datadog-icon.svg",
    title: "Datadog",
  },
  pagerduty: {
    logo: "/brands/pagerduty-icon.svg",
    title: "PagerDuty",
  },
  salesforce: {
    logo: "/brands/salesforce.svg",
    title: "Salesforce",
  },
  hubspot: {
    logo: "/brands/hubspot.svg",
    title: "HubSpot",
  },
};

export function getBrandLogo(id: BrandId): string {
  return BRAND_META[id].logo;
}

export function getBrandTitle(id: BrandId): string {
  return BRAND_META[id].title;
}
