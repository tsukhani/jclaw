<script setup lang="ts">
const { logout, username } = useAuth()
const { themeMode, setTheme } = useTheme()
const sidebarOpen = ref(true)
const searchInput = ref<HTMLInputElement | null>(null)
const isMac = ref(true)

const apiVersion = ref('')
const apiOnline = ref(false)

async function checkStatus() {
  try {
    const controller = new AbortController()
    const timeout = setTimeout(() => controller.abort(), 5000)
    const data = await $fetch<{ status: string; version: string }>('/api/status', {
      signal: controller.signal
    })
    clearTimeout(timeout)
    apiVersion.value = data.version
    apiOnline.value = data.status === 'ok'
  } catch {
    apiOnline.value = false
  }
}

onMounted(() => {
  checkStatus()
  setInterval(checkStatus, 30_000)
})

onMounted(() => {
  isMac.value = navigator.platform.includes('Mac') || navigator.userAgent.includes('Mac')
})

onMounted(() => {
  document.addEventListener('keydown', (e) => {
    if ((e.metaKey || e.ctrlKey) && e.key === 'k') {
      e.preventDefault()
      searchInput.value?.focus()
    }
    if (e.key === 'Escape' && document.activeElement === searchInput.value) {
      searchInput.value?.blur()
    }
  })
})

const route = useRoute()

const currentPageLabel = computed(() => {
  const path = route.path
  for (const group of navGroups) {
    for (const item of group.items) {
      if (item.to === path) return item.label
    }
  }
  return 'Dashboard'
})

const navGroups = [
  {
    items: [
      { label: 'Dashboard', to: '/', icon: 'svg-dashboard' },
    ]
  },
  {
    items: [
      { label: 'Chat', to: '/chat', icon: 'svg-chat' },
      { label: 'Channels', to: '/channels', icon: 'svg-channels' },
      { label: 'Conversations', to: '/conversations', icon: 'svg-conversations' },
    ]
  },
  {
    items: [
      { label: 'Tasks', to: '/tasks', icon: 'svg-tasks' },
      { label: 'Agents', to: '/agents', icon: 'svg-agents' },
      { label: 'Skills', to: '/skills', icon: 'svg-skills' },
    ]
  },
  {
    items: [
      { label: 'Settings', to: '/settings', icon: 'svg-settings' },
      { label: 'Logs', to: '/logs', icon: 'svg-logs' },
    ]
  }
]
</script>

<template>
  <div class="h-screen bg-neutral-950 text-neutral-300 flex overflow-hidden">
    <!-- Sidebar -->
    <aside
      :class="sidebarOpen ? 'w-52' : 'w-0 -ml-52'"
      class="fixed inset-y-0 left-0 z-30 bg-neutral-900/50 border-r border-neutral-800
             flex flex-col transition-all duration-200 overflow-hidden lg:relative lg:ml-0"
    >
      <!-- Logo -->
      <div class="h-14 flex items-center justify-between px-3 border-b border-neutral-800 shrink-0">
        <div class="flex items-center gap-2.5">
          <img src="/avatar.png" alt="JClaw" class="w-9 h-9 rounded-full" />
          <div class="leading-tight">
            <div class="text-[10px] text-neutral-500 uppercase tracking-wider font-medium">Control</div>
            <div class="text-sm font-semibold text-emerald-400">JClaw</div>
          </div>
        </div>
        <button
          @click="sidebarOpen = false"
          class="p-1.5 rounded-full border border-neutral-700 text-neutral-500 hover:text-white hover:border-neutral-500 transition-colors"
          title="Collapse sidebar"
        >
          <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M11 19l-7-7 7-7m8 14l-7-7 7-7" /></svg>
        </button>
      </div>

      <!-- Nav -->
      <nav class="flex-1 overflow-y-auto py-2">
        <div v-for="(group, gi) in navGroups" :key="gi">
          <NuxtLink
            v-for="item in group.items"
            :key="item.to"
            :to="item.to"
            class="flex items-center gap-3 px-4 py-2.5 text-[15px] text-neutral-400
                   hover:text-white hover:bg-neutral-900 transition-colors"
            active-class="!text-emerald-400 bg-emerald-500/10 border-r-2 border-emerald-500"
          >
            <svg class="w-5 h-5 opacity-60 shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <!-- Dashboard -->
              <path v-if="item.icon === 'svg-dashboard'" stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M3 12l2-2m0 0l7-7 7 7M5 10v10a1 1 0 001 1h3m10-11l2 2m-2-2v10a1 1 0 01-1 1h-3m-4 0a1 1 0 01-1-1v-4a1 1 0 011-1h2a1 1 0 011 1v4a1 1 0 01-1 1" />
              <!-- Chat -->
              <path v-if="item.icon === 'svg-chat'" stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.418-4.03 8-9 8a9.863 9.863 0 01-4.255-.949L3 20l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.418 4.03-8 9-8s9 3.582 9 8z" />
              <!-- Channels -->
              <path v-if="item.icon === 'svg-channels'" stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M13.828 10.172a4 4 0 00-5.656 0l-4 4a4 4 0 105.656 5.656l1.102-1.101m-.758-4.899a4 4 0 005.656 0l4-4a4 4 0 00-5.656-5.656l-1.1 1.1" />
              <!-- Conversations -->
              <path v-if="item.icon === 'svg-conversations'" stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M17 8h2a2 2 0 012 2v6a2 2 0 01-2 2h-2v4l-4-4H9a2 2 0 01-2-2v-1m0-3V4a2 2 0 012-2h8a2 2 0 012 2v4a2 2 0 01-2 2H9l-4 4V8z" />
              <!-- Tasks -->
              <path v-if="item.icon === 'svg-tasks'" stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2m-6 9l2 2 4-4" />
              <!-- Agents -->
              <path v-if="item.icon === 'svg-agents'" stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M9.75 3.104v5.714a2.25 2.25 0 01-.659 1.591L5 14.5M9.75 3.104c-.251.023-.501.05-.75.082m.75-.082a24.301 24.301 0 014.5 0m0 0v5.714a2.25 2.25 0 00.659 1.591L19 14.5M14.25 3.104c.251.023.501.05.75.082M19 14.5l-1.5 4.5H6.5L5 14.5m14 0H5" />
              <!-- Skills -->
              <path v-if="item.icon === 'svg-skills'" stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M13 10V3L4 14h7v7l9-11h-7z" />
              <!-- Settings -->
              <path v-if="item.icon === 'svg-settings'" stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.066 2.573c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.573 1.066c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.066-2.573c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065zM15 12a3 3 0 11-6 0 3 3 0 016 0z" />
              <!-- Logs -->
              <path v-if="item.icon === 'svg-logs'" stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M4 6h16M4 10h16M4 14h16M4 18h16" />
            </svg>
            {{ item.label }}
          </NuxtLink>
          <div v-if="gi < navGroups.length - 1" class="my-2 mx-4 border-t border-neutral-800/50" />
        </div>
      </nav>

      <!-- Version & API Status -->
      <div class="px-4 py-2.5 shrink-0 border-t border-neutral-800">
        <div class="flex items-center justify-between bg-neutral-900 border border-neutral-600/40 rounded-lg px-3 py-2">
          <div class="flex items-center gap-2">
            <span class="text-xs text-neutral-500 font-mono uppercase tracking-wider">Version</span>
            <span class="text-sm text-neutral-300 font-mono">{{ apiVersion ? `v${apiVersion}` : '...' }}</span>
          </div>
          <span
            class="w-2.5 h-2.5 rounded-full transition-colors"
            :class="apiOnline ? 'bg-emerald-500' : 'bg-red-500'"
            :title="apiOnline ? 'API online' : 'API offline'"
          />
        </div>
      </div>

      <!-- User -->
      <div class="border-t border-neutral-800 px-4 py-3 shrink-0">
        <div class="flex items-center justify-between">
          <span class="text-xs text-neutral-500">{{ username || 'admin' }}</span>
          <button
            @click="logout()"
            class="text-xs text-neutral-600 hover:text-neutral-400 transition-colors"
          >
            Sign out
          </button>
        </div>
      </div>
    </aside>

    <!-- Main -->
    <div class="flex-1 flex flex-col min-w-0">
      <!-- Top bar -->
      <header class="h-14 flex items-center justify-between px-4 bg-neutral-900/50 border-b border-neutral-800 shrink-0">
        <!-- Left: hamburger + breadcrumb -->
        <div class="flex items-center gap-3">
          <button
            v-if="!sidebarOpen"
            @click="sidebarOpen = true"
            class="p-1 text-neutral-500 hover:text-white transition-colors"
          >
            <span class="text-lg">☰</span>
          </button>
          <nav class="text-sm">
            <span class="text-neutral-500">JClaw</span>
            <span class="text-neutral-600 mx-1.5">›</span>
            <span class="text-emerald-400 font-medium">{{ currentPageLabel }}</span>
          </nav>
        </div>

        <!-- Right: search + theme toggle -->
        <div class="flex items-center gap-3">
          <div class="relative">
            <input
              ref="searchInput"
              type="text"
              placeholder="Search"
              class="w-64 pl-3 pr-16 py-1.5 bg-neutral-800 border border-neutral-700 rounded-lg text-sm text-white
                     placeholder-neutral-500 focus:outline-none focus:border-neutral-600 transition-colors"
            />
            <kbd class="absolute right-2.5 top-1/2 -translate-y-1/2 px-1.5 py-0.5 bg-neutral-700 border border-neutral-600 rounded text-[10px] text-neutral-400 font-mono tracking-widest">{{ isMac ? '⌘ K' : 'Ctrl K' }}</kbd>
          </div>
          <div class="flex items-center border border-neutral-700 rounded-lg overflow-hidden">
            <button
              @click="setTheme('system')"
              :class="themeMode === 'system' ? 'bg-neutral-700 text-white' : 'text-neutral-500 hover:text-white'"
              class="p-1.5 transition-colors"
              title="System theme"
            >
              <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9.75 17L9 20l-1 1h8l-1-1-.75-3M3 13h18M5 17h14a2 2 0 002-2V5a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z" /></svg>
            </button>
            <button
              @click="setTheme('light')"
              :class="themeMode === 'light' ? 'bg-neutral-700 text-white' : 'text-neutral-500 hover:text-white'"
              class="p-1.5 transition-colors"
              title="Light theme"
            >
              <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 3v1m0 16v1m9-9h-1M4 12H3m15.364 6.364l-.707-.707M6.343 6.343l-.707-.707m12.728 0l-.707.707M6.343 17.657l-.707.707M16 12a4 4 0 11-8 0 4 4 0 018 0z" /></svg>
            </button>
            <button
              @click="setTheme('dark')"
              :class="themeMode === 'dark' ? 'bg-neutral-700 text-white' : 'text-neutral-500 hover:text-white'"
              class="p-1.5 transition-colors"
              title="Dark theme"
            >
              <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M20.354 15.354A9 9 0 018.646 3.646 9.003 9.003 0 0012 21a9.003 9.003 0 008.354-5.646z" /></svg>
            </button>
          </div>
        </div>
      </header>

      <!-- Content -->
      <main class="flex-1 min-h-0 overflow-auto p-6">
        <slot />
      </main>
    </div>
  </div>
</template>
