import type { IntegrationId } from '@/types/config';
import githubLogo from '@/assets/brands/github-icon.svg';
import slackLogo from '@/assets/brands/slack-icon.svg';
import teamsLogo from '@/assets/brands/microsoft-teams.svg';
import notionLogo from '@/assets/brands/notion-icon.svg';
import confluenceLogo from '@/assets/brands/confluence.svg';
import jiraLogo from '@/assets/brands/jira.svg';
import linearLogo from '@/assets/brands/linear-icon.svg';
import zohoLogo from '@/assets/brands/zoho-icon.svg';
import datadogLogo from '@/assets/brands/datadog-icon.svg';
import pagerdutyLogo from '@/assets/brands/pagerduty-icon.svg';
import googleChatLogo from '@/assets/brands/google-chat.svg';
import gmailLogo from '@/assets/brands/gmail.svg';
import googleDocsLogo from '@/assets/brands/google-docs.svg';

export interface IntegrationMeta {
  logo: string;
  title: string;
  description: string;
}

export const INTEGRATION_META: Record<IntegrationId, IntegrationMeta> = {
  git: {
    logo: githubLogo,
    title: 'GitHub',
    description: 'Commit history, PRs, and symbol graphs from repositories',
  },
  slack: {
    logo: slackLogo,
    title: 'Slack',
    description: 'Messages and threads from selected channels',
  },
  teams: {
    logo: teamsLogo,
    title: 'Microsoft Teams',
    description: 'Team conversations and meeting notes',
  },
  notion: {
    logo: notionLogo,
    title: 'Notion',
    description: 'Pages and databases from Notion workspaces',
  },
  confluence: {
    logo: confluenceLogo,
    title: 'Confluence',
    description: 'Wiki pages and documentation spaces',
  },
  jira: {
    logo: jiraLogo,
    title: 'Jira',
    description: 'Issues, epics, and sprint history',
  },
  linear: {
    logo: linearLogo,
    title: 'Linear',
    description: 'Issues, cycles, and project updates',
  },
  zoho: {
    logo: zohoLogo,
    title: 'Zoho CRM',
    description: 'Deals, accounts, contacts, notes, and activities',
  },
  datadog: {
    logo: datadogLogo,
    title: 'Datadog',
    description: 'Incidents, monitors, and observability context',
  },
  pagerduty: {
    logo: pagerdutyLogo,
    title: 'PagerDuty',
    description: 'On-call incidents and escalation policies',
  },
  google_chat: {
    logo: googleChatLogo,
    title: 'Google Chat',
    description: 'Messages and threads from selected spaces',
  },
  gmail: {
    logo: gmailLogo,
    title: 'Gmail',
    description: 'Email threads relevant to your team',
  },
  google_docs: {
    logo: googleDocsLogo,
    title: 'Google Docs',
    description: 'Documents your team writes and shares',
  },
};
