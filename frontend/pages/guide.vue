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
import { ArrowUpIcon, Bars3Icon, XMarkIcon } from '@heroicons/vue/24/outline'
import { sections } from '~/components/guide/sections'
import GuideRenderer from '~/components/guide/GuideRenderer.vue'

const route = useRoute()
const activeSectionId = ref<string>(sections[0]?.id ?? '')
const mobileNavOpen = ref(false)

// Back-to-top affordance. The layout's <main> owns the scroll (overflow-auto
// in default.vue), so we track its scrollTop rather than window's. `scrolledDown`
// gates the "Scroll to the top" hint under the Clawdia mascot: it flips true the
// moment the reader leaves the top — whether by scrolling or by jumping to any
// section past the first (a TOC click smooth-scrolls <main>, firing scroll
// events) — and back to false once they're returned to the top.
const scroller = ref<HTMLElement | null>(null)
const scrolledDown = ref(false)

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
 * Smooth-scroll the page's scroll container back to the very top.
 * Wired to the Clawdia mascot in the right gutter so a single click
 * gets the operator out of deep scroll without hunting for a separate
 * back-to-top button.
 *
 * The scroll container is the layout-level <main>, not window —
 * default.vue gives <main> overflow-auto so it owns the scroll. Falls
 * back to window scroll if the element isn't reachable (test envs).
 */
function scrollToTop() {
  const el = scroller.value ?? document.querySelector('main')
  if (el) {
    el.scrollTo({ top: 0, behavior: 'smooth' })
  }
  else {
    window.scrollTo({ top: 0, behavior: 'smooth' })
  }
  activeSectionId.value = sections[0]?.id ?? ''
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
  scroller.value = document.querySelector('main')
  scroller.value?.addEventListener('scroll', updateScrolled, { passive: true })
  updateScrolled()
})

// Same-route hash changes (a click on a `/guide#section-id` link from
// inside the page) don't reload the page; jump to the new anchor.
watch(() => route.hash, (h) => {
  jumpToHash(h)
})

onUnmounted(() => {
  observer?.disconnect()
  observer = null
  scroller.value?.removeEventListener('scroll', updateScrolled)
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

      <!-- Reading rail + right-side mascot rail. The flex wrapper lets
           Clawdia live in the gutter to the right of the reading column,
           sticky and pinned at the top alongside the TOC. items-start
           keeps each child's sticky origin honest (without it, a flex
           container's default stretch alignment would defeat sticky). -->
      <div class="flex-1 min-w-0 flex items-start gap-8">
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

        <!-- Reading-Clawdia mascot, pinned in the right gutter alongside
             the TOC. xl:flex (not md:flex) because at md/lg the reading
             column already eats most of the horizontal budget — squeezing
             a 134px mascot into a narrow gutter there would crowd the
             text. shrink-0 keeps the mascot at its natural width so the
             reading column gives ground first on tighter widths. Wraps
             the <img> in a <button> so a click scrolls the page back to
             the top — handy when the operator is deep in the guide; a
             flex-col stacks the fading "Scroll to the top" hint beneath
             the mascot, so clicking the image or the hint both scroll up.
             The button's aria-label conveys the action; the img's alt is
             empty because inside an interactive container the picture
             is the visual handle, not independent content. -->
        <button
          type="button"
          title="Scroll to the top"
          aria-label="Scroll to the top"
          class="group hidden xl:flex xl:flex-col xl:items-center shrink-0 xl:sticky xl:top-4 cursor-pointer
                 hover:brightness-110 focus-visible:outline focus-visible:outline-2
                 focus-visible:outline-emerald-500 focus-visible:outline-offset-4
                 rounded-lg transition"
          @click="scrollToTop"
        >
          <img
            src="/clawdia-reading.webp"
            alt=""
            width="134"
            height="150"
          >
          <!-- "Scroll to the top" hint: fades in below Clawdia whenever the
               reader has left the top of the guide (scrolled down or jumped to
               a section past the first), and fades back out once they're
               returned to the top. aria-hidden because the button's aria-label
               already names the action for assistive tech. -->
          <Transition
            enter-active-class="transition duration-300 ease-out"
            enter-from-class="opacity-0 translate-y-1"
            enter-to-class="opacity-100 translate-y-0"
            leave-active-class="transition duration-200 ease-in"
            leave-from-class="opacity-100 translate-y-0"
            leave-to-class="opacity-0 translate-y-1"
          >
            <span
              v-if="scrolledDown"
              aria-hidden="true"
              class="mt-2 flex items-center gap-1 text-xs font-medium text-emerald-700 dark:text-emerald-400 whitespace-nowrap transition-colors group-hover:text-emerald-500 dark:group-hover:text-emerald-300"
            >
              <ArrowUpIcon
                class="w-3.5 h-3.5 shrink-0"
                aria-hidden="true"
              />
              Scroll to the top
            </span>
          </Transition>
        </button>
      </div>
    </div>
  </div>
</template>
