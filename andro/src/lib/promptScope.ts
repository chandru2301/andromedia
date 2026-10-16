/**
 * `@source` scoping for the prompt bar.
 *
 * The PromptBar `sources` menu inserts `@Name` into the draft. Rather than
 * letting that token reach the model as literal text, we treat it as a scope:
 * `parseScopedPrompt` strips the tokens out and returns the matching source
 * types, which the search/investigate calls pass through as `sourceTypes`.
 */

import type { IconSvgObject } from '@hugeicons/core-free-icons/types';
// Per-icon subpaths: the package barrel drags all ~6000 icons into the bundle.
import SourceCodeIcon from '@hugeicons/core-free-icons/SourceCodeIcon';
import GitCommitIcon from '@hugeicons/core-free-icons/GitCommitIcon';
import SlackIcon from '@hugeicons/core-free-icons/SlackIcon';
import Note01Icon from '@hugeicons/core-free-icons/Note01Icon';
import Task01Icon from '@hugeicons/core-free-icons/Task01Icon';
import KanbanIcon from '@hugeicons/core-free-icons/KanbanIcon';
import Analytics01Icon from '@hugeicons/core-free-icons/Analytics01Icon';
import UserGroupIcon from '@hugeicons/core-free-icons/UserGroupIcon';
import BubbleChatIcon from '@hugeicons/core-free-icons/BubbleChatIcon';

export interface SourceOption {
  key: string;
  name: string;
  description: string;
  icon: IconSvgObject;
}

interface SourceMeta extends SourceOption {
  label: string;
}

const SOURCE_META: SourceMeta[] = [
  { key: 'CODE', name: 'Code', label: 'Code', description: 'Indexed source files', icon: SourceCodeIcon },
  { key: 'GIT', name: 'Git', label: 'Git', description: 'Commits and history', icon: GitCommitIcon },
  { key: 'SLACK', name: 'Slack', label: 'Slack', description: 'Channel messages', icon: SlackIcon },
  { key: 'NOTION', name: 'Notion', label: 'Notion', description: 'Pages and notes', icon: Note01Icon },
  { key: 'JIRA', name: 'Jira', label: 'Jira', description: 'Issues and epics', icon: Task01Icon },
  { key: 'LINEAR', name: 'Linear', label: 'Linear', description: 'Issues and projects', icon: KanbanIcon },
  { key: 'DATADOG', name: 'Datadog', label: 'Datadog', description: 'Metrics and monitors', icon: Analytics01Icon },
  { key: 'ZOHO', name: 'Zoho', label: 'Zoho', description: 'CRM records', icon: UserGroupIcon },
  { key: 'GOOGLE_CHAT', name: 'Chat', label: 'Google Chat', description: 'Space messages', icon: BubbleChatIcon },
];
const BY_LABEL = new Map(SOURCE_META.map((s) => [s.label.toLowerCase(), s.key]));

/**
 * Rows for the `@` menu. Pass the source types the active workspace actually
 * indexes to show only those; an empty list shows every known type.
 */
export function sourceOptions(present: string[] = []): SourceOption[] {
  const upper = new Set(present.map((p) => p.toUpperCase()));
  const rows = upper.size > 0 ? SOURCE_META.filter((s) => upper.has(s.key)) : SOURCE_META;
  return rows.map(({ key, name, description, icon }) => ({ key, name, description, icon }));
}

const TOKEN = /(^|\s)@([A-Za-z]\w*)/g;

export interface ScopedPrompt {
  /** The draft with `@source` tokens removed. */
  text: string;
  /** Source types named by those tokens, in the order they appeared. */
  sourceTypes: string[];
}

export function parseScopedPrompt(draft: string): ScopedPrompt {
  const sourceTypes: string[] = [];
  const text = draft
    .replace(TOKEN, (match, lead: string, label: string) => {
      const key = BY_LABEL.get(label.toLowerCase());
      if (!key) {
        return match;
      }
      if (!sourceTypes.includes(key)) {
        sourceTypes.push(key);
      }
      return lead;
    })
    .replace(/\s{2,}/g, ' ')
    .trim();
  return { text, sourceTypes };
}
