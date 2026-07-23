<script setup lang="ts">
// Nav icons render at w-6 h-6 (24px) — the design grid of 24/outline. Rendering
// at w-5 h-5 used to downscale 1.5px strokes to 1.25px and smear them through
// anti-aliasing; native size keeps strokes at their intended weight.
import {
  ArrowRightOnRectangleIcon,
  Bars3Icon,
  BellAlertIcon,
  BoltIcon,
  BookOpenIcon,
  ChatBubbleLeftRightIcon,
  ChatBubbleOvalLeftEllipsisIcon,
  CircleStackIcon,
  ClipboardDocumentCheckIcon,
  Cog6ToothIcon,
  ComputerDesktopIcon,
  HomeIcon,
  LinkIcon,
  MapIcon,
  MegaphoneIcon,
  MoonIcon,
  PuzzlePieceIcon,
  RectangleStackIcon,
  Squares2X2Icon,
  SunIcon,
  WrenchScrewdriverIcon,
} from '@heroicons/vue/24/outline'
// BotMessageSquare is Lucide's robot glyph — Heroicons doesn't ship a robot,
// so this is the narrow exception where we drop down to Lucide for a nav icon.
// UsersRound covers the "spawned children" feel of the SubagentRuns admin page
// (JCLAW-271); Heroicons' UserGroup is too tilted toward a "team" gloss to
// read as the parent/child fan-out we want.
import { BotMessageSquare, PanelLeftClose, PanelLeftOpen, UsersRound } from '@lucide/vue'
import { loadTourStatus } from '~/composables/useGuidedTour'
import TourIntroDialog from '~/components/TourIntroDialog.vue'

const { logout, username } = useAuth()
const { themeMode, setTheme } = useTheme()
// Intro dialog is now the single entry point into the tour: first-login
// auto-show (onMounted below) and the sidebar Guided Tour button both
// route through showIntro. Start/skip is resolved inside the dialog.
const {
  showIntro: showTourIntro,
  confirmStart: onTourStart,
  dismissIntro: onTourSkip,
  introOpen: tourIntroOpen,
} = useGuidedTour()
installGuidedTourHooks()

// Auto-show the intro dialog once per install when the user has never
// interacted with the tour (backend flag). Fire-and-forget: if the status
// fetch fails, loadTourStatus returns shouldAutoShow=false so we stay quiet.
onMounted(async () => {
  const status = await loadTourStatus()
  if (status.shouldAutoShow) showTourIntro()
})
const sidebarOpen = ref(true)
const isMac = ref(true)
const paletteOpen = ref(false)

const apiVersion = ref('')
const frameworkVersion = ref('')
const expectedFrameworkVersion = ref('')

// Match-state for the dot next to FRAMEWORK in the sidebar footer:
// green when the running fork matches .play-version, amber when it
// drifts, hidden ('') when the expected version isn't reported (dist
// install, file missing, pre-bump backend).
const frameworkVersionMatch = computed<'match' | 'mismatch' | 'unknown'>(() => {
  if (!frameworkVersion.value || !expectedFrameworkVersion.value) return 'unknown'
  return frameworkVersion.value === expectedFrameworkVersion.value ? 'match' : 'mismatch'
})
const apiOnline = ref(false)

async function checkStatus() {
  try {
    const controller = new AbortController()
    const timeout = setTimeout(() => controller.abort(), 5000)
    const data = await $fetch<{
      status: string
      applicationVersion: string
      frameworkVersion?: string
      expectedFrameworkVersion?: string | null
    }>('/api/status', {
      signal: controller.signal,
    })
    clearTimeout(timeout)
    apiVersion.value = data.applicationVersion
    // frameworkVersion landed in the StatusResponse alongside the app
    // version so the sidebar can render Play's own version under the
    // JClaw line. Optional in the type because pre-bump deployments
    // won't include it. expectedFrameworkVersion is the contents of
    // .play-version — null in dist installs that strip the dotfile.
    frameworkVersion.value = data.frameworkVersion ?? ''
    expectedFrameworkVersion.value = data.expectedFrameworkVersion ?? ''
    apiOnline.value = data.status === 'ok'
  }
  catch {
    apiOnline.value = false
  }
}

let statusInterval: ReturnType<typeof setInterval>

function handleKeydown(e: KeyboardEvent) {
  if ((e.metaKey || e.ctrlKey) && e.key === 'k') {
    e.preventDefault()
    paletteOpen.value = !paletteOpen.value
  }
}

onMounted(() => {
  checkStatus()
  statusInterval = setInterval(checkStatus, 30_000)
  // userAgentData.platform returns "macOS" (lowercase m); navigator.userAgent
  // contains "Macintosh". Normalize before matching so both paths agree.
  const uaData = (navigator as Navigator & { userAgentData?: { platform?: string } }).userAgentData
  isMac.value = (uaData?.platform ?? navigator.userAgent)
    .toLowerCase().includes('mac')
  document.addEventListener('keydown', handleKeydown)
})

onUnmounted(() => {
  clearInterval(statusInterval)
  document.removeEventListener('keydown', handleKeydown)
})

const route = useRoute()
const breadcrumbExtra = useBreadcrumbExtra()

/**
 * When the user clicks a breadcrumb crumb that already matches the current
 * route, NuxtLink skips navigation and the page's local edit state would
 * otherwise stay open. Clearing {@code breadcrumbExtra} lets the page reset
 * its own state (pages watch this ref and collapse their edit UI when it
 * drops to null). No-op when clicking a crumb that actually changes routes —
 * the target page's mount (or our own route watcher) takes care of that.
 */
function onCrumbClick(to: string) {
  if (to === route.path) {
    breadcrumbExtra.value = null
  }
}

interface Crumb { label: string, to?: string }

interface NavItem {
  label: string
  to?: string
  icon: Component
  external?: boolean
  onClick?: () => void
}
interface NavGroup { label?: string, items: NavItem[] }

/**
 * Breadcrumb trail for the current route, rooted at the nav entry that matches
 * the first path segment and descending through nested segments. Pages that
 * edit in-place (no URL change) can append a sub-crumb via
 * {@link useBreadcrumbExtra} — e.g. {@code /agents} + extra "main" renders as
 * {@code Agents > main}. The last crumb (route segment or sub-crumb, whichever
 * comes last) is the current page, rendered plain; preceding crumbs link back.
 */
const crumbs = computed<Crumb[]>(() => {
  const path = route.path
  const allItems = navGroups.flatMap(g => g.items)
  const trail: Crumb[] = []

  if (path === '/') {
    trail.push({ label: 'Dashboard', to: '/' })
  }
  else {
    const segments = path.split('/').filter(Boolean)
    let acc = ''
    segments.forEach((seg) => {
      acc += `/${seg}`
      const match = allItems.find(item => item.to === acc)
      const label = match?.label ?? seg.charAt(0).toUpperCase() + seg.slice(1)
      trail.push({ label, to: acc })
    })
  }

  if (breadcrumbExtra.value) {
    trail.push({ label: breadcrumbExtra.value })
  }

  // The last crumb is the current page — strip its link so it renders plain
  // and carries aria-current.
  const last = trail[trail.length - 1]
  if (last) delete last.to

  return trail
})

const navGroups: NavGroup[] = [
  {
    items: [
      { label: 'Dashboard', to: '/', icon: HomeIcon },
    ],
  },
  {
    label: 'Chat',
    items: [
      { label: 'Chats', to: '/chat', icon: ChatBubbleOvalLeftEllipsisIcon },
      // Prompts Library (JCLAW-813): saved reusable prompts you Run into the
      // composer — a chat-composition aid, so it sits with the chat surfaces.
      { label: 'Prompts', to: '/prompts', icon: RectangleStackIcon },
      { label: 'Channels', to: '/channels', icon: LinkIcon },
      { label: 'Conversations', to: '/conversations', icon: ChatBubbleLeftRightIcon },
    ],
  },
  {
    label: 'Ops',
    items: [
      { label: 'Agents', to: '/agents', icon: BotMessageSquare },
      // Subagents is the operator-facing list of every subagent invocation
      // (RUNNING, COMPLETED, FAILED, KILLED, TIMEOUT) with status pills
      // and a kill action for RUNNING rows. Sits directly below Agents
      // because the two are conceptually paired — Agents configures the
      // workforce, Subagents shows what that workforce is currently doing.
      { label: 'Subagents', to: '/subagents', icon: UsersRound },
      // Apps: operator-hosted mini-apps under public/apps/<slug>/ (SPEC-apps).
      { label: 'Apps', to: '/apps', icon: Squares2X2Icon },
      { label: 'Tasks', to: '/tasks', icon: ClipboardDocumentCheckIcon },
      // Reminders are payloadType=reminder Tasks, surfaced on their own page;
      // placed directly below Tasks since they're the same underlying entity.
      { label: 'Reminders', to: '/reminders', icon: BellAlertIcon },
      { label: 'Skills', to: '/skills', icon: BoltIcon },
      // WrenchScrewdriverIcon (not Cog) — disambiguates Tools from Settings,
      // which was previously broken: both rendered the same gear glyph.
      { label: 'Tools', to: '/tools', icon: WrenchScrewdriverIcon },
      // MCP Servers configures the upstream source of MCP tools that
      // appear on the Tools page; placed adjacent so the "where do my
      // tools come from?" question has both halves of the answer next
      // to each other. Same conceptual category as Skills (runtime-
      // configurable capability extension).
      { label: 'MCP Servers', to: '/mcp-servers', icon: PuzzlePieceIcon },
    ],
  },
  {
    label: 'Admin',
    items: [
      { label: 'Settings', to: '/settings', icon: Cog6ToothIcon },
      // Memories: the operator's window into what each agent has captured
      // (JCLAW-40) — text, category, importance, and source per memory, with
      // manual importance adjustment and delete.
      { label: 'Memories', to: '/memories', icon: CircleStackIcon },
      { label: 'Logs', to: '/logs', icon: Bars3Icon },
    ],
  },
  {
    label: 'Help',
    items: [
      { label: 'Feedback', to: 'https://github.com/tsukhani/jclaw/issues', icon: MegaphoneIcon, external: true },
      { label: 'Guided Tour', icon: MapIcon, onClick: showTourIntro },
      // JCLAW-292: in-app User Guide. Sits below Guided Tour because
      // the tour is a one-time onboarding ritual; the guide is the
      // operator's recurring reference.
      { label: 'User Guide', to: '/guide', icon: BookOpenIcon },
    ],
  },
]
</script>

<template>
  <div class="h-screen bg-surface text-fg-primary flex overflow-hidden">
    <!--
      Tour intro dialog lives at the layout level so both entry paths
      (first-login auto-show via onMounted, and the sidebar "Guided Tour"
      button) can reach it without pages having to re-render it locally.
      Dismissing via Escape or overlay-click counts as skip.
    -->
    <TourIntroDialog
      :open="tourIntroOpen"
      @start="onTourStart"
      @skip="onTourSkip"
      @update:open="tourIntroOpen = $event"
    />

    <!-- Sidebar -->
    <aside
      :class="sidebarOpen ? 'w-60' : 'w-0 -ml-60 lg:w-14 lg:ml-0'"
      class="fixed inset-y-0 left-0 z-30 bg-surface-elevated border-r border-fg-muted/40
             flex flex-col transition-all duration-200 overflow-hidden lg:relative"
    >
      <!-- Logo -->
      <!--
        border-b goes transparent in BOTH modes: the box is what matters
        (preserves the 1px height that aligns the logo row with the
        page-content header bar). Light mode always was transparent;
        dark used to inherit `border-border` because the token was
        near-isoluminant with the surface — that's no longer true since
        the dark `--border` token was lifted for visibility on tables/
        menus, so we pin transparent here to keep the original "no
        visible hairline" intent.
      -->
      <div
        :class="sidebarOpen ? 'justify-between px-3' : 'justify-center px-0'"
        class="h-14 flex items-center border-b border-transparent shrink-0"
      >
        <div
          :class="sidebarOpen ? 'gap-2.5' : 'gap-0'"
          class="flex items-center"
        >
          <img
            src="/clawdia.webp"
            alt="JClaw"
            class="w-9 h-9 rounded-full"
          >
          <div
            v-if="sidebarOpen"
            class="leading-tight"
          >
            <div class="text-[10px] text-fg-muted uppercase tracking-wider font-medium">
              Control
            </div>
            <div class="text-sm font-bold text-emerald-700 dark:text-emerald-400">
              JClaw
            </div>
          </div>
        </div>
        <span
          v-if="sidebarOpen"
          class="inline-flex items-center justify-center
                 rounded-full border border-blue-700 dark:border-blue-400
                 px-[5px] py-[2px]
                 text-[10px] font-medium leading-none tracking-[0.04em]
                 text-blue-700 dark:text-blue-400"
        >BETA</span>
        <button
          v-if="sidebarOpen"
          class="p-1.5 rounded-full border border-fg-muted/40 text-fg-muted hover:text-fg-strong hover:border-fg-muted transition-colors"
          title="Collapse sidebar"
          @click="sidebarOpen = false"
        >
          <PanelLeftClose
            class="w-3.5 h-3.5"
            aria-hidden="true"
          />
        </button>
      </div>

      <!-- Nav -->
      <!--
        `pt-4` (16px) aligns the first nav item's content with each page's
        `<h1>` title. Both sit below an `h-14` bar; the main column's
        `p-6` gives 24px of top padding before the title, while the
        button here adds its own `py-2` (8px) internally. Matching that
        stack is 16 + 8 = 24, so the icon row and the page title share a
        top edge. `pb-2` preserves the original bottom spacing.
      -->
      <nav
        class="flex-1 overflow-y-auto pt-4 pb-2"
        aria-label="Main navigation"
      >
        <div
          v-for="(group, gi) in navGroups"
          :key="gi"
          :role="group.label ? 'group' : undefined"
          :aria-label="group.label"
        >
          <div
            v-if="sidebarOpen && group.label"
            class="px-4 pt-3 pb-1 text-[10px] text-fg-muted uppercase tracking-wider font-medium"
          >
            {{ group.label }}
          </div>
          <template
            v-for="item in group.items"
            :key="item.label"
          >
            <button
              v-if="item.onClick"
              type="button"
              :title="sidebarOpen ? undefined : item.label"
              :class="sidebarOpen ? 'gap-3 px-4' : 'justify-center px-0'"
              class="w-full flex items-center py-2 text-[15px] text-fg-muted
                     hover:text-fg-strong hover:bg-surface-elevated transition-colors
                     bg-transparent border-0 text-left cursor-pointer"
              @click="item.onClick"
            >
              <component
                :is="item.icon"
                class="w-6 h-6 shrink-0"
                aria-hidden="true"
              />
              <span v-if="sidebarOpen">{{ item.label }}</span>
            </button>
            <NuxtLink
              v-else-if="item.to"
              :to="item.to"
              :target="item.external ? '_blank' : undefined"
              :rel="item.external ? 'noopener noreferrer' : undefined"
              :title="sidebarOpen ? undefined : item.label"
              :class="sidebarOpen ? 'gap-3 px-4' : 'justify-center px-0'"
              class="flex items-center py-2 text-[15px] text-fg-muted
                     hover:text-fg-strong hover:bg-surface-elevated transition-colors"
              active-class="text-emerald-700! dark:text-emerald-400! bg-emerald-500/10 border-r-2 border-emerald-600 dark:border-emerald-500"
            >
              <component
                :is="item.icon"
                class="w-6 h-6 shrink-0"
                aria-hidden="true"
              />
              <span v-if="sidebarOpen">{{ item.label }}</span>
            </NuxtLink>
          </template>
          <!--
            Group spacer: keep the 1px transparent border-t so the element
            still occupies its box (prevents my-1.5 margin collapse with
            neighboring blocks and preserves exact 12px vertical rhythm).
            Pinned transparent in both modes — used to inherit border-border
            in dark when that token was near-isoluminant, but the token is
            now visible (lifted for tables/menus contrast), so we have to
            opt out explicitly to keep the spacer invisible.
          -->
          <div
            v-if="gi < navGroups.length - 1"
            class="my-1.5 border-t border-transparent"
          />
        </div>
      </nav>

      <!-- Version & framework version. Two distinct rows separated by
           a divider so each version reads as its own self-contained
           row matching the rest of the sidebar's row pattern (Settings,
           Logs above). Labels share a fixed-width column so the two
           version values left-align; both values use the same color
           token + size + font so neither reads as "more important"
           than the other — they're peer pieces of diagnostic info,
           and the colored dot is the only thing that should call
           attention to one over the other. -->
      <template v-if="sidebarOpen">
        <div class="shrink-0 border-t border-fg-muted/40 px-4 py-2.5">
          <div class="flex items-center justify-between gap-3">
            <div class="flex items-center gap-2 min-w-0">
              <span class="text-xs text-fg-muted font-mono uppercase tracking-wider w-[5.5rem] shrink-0">Version</span>
              <span class="text-sm text-fg-primary font-mono truncate">{{ apiVersion ? `v${apiVersion}` : '...' }}</span>
            </div>
            <span
              class="w-2.5 h-2.5 rounded-full transition-colors shrink-0"
              :class="apiOnline ? 'bg-ok' : 'bg-danger'"
              :title="apiOnline ? 'API online' : 'API offline'"
            />
          </div>
        </div>
        <div
          v-if="frameworkVersion"
          class="shrink-0 border-t border-fg-muted/40 px-4 py-2.5"
        >
          <div class="flex items-center justify-between gap-3">
            <div class="flex items-center gap-2 min-w-0">
              <span class="text-xs text-fg-muted font-mono uppercase tracking-wider w-[5.5rem] shrink-0">Framework</span>
              <span class="text-sm text-fg-primary font-mono truncate">v{{ frameworkVersion }}</span>
            </div>
            <!-- Match dot: green when running fork == .play-version,
                 amber when drift, hidden when the expected version
                 isn't known (dist install, file missing). Tooltip
                 spells out the comparison so an operator hovering an
                 amber dot can read the actual delta without digging.
                 Same size/position as the API-online dot above so the
                 two rows read as visually parallel. -->
            <span
              v-if="frameworkVersionMatch !== 'unknown'"
              class="w-2.5 h-2.5 rounded-full shrink-0"
              :class="frameworkVersionMatch === 'match' ? 'bg-ok' : 'bg-yellow-500'"
              :title="frameworkVersionMatch === 'match'
                ? `Framework matches .play-version (${expectedFrameworkVersion})`
                : `Framework drift: running v${frameworkVersion}, .play-version expects v${expectedFrameworkVersion}`"
            />
          </div>
        </div>
      </template>
      <!-- Collapsed-rail diagnostic dots. Two side-by-side glyphs mirror
           the two expanded rows (Version + Framework) so the operator
           gets the same at-a-glance health read at either width. The
           framework dot drops out when the expected version isn't
           reported (dist install / .play-version absent) — better to
           show one dot than a gray one whose state the operator has
           to decode. -->
      <div
        v-else
        class="shrink-0 border-t border-fg-muted/40 px-0 py-3 flex justify-center items-center gap-1.5"
      >
        <span
          class="w-2.5 h-2.5 rounded-full transition-colors"
          :class="apiOnline ? 'bg-ok' : 'bg-danger'"
          :title="`${apiOnline ? 'API online' : 'API offline'}${apiVersion ? ` — v${apiVersion}` : ''}`"
        />
        <span
          v-if="frameworkVersionMatch !== 'unknown'"
          class="w-2.5 h-2.5 rounded-full"
          :class="frameworkVersionMatch === 'match' ? 'bg-ok' : 'bg-yellow-500'"
          :title="frameworkVersionMatch === 'match'
            ? `Framework matches .play-version (v${expectedFrameworkVersion})`
            : `Framework drift: running v${frameworkVersion}, .play-version expects v${expectedFrameworkVersion}`"
        />
      </div>

      <!-- User -->
      <div
        :class="sidebarOpen ? 'px-4 py-3' : 'px-0 py-2 flex justify-center'"
        class="border-t border-fg-muted/40 shrink-0"
      >
        <div
          v-if="sidebarOpen"
          class="flex items-center justify-between w-full"
        >
          <span class="text-xs text-fg-muted">{{ username || 'admin' }}</span>
          <button
            class="text-xs text-fg-muted hover:text-fg-primary transition-colors"
            @click="logout()"
          >
            Sign out
          </button>
        </div>
        <button
          v-else
          class="p-1.5 text-fg-muted hover:text-fg-strong transition-colors"
          title="Sign out"
          @click="logout()"
        >
          <ArrowRightOnRectangleIcon
            class="w-6 h-6"
            aria-hidden="true"
          />
        </button>
      </div>
    </aside>

    <!-- Main -->
    <div class="flex-1 flex flex-col min-w-0">
      <!-- Top bar -->
      <header class="h-14 flex items-center justify-between px-4 bg-surface-elevated border-b border-fg-muted/40 shrink-0">
        <!-- Left: hamburger + breadcrumb -->
        <div class="flex items-center gap-3">
          <button
            v-if="!sidebarOpen"
            class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
            title="Expand sidebar"
            @click="sidebarOpen = true"
          >
            <PanelLeftOpen
              class="w-5 h-5"
              aria-hidden="true"
            />
          </button>
          <nav
            class="text-sm"
            aria-label="Breadcrumb"
          >
            <NuxtLink
              to="/"
              class="text-fg-muted hover:text-fg-strong transition-colors"
              @click="onCrumbClick('/')"
            >JClaw</NuxtLink>
            <template
              v-for="(crumb, i) in crumbs"
              :key="i"
            >
              <span class="text-fg-muted mx-1.5">›</span>
              <NuxtLink
                v-if="crumb.to"
                :to="crumb.to"
                class="text-fg-muted hover:text-fg-strong transition-colors"
                @click="onCrumbClick(crumb.to)"
              >{{ crumb.label }}</NuxtLink>
              <span
                v-else
                class="text-emerald-700 dark:text-emerald-400 font-medium"
                aria-current="page"
              >{{ crumb.label }}</span>
            </template>
          </nav>
        </div>

        <!-- Right: search + theme toggle -->
        <div class="flex items-center gap-3 min-w-0">
          <button
            class="w-64 min-w-0 flex items-center justify-between pl-3 pr-2.5 py-1.5
                   bg-transparent border border-fg-muted/40 rounded-lg text-sm
                   text-neutral-600 dark:text-neutral-400
                   hover:border-ring transition-colors cursor-pointer"
            @click="paletteOpen = true"
          >
            <span class="truncate">Search...</span>
            <kbd class="max-sm:hidden shrink-0 px-1.5 py-0.5 bg-transparent border border-fg-muted/40 rounded text-[10px] font-mono tracking-widest">{{ isMac ? '⌘ K' : 'Ctrl K' }}</kbd>
          </button>
          <div class="flex items-center gap-0.5 p-0.5 border border-fg-muted/40 rounded-full">
            <button
              :class="themeMode === 'system' ? 'bg-muted text-fg-strong' : 'text-fg-muted hover:text-fg-strong'"
              class="p-1.5 rounded-full transition-colors"
              title="System theme"
              @click="setTheme('system', $event.currentTarget)"
            >
              <ComputerDesktopIcon
                class="w-4 h-4"
                aria-hidden="true"
              />
            </button>
            <button
              :class="themeMode === 'light' ? 'bg-muted text-fg-strong' : 'text-fg-muted hover:text-fg-strong'"
              class="p-1.5 rounded-full transition-colors"
              title="Light theme"
              @click="setTheme('light', $event.currentTarget)"
            >
              <SunIcon
                class="w-4 h-4"
                aria-hidden="true"
              />
            </button>
            <button
              :class="themeMode === 'dark' ? 'bg-muted text-fg-strong' : 'text-fg-muted hover:text-fg-strong'"
              class="p-1.5 rounded-full transition-colors"
              title="Dark theme"
              @click="setTheme('dark', $event.currentTarget)"
            >
              <MoonIcon
                class="w-4 h-4"
                aria-hidden="true"
              />
            </button>
          </div>
        </div>
      </header>

      <!-- Status banners -->
      <StatusBanner
        v-if="!apiOnline"
        message="API is unreachable. Some features may be unavailable."
        variant="error"
        action-text="Retry"
        @action="checkStatus"
      />

      <!-- Content -->
      <!-- scrollbar-gutter:stable reserves the vertical-scrollbar space at all
           times, so the content width doesn't change when a tall view (e.g. an
           expanded Tasks row) makes the scrollbar appear — which otherwise
           reflows full-width tables (columns shift, values wrap). -->
      <main class="flex-1 min-h-0 overflow-auto p-6 relative [scrollbar-gutter:stable]">
        <slot />
      </main>
    </div>

    <!-- Command Palette -->
    <CommandPalette v-model:open="paletteOpen" />

    <!-- Reminder notification toasts (Teleported to body, so the wrapping
         flex layout doesn't affect their fixed-position overlay). -->
    <NotificationBar />
  </div>
</template>
