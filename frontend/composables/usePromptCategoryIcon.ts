import type { FunctionalComponent } from 'vue'
import {
  BriefcaseIcon,
  ChartBarIcon,
  CodeBracketIcon,
  FolderIcon,
  PencilSquareIcon,
  SparklesIcon,
} from '@heroicons/vue/24/outline'

// Maps a fixed prompt-category value to its Heroicon — the same icon style the
// Tools page uses (SVG glyphs, not emoji), rendered via <component :is>. Keyed
// on the stable category value so a label rewording never changes the glyph;
// an unmapped value falls back to FolderIcon so nothing renders blank.
const PROMPT_CATEGORY_ICONS: Record<string, FunctionalComponent> = {
  CODING: CodeBracketIcon,
  WRITING: PencilSquareIcon,
  ANALYSIS: ChartBarIcon,
  CREATIVE: SparklesIcon,
  BUSINESS: BriefcaseIcon,
  CUSTOM: FolderIcon,
}

export function promptCategoryIcon(value: string): FunctionalComponent {
  return PROMPT_CATEGORY_ICONS[value] ?? FolderIcon
}
