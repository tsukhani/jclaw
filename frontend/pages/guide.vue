<script setup lang="ts">
/**
 * JCLAW-292: in-app User Guide.
 *
 * Two-pane layout: left rail = sticky TOC of registered sections; right
 * pane = stacked section components with a max-w reading rail. The TOC's
 * active highlight follows scroll position via IntersectionObserver so
 * the operator always knows where they are.
 *
 * Mobile: TOC collapses behind a button that toggles a slide-down panel
 * (sticky positioning would shrink the reading column too aggressively
 * at narrow widths). The chosen breakpoint mirrors what /chat uses to
 * collapse its sidebar.
 *
 * Sections are sourced from `components/guide/sections.ts`. Adding a new
 * section is one import + one array entry there — this file doesn't
 * change. The page never hits `/api`; content is fully client-side.
 */
import { computed, onMounted, onUnmounted, ref, nextTick } from 'vue'
import { useRoute } from '#imports'
import { Bars3Icon, XMarkIcon } from '@heroicons/vue/24/outline'
import { sections } from '~/components/guide/sections'

const route = useRoute()
const activeSectionId = ref<string>(sections[0]?.id ?? '')
const mobileNavOpen = ref(false)

// Watcher target for IntersectionObserver. We register one observer that
// watches the section-anchor sentinels emitted at the top of each
// section card; whichever sentinel is closest to the viewport top wins
// the active highlight. RootMargin pulls the trigger slightly below the
// header so a section becomes "active" the moment its title scrolls into
// the upper third of the viewport rather than only after the entire
// section has cleared the top edge.
let observer: IntersectionObserver | null = null

function setupObserver() {
  if (typeof IntersectionObserver === 'undefined') return // SSR / test env without DOM
  observer = new IntersectionObserver((entries) => {
    // Multiple entries can fire per scroll tick. Track the topmost-visible
    // one rather than just the first because order isn't guaranteed.
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
  // anchorId is the deep target inside a section (sub-heading); when
  // omitted we jump to the section's anchor sentinel.
  const target = anchorId
    ? document.getElementById(anchorId)
    : document.querySelector(`[data-section-id="${sectionId}"]`)
  if (!target) return
  target.scrollIntoView({ behavior: 'smooth', block: 'start' })
  activeSectionId.value = sectionId
  mobileNavOpen.value = false
}

onMounted(async () => {
  // Wait one tick so the section components mount + emit their sentinel
  // elements before the observer goes hunting for them.
  await nextTick()
  setupObserver()

  // Honour the inbound URL hash on first load. The browser's native
  // anchor jump can fire before the section components hydrate (especially
  // on a hard refresh of /guide#xyz), so we re-jump explicitly once the
  // DOM is ready. Falls through harmlessly if the hash is absent or
  // points at an id that doesn't exist yet (custom 404s aren't worth
  // it for this surface — silent fallthrough lands on section 0).
  const hash = route.hash?.replace(/^#/, '')
  if (hash) {
    const el = document.getElementById(hash)
    if (el) {
      el.scrollIntoView({ behavior: 'auto', block: 'start' })
      // Derive the section id from the anchor: anchors are
      // `<sectionId>-<slug>`, so the prefix up to the first dash is the
      // owning section.
      const sectionId = sections.find(s => hash === s.id || hash.startsWith(`${s.id}-`))?.id
      if (sectionId) activeSectionId.value = sectionId
    }
  }
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
      <!-- TOC rail. Sticky on desktop so it stays in view while reading
           a long section; an overlay panel on mobile that closes on link
           click via jumpTo above. -->
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
           regardless of viewport width — same column width chat.vue uses
           for its message rail. -->
      <div class="flex-1 min-w-0">
        <div class="max-w-3xl space-y-8">
          <section
            v-for="s in sections"
            :id="s.id"
            :key="s.id"
            :data-section-id="s.id"
            class="border border-border rounded-xl bg-surface-elevated p-6"
          >
            <component :is="s.component" />
          </section>
        </div>
      </div>
    </div>
  </div>
</template>
