<script setup lang="ts">
/**
 * In-app User Guide.
 *
 * Two-pane layout: left rail = sticky TOC of registered sections; right
 * pane = stacked section cards, each rendered from its source .md file.
 * The TOC's active highlight follows scroll position via
 * IntersectionObserver.
 *
 * Sections are sourced from `components/guide/sections.ts`, which lists the
 * steps for adding one — this page doesn't change.
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
import { useRoute, useRouter } from '#imports'
import { Bars3Icon, XMarkIcon } from '@heroicons/vue/24/outline'
import { sections } from '~/components/guide/sections'
import GuideRenderer from '~/components/guide/GuideRenderer.vue'
import ScrollJumpButton, { SCROLL_JUMP_CLEARANCE } from '~/components/ScrollJumpButton.vue'

const route = useRoute()
const router = useRouter()
const activeSectionId = ref<string>(sections[0]?.id ?? '')
const mobileNavOpen = ref(false)

// Back to top. The layout's <main> owns the scroll (overflow-auto in
// default.vue), so we track its scrollTop rather than window's. `scrolledDown`
// shows the button: it flips true the moment the reader leaves the top —
// whether by scrolling or by jumping to any section past the first (a TOC
// click smooth-scrolls <main>, firing scroll events) — and back to false once
// they're returned to the top.
const scroller = ref<HTMLElement | null>(null)
const scrolledDown = ref(false)
const pageTitle = ref<HTMLElement | null>(null)

function updateScrolled() {
  const el = scroller.value
  if (el) scrolledDown.value = el.scrollTop > 8
}

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
      const id = (entry.target as HTMLElement).dataset.sectionId ?? null
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

/**
 * Pick a section from the TOC. Navigates rather than scrolling directly, so the
 * fragment lands in the URL and the scroll itself runs through the same route
 * watcher a `/guide#x` link inside the body would — one path, not two.
 *
 * `push`, so Back steps back through picks, matching how every anchor-based TOC
 * behaves. Only a click writes the URL; scrolling deliberately does not.
 */
function jumpTo(sectionId: string, anchorId?: string) {
  activeSectionId.value = sectionId
  mobileNavOpen.value = false
  router.push({ hash: `#${anchorId ?? sectionId}` })
}

/**
 * Smooth-scroll the page's scroll container back to the very top.
 *
 * The scroll container is the layout-level <main>, not window —
 * default.vue gives <main> overflow-auto so it owns the scroll. Falls
 * back to window scroll if the element isn't reachable (test envs).
 */
function scrollToTop(e: MouseEvent) {
  // A press never focuses the button, so only a keyboard user arrives here with focus on it, and the button unmounts
  // at the top, dropping that focus to <body>: it moves to the title, where the next Tab reaches the contents.
  // preventScroll, because focusing the off-screen title would jump there and cut the smooth scroll short.
  if (document.activeElement === e.currentTarget) pageTitle.value?.focus({ preventScroll: true })
  const el = scroller.value ?? document.querySelector('main')
  if (el) {
    el.scrollTo({ top: 0, behavior: 'smooth' })
  }
  else {
    window.scrollTo({ top: 0, behavior: 'smooth' })
  }
  activeSectionId.value = sections[0]?.id ?? ''
  // Drop the fragment: the reader is back at the top, so a URL still naming a
  // section would be wrong to copy. A click writes the URL like any other, and
  // `push` keeps it undoable — Back returns to the section they came from.
  // Guarded so clicking at the top doesn't stack no-op history entries.
  if (route.hash) router.push('/guide')
}

/**
 * Resolve a URL fragment back to the owning section and scroll into view.
 * Used both on initial mount and when the user clicks a `/guide#x` link
 * from within a section (the inbound URL change fires `route.hash` but
 * Nuxt's router doesn't auto-scroll on same-route hash changes).
 */
/**
 * Owning section for a URL fragment: an exact id first, then the longest id the
 * fragment is prefixed by. Order matters because ids nest — a plain
 * first-prefix-wins match resolves `subagents-tasks-reminders` to `subagents`
 * and highlights the wrong entry.
 */
function sectionIdForHash(bare: string): string | undefined {
  const exact = sections.find(s => bare === s.id)
  if (exact) return exact.id
  return sections
    .filter(s => bare.startsWith(`${s.id}-`))
    .sort((a, b) => b.id.length - a.id.length)[0]?.id
}

function jumpToHash(hash: string, behavior: ScrollBehavior = 'auto') {
  if (!hash) return
  const bare = hash.replace(/^#/, '')
  if (!bare) return
  const el = document.getElementById(bare)
  if (el) {
    el.scrollIntoView({ behavior, block: 'start' })
  }
  const sectionId = sectionIdForHash(bare)
  if (sectionId) activeSectionId.value = sectionId
}

onMounted(async () => {
  // Wait one tick so each section's markdown body mounts and emits its
  // heading anchors before the observer goes hunting.
  await nextTick()
  setupObserver()
  jumpToHash(route.hash)
  scroller.value = document.querySelector('main')
  scroller.value?.addEventListener('scroll', updateScrolled, { passive: true })
  // <main> is the layout's, shared with every page, so the clearance comes off again on unmount.
  scroller.value?.classList.add(SCROLL_JUMP_CLEARANCE)
  updateScrolled()
})

// Same-route hash changes don't reload the page, so nothing scrolls on their
// own: a TOC pick, a `/guide#x` link in the body, and Back between fragments
// all arrive here. Smooth, because every one of them is a deliberate jump the
// reader should be able to follow; the mount-time scroll stays instant.
//
// The URL is never written while scrolling. Scroll-spy moves the TOC highlight
// only — the address bar changes on a click and nothing else, which is what
// MDN, VitePress and Docusaurus all do.
watch(() => route.hash, (h) => {
  jumpToHash(h, 'smooth')
})

onUnmounted(() => {
  observer?.disconnect()
  observer = null
  scroller.value?.removeEventListener('scroll', updateScrolled)
  scroller.value?.classList.remove(SCROLL_JUMP_CLEARANCE)
})

const tocItems = computed(() => sections.map(s => ({
  id: s.id,
  title: s.title,
  icon: s.icon,
})))
</script>

<template>
  <div class="@container">
    <!-- The root is full width, unlike the header row and the body, so Back to top can sit at <main>'s right edge,
         clear of the reading column; as the @container it labels the button only where that edge leaves it room. -->
    <!-- max-w-5xl (64rem) is the TOC rail, its gap and the reading column (w-56 + gap-8 + max-w-3xl): the header row
         ends where the column does, so Clawdia stands over the first card's right corner at every width. Her negative
         bottom margin cancels the row's, so she stands on that card's top edge: keep the two pairs equal. -->
    <div class="max-w-5xl flex items-end justify-between gap-4 mb-4 md:mb-3">
      <div>
        <h1
          ref="pageTitle"
          tabindex="-1"
          class="text-lg font-semibold text-fg-strong mb-6 md:mb-0"
        >
          User Guide
        </h1>

        <!-- Mobile TOC toggle. On md+ the sticky rail handles navigation; on
             narrower viewports the rail collapses behind this button to keep
             the reading column wide enough to be readable. -->
        <button
          type="button"
          class="md:hidden flex items-center gap-2 px-3 py-2 text-sm text-fg-strong border border-border rounded-lg bg-surface-elevated hover:bg-muted/40 transition-colors"
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
      </div>
      <img
        src="/clawdia-reading.webp"
        alt=""
        width="134"
        height="150"
        class="shrink-0 w-18 md:w-20 h-auto mr-4 md:mr-6 -mb-4 md:-mb-3"
      >
    </div>

    <div class="max-w-5xl flex flex-col md:flex-row gap-8">
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

      <div class="flex-1 min-w-0 max-w-3xl space-y-8">
        <section
          v-for="s in sections"
          :id="s.id"
          :key="s.id"
          :data-section-id="s.id"
          class="border border-border rounded-xl bg-surface-elevated p-6"
        >
          <!-- Section header: icon + title. Mirrors the icon shown in the
               TOC so operators can scan the page and see the same visual
               anchor that brought them here. The markdown's leading h1 is
               suppressed (suppressFirstH1) so the title isn't duplicated. -->
          <header class="flex items-center gap-3 mb-6">
            <component
              :is="s.icon"
              class="w-7 h-7 shrink-0 text-fg-strong"
              aria-hidden="true"
            />
            <h1 class="text-2xl font-bold text-fg-strong leading-tight m-0">
              {{ s.title }}
            </h1>
          </header>
          <GuideRenderer
            :section-id="s.id"
            :content="s.content"
            suppress-first-h1
          />
        </section>
      </div>
    </div>

    <!-- 72rem leaves the labelled pill a gutter beside the 64rem column. Narrower, where the card reaches the edge, the
         arrow alone shows, round and inside the card's padding, with the label kept as its accessible name. -->
    <ScrollJumpButton
      v-if="scrolledDown"
      direction="up"
      align="end"
      data-testid="guide-back-to-top"
      title="Back to top"
      class="@max-6xl:px-1.5"
      @click="scrollToTop"
    >
      <span class="sr-only @6xl:not-sr-only">Back to top</span>
    </ScrollJumpButton>
  </div>
</template>
