/**
 * Tool-metadata composable — thin consumer over GET /api/tools/meta.
 *
 * The backend is the authoritative source for name / category / icon /
 * shortDescription / actions / requiresConfig / system (JCLAW-72). This
 * composable only owns purely-presentational concerns that have no business
 * on the JVM: Tailwind class names keyed by category, and the SVG <path>
 * string dictionary keyed by the backend-supplied icon name.
 *
 * Adding a new tool in app/tools/ is now a backend-only change. This file
 * should not need updates unless the four-category taxonomy grows or the
 * set of supported SVG icons expands.
 */

import { ref, computed, type Ref, type ComputedRef } from 'vue'

// ───── Types ─────────────────────────────────────────────────────────────

export interface ToolAction {
  name: string
  description: string
}

/** Shape returned by GET /api/tools/meta. */
export interface ToolApiMeta {
  name: string
  category: ToolCategory
  icon: string
  shortDescription: string
  system: boolean
  requiresConfig?: string
  actions: ToolAction[]
}

export type ToolCategory = 'System' | 'Web' | 'Files' | 'Utilities'

/** Shape consumed by the tools page — backend meta plus derived styling. */
export interface ToolMeta extends ToolApiMeta {
  categoryColor: string
  iconBg: string
  iconColor: string
  functions: ToolAction[] // legacy alias for actions; preserved for pages/tools.vue compatibility
}

// ───── Presentational mappings (frontend-only concerns) ──────────────────

const CATEGORY_STYLES: Record<ToolCategory, { categoryColor: string, iconBg: string, iconColor: string }> = {
  System: {
    categoryColor: 'text-neutral-400 bg-neutral-800',
    iconBg: 'bg-neutral-800',
    iconColor: 'text-neutral-300',
  },
  Files: {
    categoryColor: 'text-amber-400 bg-amber-500/15',
    iconBg: 'bg-amber-500/15',
    iconColor: 'text-amber-400',
  },
  Web: {
    categoryColor: 'text-blue-400 bg-blue-500/15',
    iconBg: 'bg-blue-500/15',
    iconColor: 'text-blue-400',
  },
  Utilities: {
    categoryColor: 'text-emerald-400 bg-emerald-500/15',
    iconBg: 'bg-emerald-500/15',
    iconColor: 'text-emerald-400',
  },
}

const PILL_CLASSES: Record<ToolCategory, string> = {
  System: 'bg-neutral-800 border-neutral-700/60 text-neutral-400',
  Files: 'bg-amber-500/10 border-amber-500/25 text-amber-400',
  Web: 'bg-blue-500/10 border-blue-500/25 text-blue-400',
  Utilities: 'bg-emerald-500/10 border-emerald-500/25 text-emerald-400',
}

export const TOOL_CATEGORIES = ['System', 'Files', 'Web', 'Utilities'] as const

// ───── Module-level cache ────────────────────────────────────────────────
// One fetch per page load, shared across every component that calls
// useToolMeta(). Re-entrant: concurrent callers during the first fetch all
// await the same in-flight promise.

const metaList: Ref<ToolApiMeta[]> = ref([])
let fetchPromise: Promise<ToolApiMeta[]> | null = null

async function ensureLoaded(): Promise<ToolApiMeta[]> {
  if (metaList.value.length > 0) return metaList.value
  if (!fetchPromise) {
    fetchPromise = $fetch<ToolApiMeta[]>('/api/tools/meta')
      .then((data) => {
        metaList.value = data ?? []
        return metaList.value
      })
      .catch(() => {
        // Network error — leave metaList empty so pages render a graceful
        // empty grid. Reset the promise so a retry on next call is possible.
        fetchPromise = null
        return [] as ToolApiMeta[]
      })
  }
  return fetchPromise
}

function augment(api: ToolApiMeta): ToolMeta {
  const styles = CATEGORY_STYLES[api.category] ?? CATEGORY_STYLES.Utilities
  return {
    ...api,
    categoryColor: styles.categoryColor,
    iconBg: styles.iconBg,
    iconColor: styles.iconColor,
    functions: api.actions, // legacy name preserved for pages/tools.vue
  }
}

// ───── Public composable ────────────────────────────────────────────────

export function useToolMeta() {
  // Kick off the fetch eagerly so consumers don't have to remember to await.
  ensureLoaded()

  const TOOL_META: ComputedRef<Record<string, ToolMeta>> = computed(() => {
    const out: Record<string, ToolMeta> = {}
    for (const t of metaList.value) {
      out[t.name] = augment(t)
    }
    return out
  })

  const ORDERED_TOOLS: ComputedRef<string[]> = computed(() =>
    metaList.value.map(t => t.name),
  )

  function getToolMeta(name: string): ToolMeta | null {
    return TOOL_META.value[name] ?? null
  }

  function getPillClass(name: string): string {
    const category = TOOL_META.value[name]?.category
    return category ? PILL_CLASSES[category] : PILL_CLASSES.System
  }

  return {
    TOOL_META,
    ORDERED_TOOLS,
    getToolMeta,
    getPillClass,
    /** Awaitable — resolves once the /api/tools/meta fetch lands. */
    ready: ensureLoaded,
  }
}
