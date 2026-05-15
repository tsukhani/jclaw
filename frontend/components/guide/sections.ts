/**
 * JCLAW-292: section registry for the in-app User Guide. The order in
 * this array drives:
 *
 *   - the left-rail TOC's display order
 *   - the rendered content stack on /guide
 *   - the deterministic anchor ids the guide page emits (id: subagents
 *     becomes #subagents-<slug>)
 *
 * Add a new section by writing its component under this directory and
 * appending an entry. No plumbing changes elsewhere — pages/guide.vue
 * iterates this array and lets each component render itself.
 *
 * Each section component must:
 *   - accept no props
 *   - render its own headings (h2 for the section title, h3 for
 *     subsections) so the TOC observer can find them
 *   - emit anchor ids prefixed with `<section-id>-` so cross-section
 *     anchors don't collide
 */
import { UsersRound } from 'lucide-vue-next'
import SubagentsSection from './SubagentsSection.vue'
import type { Component } from 'vue'

export interface GuideSection {
  /** Stable id used in the URL hash and as the anchor-id prefix. Lowercase
   *  kebab. Don't rename once shipped — operator bookmarks point at this. */
  id: string
  /** Display label rendered in the TOC and as the section's h2 heading. */
  title: string
  /** Icon ref shown in the TOC next to the title. Heroicons-outline or
   *  Lucide both work; the page renders at w-3.5 h-3.5 to match the
   *  rest of the chat surface. */
  icon: Component
  /** The Vue component that renders the section body. */
  component: Component
}

export const sections: GuideSection[] = [
  {
    id: 'subagents',
    title: 'Subagents',
    icon: UsersRound,
    component: SubagentsSection,
  },
]
