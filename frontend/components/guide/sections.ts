/**
 * Registry of in-app User Guide sections.
 *
 * Each entry pairs section metadata (id, title, icon) with the raw
 * markdown content of `docs/user-guide/<slug>.md`. The .md files are the
 * single source of truth for operator-facing copy; this file does the
 * presentation plumbing (TOC ordering, icon mapping).
 *
 * Adding a new section:
 *
 *   1. Write `docs/user-guide/<your-section>.md`.
 *   2. Import its `?raw` content here and append a `GuideSection` entry.
 *   3. Done. `pages/guide.vue` iterates this array; no other plumbing.
 *
 * The `id` field is the URL-fragment prefix every anchor in that section
 * gets namespaced with (e.g. `id: 'subagents'` + `## Spawn modes` →
 * `#subagents-spawn-modes`). Don't rename a shipped id — operator
 * bookmarks point at it.
 */
import {
  BookOpenIcon,
  ChatBubbleOvalLeftEllipsisIcon,
  ChartBarIcon,
  ClipboardDocumentCheckIcon,
  Cog6ToothIcon,
  LinkIcon,
  PuzzlePieceIcon,
} from '@heroicons/vue/24/outline'
import { BotMessageSquare, UsersRound } from 'lucide-vue-next'
import type { Component } from 'vue'

// Vite's `?raw` query returns the file's contents as a string at build
// time. The .md files ship inside the SPA bundle, so the guide page
// hits no /api endpoints to render.
import gettingStartedMd from '../../../docs/user-guide/getting-started.md?raw'
import chatMd from '../../../docs/user-guide/chat.md?raw'
import agentsMd from '../../../docs/user-guide/agents.md?raw'
import conversationsChannelsMd from '../../../docs/user-guide/conversations-and-channels.md?raw'
import subagentsMd from '../../../docs/user-guide/subagents.md?raw'
import tasksMd from '../../../docs/user-guide/tasks.md?raw'
import skillsToolsMcpMd from '../../../docs/user-guide/skills-tools-mcp.md?raw'
import settingsMd from '../../../docs/user-guide/settings.md?raw'
import logsDashboardMd from '../../../docs/user-guide/logs-and-dashboard.md?raw'

export interface GuideSection {
  /** URL-fragment-prefix and stable identifier. Lowercase kebab. Don't
   *  rename once shipped — operator bookmarks point at this. */
  id: string
  /** Display label rendered in the TOC and as the section's h1 heading. */
  title: string
  /** Icon ref shown in the TOC next to the title. */
  icon: Component
  /** Raw markdown content of the section's `.md` file. */
  content: string
}

export const sections: GuideSection[] = [
  {
    id: 'getting-started',
    title: 'Getting Started',
    icon: BookOpenIcon,
    content: gettingStartedMd,
  },
  {
    id: 'chat',
    title: 'Chat',
    icon: ChatBubbleOvalLeftEllipsisIcon,
    content: chatMd,
  },
  {
    id: 'agents',
    title: 'Agents',
    icon: BotMessageSquare,
    content: agentsMd,
  },
  {
    id: 'conversations-and-channels',
    title: 'Conversations & Channels',
    icon: LinkIcon,
    content: conversationsChannelsMd,
  },
  {
    id: 'subagents',
    title: 'Subagents',
    icon: UsersRound,
    content: subagentsMd,
  },
  {
    id: 'tasks',
    title: 'Tasks',
    icon: ClipboardDocumentCheckIcon,
    content: tasksMd,
  },
  {
    id: 'skills-tools-mcp',
    title: 'Skills, Tools & MCP Servers',
    icon: PuzzlePieceIcon,
    content: skillsToolsMcpMd,
  },
  {
    id: 'settings',
    title: 'Settings',
    icon: Cog6ToothIcon,
    content: settingsMd,
  },
  {
    id: 'logs-and-dashboard',
    title: 'Logs & Dashboard',
    icon: ChartBarIcon,
    content: logsDashboardMd,
  },
]
