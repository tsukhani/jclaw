<script setup lang="ts">
const { logout, username } = useAuth()
const { themeMode, setTheme } = useTheme()
const sidebarOpen = ref(true)
const isMac = ref(true)
const paletteOpen = ref(false)

const apiVersion = ref('')
const apiOnline = ref(false)

async function checkStatus() {
  try {
    const controller = new AbortController()
    const timeout = setTimeout(() => controller.abort(), 5000)
    const data = await $fetch<{ status: string, version: string }>('/api/status', {
      signal: controller.signal,
    })
    clearTimeout(timeout)
    apiVersion.value = data.version
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

const navGroups = [
  {
    label: 'Core',
    items: [
      { label: 'Dashboard', to: '/', icon: 'svg-dashboard' },
    ],
  },
  {
    label: 'Chat',
    items: [
      { label: 'Chat', to: '/chat', icon: 'svg-chat' },
      { label: 'Conversations', to: '/conversations', icon: 'svg-conversations' },
    ],
  },
  {
    label: 'Ops',
    items: [
      { label: 'Agents', to: '/agents', icon: 'svg-agents' },
      { label: 'Channels', to: '/channels', icon: 'svg-channels' },
      { label: 'Tasks', to: '/tasks', icon: 'svg-tasks' },
      { label: 'Skills', to: '/skills', icon: 'svg-skills' },
      { label: 'Tools', to: '/tools', icon: 'svg-tools' },
    ],
  },
  {
    label: 'Admin',
    items: [
      { label: 'Settings', to: '/settings', icon: 'svg-settings' },
      { label: 'Logs', to: '/logs', icon: 'svg-logs' },
    ],
  },
]
</script>

<template>
  <div class="h-screen bg-surface text-fg-primary flex overflow-hidden">
    <!-- Sidebar -->
    <aside
      :class="sidebarOpen ? 'w-52' : 'w-0 -ml-52'"
      class="fixed inset-y-0 left-0 z-30 bg-surface-elevated border-r border-border
             flex flex-col transition-all duration-200 overflow-hidden lg:relative lg:ml-0"
    >
      <!-- Logo -->
      <div class="h-14 flex items-center justify-between px-3 border-b border-border shrink-0">
        <div class="flex items-center gap-2.5">
          <img
            src="/avatar.png"
            alt="JClaw"
            class="w-9 h-9 rounded-full"
          >
          <div class="leading-tight">
            <div class="text-[10px] text-fg-muted uppercase tracking-wider font-medium">
              Control
            </div>
            <div
              class="text-sm font-semibold tracking-widest"
              aria-label="JClaw"
            >
              <span
                class="text-emerald-700 dark:text-emerald-400"
                aria-hidden="true"
              >J</span><span
                class="text-red-600 dark:text-red-500"
                aria-hidden="true"
              >Claw</span>
            </div>
          </div>
        </div>
        <button
          class="p-1.5 rounded-full border border-border text-fg-muted hover:text-fg-strong hover:border-fg-muted transition-colors"
          title="Collapse sidebar"
          @click="sidebarOpen = false"
        >
          <svg
            class="w-3.5 h-3.5"
            fill="none"
            stroke="currentColor"
            viewBox="0 0 24 24"
          ><path
            stroke-linecap="round"
            stroke-linejoin="round"
            stroke-width="2"
            d="M11 19l-7-7 7-7m8 14l-7-7 7-7"
          /></svg>
        </button>
      </div>

      <!-- Nav -->
      <nav
        class="flex-1 overflow-y-auto py-2"
        aria-label="Main navigation"
      >
        <div
          v-for="(group, gi) in navGroups"
          :key="gi"
          role="group"
          :aria-label="group.label"
        >
          <div class="px-4 pt-3 pb-1 text-[10px] text-fg-muted uppercase tracking-wider font-medium">
            {{ group.label }}
          </div>
          <NuxtLink
            v-for="item in group.items"
            :key="item.to"
            :to="item.to"
            class="flex items-center gap-3 px-4 py-2 text-[15px] text-fg-muted
                   hover:text-fg-strong hover:bg-surface-elevated transition-colors"
            active-class="text-emerald-700! dark:text-emerald-400! bg-emerald-500/10 border-r-2 border-emerald-600 dark:border-emerald-500"
          >
            <svg
              class="w-5 h-5 opacity-60 shrink-0"
              fill="none"
              stroke="currentColor"
              viewBox="0 0 24 24"
            >
              <!-- Dashboard -->
              <path
                v-if="item.icon === 'svg-dashboard'"
                stroke-linecap="round"
                stroke-linejoin="round"
                stroke-width="1.5"
                d="M3 12l2-2m0 0l7-7 7 7M5 10v10a1 1 0 001 1h3m10-11l2 2m-2-2v10a1 1 0 01-1 1h-3m-4 0a1 1 0 01-1-1v-4a1 1 0 011-1h2a1 1 0 011 1v4a1 1 0 01-1 1"
              />
              <!-- Chat -->
              <path
                v-if="item.icon === 'svg-chat'"
                stroke-linecap="round"
                stroke-linejoin="round"
                stroke-width="1.5"
                d="M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.418-4.03 8-9 8a9.863 9.863 0 01-4.255-.949L3 20l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.418 4.03-8 9-8s9 3.582 9 8z"
              />
              <!-- Channels -->
              <path
                v-if="item.icon === 'svg-channels'"
                stroke-linecap="round"
                stroke-linejoin="round"
                stroke-width="1.5"
                d="M13.828 10.172a4 4 0 00-5.656 0l-4 4a4 4 0 105.656 5.656l1.102-1.101m-.758-4.899a4 4 0 005.656 0l4-4a4 4 0 00-5.656-5.656l-1.1 1.1"
              />
              <!-- Conversations -->
              <path
                v-if="item.icon === 'svg-conversations'"
                stroke-linecap="round"
                stroke-linejoin="round"
                stroke-width="1.5"
                d="M17 8h2a2 2 0 012 2v6a2 2 0 01-2 2h-2v4l-4-4H9a2 2 0 01-2-2v-1m0-3V4a2 2 0 012-2h8a2 2 0 012 2v4a2 2 0 01-2 2H9l-4 4V8z"
              />
              <!-- Tasks -->
              <path
                v-if="item.icon === 'svg-tasks'"
                stroke-linecap="round"
                stroke-linejoin="round"
                stroke-width="1.5"
                d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2m-6 9l2 2 4-4"
              />
              <!-- Agents -->
              <path
                v-if="item.icon === 'svg-agents'"
                stroke-linecap="round"
                stroke-linejoin="round"
                stroke-width="1.5"
                d="M9.75 3.104v5.714a2.25 2.25 0 01-.659 1.591L5 14.5M9.75 3.104c-.251.023-.501.05-.75.082m.75-.082a24.301 24.301 0 014.5 0m0 0v5.714a2.25 2.25 0 00.659 1.591L19 14.5M14.25 3.104c.251.023.501.05.75.082M19 14.5l-1.5 4.5H6.5L5 14.5m14 0H5"
              />
              <!-- Skills -->
              <path
                v-if="item.icon === 'svg-skills'"
                stroke-linecap="round"
                stroke-linejoin="round"
                stroke-width="1.5"
                d="M13 10V3L4 14h7v7l9-11h-7z"
              />
              <!-- Tools -->
              <path
                v-if="item.icon === 'svg-tools'"
                stroke-linecap="round"
                stroke-linejoin="round"
                stroke-width="1.5"
                d="M10.343 3.94c.09-.542.56-.94 1.11-.94h1.093c.55 0 1.02.398 1.11.94l.149.894c.07.424.384.764.78.93.398.164.855.142 1.205-.108l.737-.527a1.125 1.125 0 011.45.12l.773.774c.39.389.44 1.002.12 1.45l-.527.737c-.25.35-.272.806-.107 1.204.165.397.505.71.93.78l.893.15c.543.09.94.56.94 1.109v1.094c0 .55-.397 1.02-.94 1.11l-.893.149c-.425.07-.765.383-.93.78-.165.398-.143.854.107 1.204l.527.738c.32.447.269 1.06-.12 1.45l-.774.773a1.125 1.125 0 01-1.449.12l-.738-.527c-.35-.25-.806-.272-1.203-.107-.397.165-.71.505-.781.929l-.149.894c-.09.542-.56.94-1.11.94h-1.094c-.55 0-1.019-.398-1.11-.94l-.148-.894c-.071-.424-.384-.764-.781-.93-.398-.164-.854-.142-1.204.108l-.738.527c-.447.32-1.06.269-1.45-.12l-.773-.774a1.125 1.125 0 01-.12-1.45l.527-.737c.25-.35.273-.806.108-1.204-.165-.397-.505-.71-.93-.78l-.894-.15c-.542-.09-.94-.56-.94-1.109v-1.094c0-.55.398-1.02.94-1.11l.894-.149c.424-.07.765-.383.93-.78.165-.398.143-.854-.108-1.204l-.526-.738a1.125 1.125 0 01.12-1.45l.773-.773a1.125 1.125 0 011.45-.12l.737.527c.35.25.807.272 1.204.107.397-.165.71-.505.78-.929l.15-.894zM15 12a3 3 0 11-6 0 3 3 0 016 0z"
              />
              <!-- Settings -->
              <path
                v-if="item.icon === 'svg-settings'"
                stroke-linecap="round"
                stroke-linejoin="round"
                stroke-width="1.5"
                d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.066 2.573c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.573 1.066c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.066-2.573c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065zM15 12a3 3 0 11-6 0 3 3 0 016 0z"
              />
              <!-- Logs -->
              <path
                v-if="item.icon === 'svg-logs'"
                stroke-linecap="round"
                stroke-linejoin="round"
                stroke-width="1.5"
                d="M4 6h16M4 10h16M4 14h16M4 18h16"
              />
            </svg>
            {{ item.label }}
          </NuxtLink>
          <div
            v-if="gi < navGroups.length - 1"
            class="my-1.5 border-t border-border"
          />
        </div>
      </nav>

      <!-- Version & API Status -->
      <div class="px-4 py-2.5 shrink-0 border-t border-border">
        <div class="flex items-center justify-between">
          <div class="flex items-center gap-2">
            <span class="text-xs text-fg-muted font-mono uppercase tracking-wider">Version</span>
            <span class="text-sm text-fg-primary font-mono">{{ apiVersion ? `v${apiVersion}` : '...' }}</span>
          </div>
          <span
            class="w-2.5 h-2.5 rounded-full transition-colors"
            :class="apiOnline ? 'bg-ok' : 'bg-danger'"
            :title="apiOnline ? 'API online' : 'API offline'"
          />
        </div>
      </div>

      <!-- User -->
      <div class="border-t border-border px-4 py-3 shrink-0">
        <div class="flex items-center justify-between">
          <span class="text-xs text-fg-muted">{{ username || 'admin' }}</span>
          <button
            class="text-xs text-fg-muted hover:text-fg-primary transition-colors"
            @click="logout()"
          >
            Sign out
          </button>
        </div>
      </div>
    </aside>

    <!-- Main -->
    <div class="flex-1 flex flex-col min-w-0">
      <!-- Top bar -->
      <header class="h-14 flex items-center justify-between px-4 bg-surface-elevated border-b border-border shrink-0">
        <!-- Left: hamburger + breadcrumb -->
        <div class="flex items-center gap-3">
          <button
            v-if="!sidebarOpen"
            class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
            @click="sidebarOpen = true"
          >
            <span class="text-lg">☰</span>
          </button>
          <nav
            class="text-sm"
            aria-label="Breadcrumb"
          >
            <NuxtLink
              to="/"
              class="text-fg-muted hover:text-fg-strong transition-colors"
              @click="onCrumbClick('/')"
            >
              JClaw
            </NuxtLink>
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
              >
                {{ crumb.label }}
              </NuxtLink>
              <span
                v-else
                class="text-emerald-700 dark:text-emerald-400 font-medium"
                aria-current="page"
              >{{ crumb.label }}</span>
            </template>
          </nav>
        </div>

        <!-- Right: search + theme toggle -->
        <div class="flex items-center gap-3">
          <button
            class="w-64 flex items-center justify-between pl-3 pr-2.5 py-1.5 bg-muted border border-input rounded-lg text-sm text-fg-muted
                   hover:border-ring transition-colors cursor-pointer"
            @click="paletteOpen = true"
          >
            <span>Search...</span>
            <kbd class="px-1.5 py-0.5 bg-surface border border-input rounded text-[10px] font-mono tracking-widest">{{ isMac ? '⌘ K' : 'Ctrl K' }}</kbd>
          </button>
          <div class="flex items-center gap-0.5 p-0.5 border border-fg-muted/40 rounded-full">
            <button
              :class="themeMode === 'system' ? 'bg-muted text-fg-strong' : 'text-fg-muted hover:text-fg-strong'"
              class="p-1.5 rounded-full transition-colors"
              title="System theme"
              @click="setTheme('system')"
            >
              <svg
                class="w-4 h-4"
                fill="none"
                stroke="currentColor"
                viewBox="0 0 24 24"
              ><path
                stroke-linecap="round"
                stroke-linejoin="round"
                stroke-width="2"
                d="M9.75 17L9 20l-1 1h8l-1-1-.75-3M3 13h18M5 17h14a2 2 0 002-2V5a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z"
              /></svg>
            </button>
            <button
              :class="themeMode === 'light' ? 'bg-muted text-fg-strong' : 'text-fg-muted hover:text-fg-strong'"
              class="p-1.5 rounded-full transition-colors"
              title="Light theme"
              @click="setTheme('light')"
            >
              <svg
                class="w-4 h-4"
                fill="none"
                stroke="currentColor"
                viewBox="0 0 24 24"
              ><path
                stroke-linecap="round"
                stroke-linejoin="round"
                stroke-width="2"
                d="M12 3v1m0 16v1m9-9h-1M4 12H3m15.364 6.364l-.707-.707M6.343 6.343l-.707-.707m12.728 0l-.707.707M6.343 17.657l-.707.707M16 12a4 4 0 11-8 0 4 4 0 018 0z"
              /></svg>
            </button>
            <button
              :class="themeMode === 'dark' ? 'bg-muted text-fg-strong' : 'text-fg-muted hover:text-fg-strong'"
              class="p-1.5 rounded-full transition-colors"
              title="Dark theme"
              @click="setTheme('dark')"
            >
              <svg
                class="w-4 h-4"
                fill="none"
                stroke="currentColor"
                viewBox="0 0 24 24"
              ><path
                stroke-linecap="round"
                stroke-linejoin="round"
                stroke-width="2"
                d="M20.354 15.354A9 9 0 018.646 3.646 9.003 9.003 0 0012 21a9.003 9.003 0 008.354-5.646z"
              /></svg>
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
      <main class="flex-1 min-h-0 overflow-auto p-6 relative">
        <slot />
      </main>
    </div>

    <!-- Command Palette -->
    <CommandPalette v-model:open="paletteOpen" />
  </div>
</template>
