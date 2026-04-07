<script setup lang="ts">
const { logout, username } = useAuth()
const { themeMode, setTheme } = useTheme()
const sidebarOpen = ref(true)
const searchInput = ref<HTMLInputElement | null>(null)
const isMac = ref(true)

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
      { label: 'Dashboard', to: '/', icon: '◉' },
      { label: 'Chat', to: '/chat', icon: '◈' },
    ]
  },
  {
    items: [
      { label: 'Channels', to: '/channels', icon: '⊞' },
      { label: 'Conversations', to: '/conversations', icon: '≡' },
    ]
  },
  {
    items: [
      { label: 'Tasks', to: '/tasks', icon: '⊡' },
      { label: 'Agents', to: '/agents', icon: '⊙' },
      { label: 'Skills', to: '/skills', icon: '⊕' },
    ]
  },
  {
    items: [
      { label: 'Settings', to: '/settings', icon: '⊘' },
      { label: 'Logs', to: '/logs', icon: '⊟' },
    ]
  }
]
</script>

<template>
  <div class="min-h-screen bg-neutral-950 text-neutral-300 flex">
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
            class="flex items-center gap-2.5 px-4 py-1.5 text-sm text-neutral-400
                   hover:text-white hover:bg-neutral-900 transition-colors"
            active-class="!text-emerald-400 bg-emerald-500/10 border-r-2 border-emerald-500"
          >
            <span class="w-4 text-center text-xs opacity-50">{{ item.icon }}</span>
            {{ item.label }}
          </NuxtLink>
          <div v-if="gi < navGroups.length - 1" class="my-2 mx-4 border-t border-neutral-800/50" />
        </div>
      </nav>

      <!-- Version -->
      <div class="px-4 py-2.5 shrink-0 border-t border-neutral-800">
        <div class="flex items-center justify-between bg-neutral-900 border border-neutral-600/40 rounded-lg px-3 py-2">
          <div class="flex items-center gap-2">
            <span class="text-xs text-neutral-500 font-mono uppercase tracking-wider">Version</span>
            <span class="text-sm text-neutral-300 font-mono">v0.2.0</span>
          </div>
          <span class="w-2.5 h-2.5 rounded-full bg-emerald-500" />
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
      <header class="h-12 flex items-center justify-between px-4 bg-neutral-900/50 border-b border-neutral-800 shrink-0">
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
