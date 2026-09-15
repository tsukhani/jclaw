<script setup lang="ts">
// Palette rows mirror the sidebar's icon per destination (layouts/default.vue)
// so a destination reads the same in both surfaces. Rendered at size-4 rather
// than the sidebar's 24px: palette rows are 40px tall, not 44px.
import {
  Bars3Icon,
  BoltIcon,
  ChatBubbleLeftRightIcon,
  ChatBubbleOvalLeftEllipsisIcon,
  ClipboardDocumentCheckIcon,
  Cog6ToothIcon,
  HomeIcon,
  LinkIcon,
  MoonIcon,
  SunIcon,
  WrenchScrewdriverIcon,
} from '@heroicons/vue/24/outline'
import { BotMessageSquare } from '@lucide/vue'
import {
  CommandDialog,
  CommandEmpty,
  CommandGroup,
  CommandInput,
  CommandItem,
  CommandList,
} from '~/components/ui/command'

const props = defineProps<{ open: boolean }>()
const emit = defineEmits<(e: 'update:open', value: boolean) => void>()

const router = useRouter()

// ── Static navigation items ─────────────────────────────────────────────────
const navItems = [
  { label: 'Dashboard', to: '/', icon: HomeIcon, keywords: 'home overview stats' },
  { label: 'Chats', to: '/chat', icon: ChatBubbleOvalLeftEllipsisIcon, keywords: 'message conversation talk' },
  { label: 'Channels', to: '/channels', icon: LinkIcon, keywords: 'telegram slack whatsapp' },
  { label: 'Conversations', to: '/conversations', icon: ChatBubbleLeftRightIcon, keywords: 'history messages' },
  { label: 'Tasks', to: '/tasks', icon: ClipboardDocumentCheckIcon, keywords: 'jobs schedule cron' },
  { label: 'Agents', to: '/agents', icon: BotMessageSquare, keywords: 'bot ai assistant' },
  { label: 'Skills', to: '/skills', icon: BoltIcon, keywords: 'capability plugin' },
  { label: 'Tools', to: '/tools', icon: WrenchScrewdriverIcon, keywords: 'function action' },
  { label: 'Settings', to: '/settings', icon: Cog6ToothIcon, keywords: 'config provider api' },
  { label: 'Logs', to: '/logs', icon: Bars3Icon, keywords: 'events audit activity' },
]

// ── Dynamic data (fetched on open) ──────────────────────────────────────────
interface Agent {
  id: number
  name: string
  modelProvider: string
  modelId: string
  enabled: boolean
}

interface Conversation {
  id: number
  agentName: string
  channelType: string
  preview: string
  updatedAt: string
}

const agents = ref<Agent[]>([])
const conversations = ref<Conversation[]>([])
const isDark = ref(false)

watch(() => props.open, async (isOpen) => {
  if (!isOpen) return
  isDark.value = document.documentElement.classList.contains('dark')
  try {
    const [agentData, convoData] = await Promise.all([
      $fetch<Agent[]>('/api/agents'),
      $fetch<Conversation[]>('/api/conversations?limit=10'),
    ])
    agents.value = agentData ?? []
    conversations.value = convoData ?? []
  }
  catch {
    // Palette still works with static items if API is down
  }
})

// ── Actions ─────────────────────────────────────────────────────────────────
const { setTheme } = useTheme()

function close() {
  emit('update:open', false)
}

function navigateTo(path: string) {
  close()
  router.push(path)
}

function openAgent(name: string) {
  close()
  router.push(`/agents/${name}`)
}

function openConversation(id: number) {
  close()
  router.push(`/chat?conversation=${id}`)
}

function toggleTheme() {
  const html = document.documentElement
  const dark = html.classList.contains('dark')
  setTheme(dark ? 'light' : 'dark')
  isDark.value = !dark
  close()
}
</script>

<template>
  <CommandDialog
    :open="open"
    :show-close-button="false"
    class="top-[15vh] translate-y-0 shadow-2xl sm:max-w-[640px]
           **:data-[slot=command-group-heading]:text-[11px]
           **:data-[slot=command-group-heading]:font-semibold
           **:data-[slot=command-group-heading]:tracking-wider
           **:data-[slot=command-group-heading]:uppercase"
    @update:open="emit('update:open', $event)"
  >
    <CommandInput placeholder="Search pages, agents, conversations..." />
    <CommandList class="max-h-[min(400px,calc(85dvh_-_7rem))] p-1">
      <CommandEmpty>No results found.</CommandEmpty>

      <CommandGroup heading="Navigation">
        <CommandItem
          v-for="item in navItems"
          :key="item.to"
          :value="`${item.label} ${item.keywords}`"
          class="gap-3 px-3 py-2.5"
          @select="navigateTo(item.to)"
        >
          <component
            :is="item.icon"
            class="size-4"
          />
          <span>{{ item.label }}</span>
        </CommandItem>
      </CommandGroup>

      <CommandGroup
        v-if="agents.length"
        heading="Agents"
      >
        <CommandItem
          v-for="agent in agents"
          :key="agent.id"
          :value="`agent ${agent.name} ${agent.modelProvider} ${agent.modelId}`"
          class="gap-3 px-3 py-2.5"
          @select="openAgent(agent.name)"
        >
          <BotMessageSquare class="size-4" />
          <span class="truncate">{{ agent.name }}</span>
          <span class="ml-auto shrink-0 pl-3 text-xs text-fg-muted">{{ agent.modelId }}</span>
        </CommandItem>
      </CommandGroup>

      <CommandGroup
        v-if="conversations.length"
        heading="Recent Conversations"
      >
        <CommandItem
          v-for="convo in conversations"
          :key="convo.id"
          :value="`conversation ${convo.agentName} ${convo.channelType} ${convo.preview}`"
          class="gap-3 px-3 py-2.5"
          @select="openConversation(convo.id)"
        >
          <ChatBubbleLeftRightIcon class="size-4" />
          <span class="truncate">{{ convo.preview || '(empty)' }}</span>
          <span class="ml-auto shrink-0 pl-3 text-xs text-fg-muted">{{ convo.agentName }}</span>
        </CommandItem>
      </CommandGroup>

      <CommandGroup heading="Actions">
        <CommandItem
          value="toggle theme light dark mode"
          class="gap-3 px-3 py-2.5"
          @select="toggleTheme"
        >
          <component
            :is="isDark ? SunIcon : MoonIcon"
            class="size-4"
          />
          <span>Toggle theme</span>
        </CommandItem>
      </CommandGroup>
    </CommandList>

    <!-- Hints only; the listbox already conveys arrow/enter/escape to AT. -->
    <div
      aria-hidden="true"
      class="flex shrink-0 items-center gap-4 border-t px-3 py-2 text-xs text-fg-muted"
    >
      <span class="flex items-center gap-1.5">
        <kbd class="inline-flex size-5 items-center justify-center rounded border bg-muted font-sans text-xs">↑</kbd>
        <kbd class="inline-flex size-5 items-center justify-center rounded border bg-muted font-sans text-xs">↓</kbd>
        navigate
      </span>
      <span class="flex items-center gap-1.5">
        <kbd class="inline-flex size-5 items-center justify-center rounded border bg-muted font-sans text-xs">↵</kbd>
        open
      </span>
      <span class="ml-auto flex items-center gap-1.5">
        <kbd class="inline-flex h-5 items-center justify-center rounded border bg-muted px-1.5 font-sans text-xs">esc</kbd>
        close
      </span>
    </div>
  </CommandDialog>
</template>
