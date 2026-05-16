<script setup lang="ts">
/**
 * In-app User Guide.
 *
 * Two-pane layout: left rail = sticky TOC of registered sections; right
 * pane = stacked section cards, each rendered from its source .md file.
 * The TOC's active highlight follows scroll position via
 * IntersectionObserver.
 *
 * Sections are sourced from `components/guide/sections.ts`. Adding a new
 * section is one .md file + one entry there — this page doesn't change.
 * The page hits no /api endpoints; content is bundled at build time via
 * Vite's `?raw` markdown imports.
 *
 * Anchor scheme: each section's `id` becomes the URL-fragment prefix for
 * every heading inside that section (e.g. `subagents-spawn-modes`). Deep
 * links like `/guide#subagents-async-yield` jump straight to the right
 * place; the onMounted hash handler below re-jumps after hydration so
 * hard refreshes work.
 */
import { computed, onMounted, onUnmounted, ref, nextTick, watch } from 'vue'
import { useRoute } from '#imports'
import { Bars3Icon, XMarkIcon } from '@heroicons/vue/24/outline'
import { sections } from '~/components/guide/sections'
import GuideRenderer from '~/components/guide/GuideRenderer.vue'

const route = useRoute()
const activeSectionId = ref<string>(sections[0]?.id ?? '')
const mobileNavOpen = ref(false)

// One IntersectionObserver watches the section sentinels. The topmost
// intersecting sentinel wins the active highlight. RootMargin pulls the
// trigger slightly below the header so a section becomes "active" the
// moment its title scrolls into the upper third of the viewport, not
// only after the entire section has cleared the top edge.
let observer: IntersectionObserver | null = null

function setupObserver() {
  if (typeof IntersectionObserver === 'undefined') return // SSR / test env without DOM
  observer = new IntersectionObserver((entries) => {
    let topId: string | null = null
    let topY = Number.POSITIVE_INFINITY
    for (const entry of entries) {
      if (!entry.isIntersecting) continue
      const id = entry.target.getAttribute('data-section-id')
      if (!id) continue
      const top = entry.boundingClientRect.top
      if (top < topY) {
        topY = top
        topId = id
      }
    }
    if (topId) activeSectionId.value = topId
  }, {
    // -40% bottom: trigger when the top of the section is in the upper
    //  60% of the viewport. Threshold 0 fires on any pixel intersection.
    rootMargin: '-72px 0px -40% 0px',
    threshold: 0,
  })
  for (const s of sections) {
    const el = document.querySelector(`[data-section-id="${s.id}"]`)
    if (el) observer.observe(el)
  }
}

function jumpTo(sectionId: string, anchorId?: string) {
  const target = anchorId
    ? document.getElementById(anchorId)
    : document.querySelector(`[data-section-id="${sectionId}"]`)
  if (!target) return
  target.scrollIntoView({ behavior: 'smooth', block: 'start' })
  activeSectionId.value = sectionId
  mobileNavOpen.value = false
}

/**
 * Resolve a URL fragment back to the owning section and scroll into view.
 * Used both on initial mount and when the user clicks a `/guide#x` link
 * from within a section (the inbound URL change fires `route.hash` but
 * Nuxt's router doesn't auto-scroll on same-route hash changes).
 */
function jumpToHash(hash: string) {
  if (!hash) return
  const bare = hash.replace(/^#/, '')
  if (!bare) return
  const el = document.getElementById(bare)
  if (el) {
    el.scrollIntoView({ behavior: 'auto', block: 'start' })
  }
  // The section id is either the literal fragment or its prefix-before-dash.
  const sectionId = sections.find(s => bare === s.id || bare.startsWith(`${s.id}-`))?.id
  if (sectionId) activeSectionId.value = sectionId
}

onMounted(async () => {
  // Wait one tick so each section's markdown body mounts and emits its
  // heading anchors before the observer goes hunting.
  await nextTick()
  setupObserver()
  jumpToHash(route.hash)
})

// Same-route hash changes (a click on a `/guide#section-id` link from
// inside the page) don't reload the page; jump to the new anchor.
watch(() => route.hash, (h) => {
  jumpToHash(h)
})

onUnmounted(() => {
  observer?.disconnect()
  observer = null
})

const tocItems = computed(() => sections.map(s => ({
  id: s.id,
  title: s.title,
  icon: s.icon,
})))
</script>

<template>
  <div>
    <h1 class="text-lg font-semibold text-fg-strong mb-6">
      User Guide
    </h1>

    <!-- Mobile TOC toggle. On md+ the sticky rail handles navigation; on
         narrower viewports the rail collapses behind this button to keep
         the reading column wide enough to be readable. -->
    <button
      type="button"
      class="md:hidden mb-4 flex items-center gap-2 px-3 py-2 text-sm text-fg-strong border border-border rounded-lg bg-surface-elevated hover:bg-muted/40 transition-colors"
      :aria-expanded="mobileNavOpen"
      aria-controls="guide-toc-mobile"
      @click="mobileNavOpen = !mobileNavOpen"
    >
      <component
        :is="mobileNavOpen ? XMarkIcon : Bars3Icon"
        class="w-4 h-4"
        aria-hidden="true"
      />
      <span>{{ mobileNavOpen ? 'Hide contents' : 'Contents' }}</span>
    </button>

    <div class="flex flex-col md:flex-row gap-8">
      <!-- TOC rail. Sticky on desktop; an overlay panel on mobile that
           closes on link click via jumpTo above. -->
      <aside
        id="guide-toc-mobile"
        :class="[
          'md:w-56 md:shrink-0 md:sticky md:top-4 md:self-start',
          mobileNavOpen ? 'block' : 'hidden md:block',
        ]"
        aria-label="User guide table of contents"
      >
        <nav class="border border-border rounded-xl bg-surface-elevated overflow-hidden">
          <div class="px-3 py-2 border-b border-border text-[11px] font-medium uppercase tracking-wide text-fg-muted">
            Sections
          </div>
          <ul class="py-1">
            <li
              v-for="item in tocItems"
              :key="item.id"
            >
              <button
                type="button"
                :class="[
                  'w-full flex items-center gap-2 px-3 py-2 text-sm text-left transition-colors',
                  activeSectionId === item.id
                    ? 'bg-emerald-50/60 dark:bg-emerald-900/20 text-emerald-700 dark:text-emerald-300 font-medium'
                    : 'text-fg-strong hover:bg-muted/40',
                ]"
                :aria-current="activeSectionId === item.id ? 'true' : undefined"
                :data-testid="`guide-toc-item-${item.id}`"
                @click="jumpTo(item.id)"
              >
                <component
                  :is="item.icon"
                  class="w-3.5 h-3.5 shrink-0"
                  aria-hidden="true"
                />
                <span class="truncate">{{ item.title }}</span>
              </button>
            </li>
          </ul>
        </nav>
      </aside>

      <!-- Reading rail. Caps at max-w-3xl to keep line lengths comfortable
           regardless of viewport width. -->
      <div class="flex-1 min-w-0">
        <div class="max-w-3xl space-y-8">
          <section
            v-for="s in sections"
            :id="s.id"
            :key="s.id"
            :data-section-id="s.id"
            class="border border-border rounded-xl bg-surface-elevated p-6"
          >
            <GuideRenderer
              :section-id="s.id"
              :content="s.content"
            />
          </section>
        </div>
      </div>
    </div>
  </div>
</template>
