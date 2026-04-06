<script setup lang="ts">
const { logout, username } = useAuth()
const sidebarOpen = ref(true)

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
      class="fixed inset-y-0 left-0 z-30 bg-neutral-950 border-r border-neutral-800
             flex flex-col transition-all duration-200 overflow-hidden lg:relative lg:ml-0"
    >
      <!-- Logo -->
      <div class="h-12 flex items-center px-4 border-b border-neutral-800 shrink-0">
        <span class="text-sm font-semibold text-white tracking-tight">JClaw</span>
        <span class="ml-2 text-[10px] text-neutral-600 font-mono">v0.1.0</span>
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
            active-class="!text-white bg-neutral-900"
          >
            <span class="w-4 text-center text-xs opacity-50">{{ item.icon }}</span>
            {{ item.label }}
          </NuxtLink>
          <div v-if="gi < navGroups.length - 1" class="my-2 mx-4 border-t border-neutral-800/50" />
        </div>
      </nav>

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
      <header class="h-12 flex items-center px-4 border-b border-neutral-800 shrink-0">
        <button
          @click="sidebarOpen = !sidebarOpen"
          class="p-1 text-neutral-500 hover:text-white transition-colors lg:hidden"
        >
          <span class="text-lg">☰</span>
        </button>
      </header>

      <!-- Content -->
      <main class="flex-1 overflow-auto p-6">
        <slot />
      </main>
    </div>
  </div>
</template>
