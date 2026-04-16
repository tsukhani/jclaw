<script setup lang="ts">
import {
  CommandDialog,
  CommandEmpty,
  CommandGroup,
  CommandInput,
  CommandItem,
  CommandList,
  CommandSeparator,
} from '~/components/ui/command'

const props = defineProps<{ open: boolean }>()
const emit = defineEmits<{ (e: 'update:open', value: boolean): void }>()

const router = useRouter()

// ── Static navigation items ─────────────────────────────────────────────────
const navItems = [
  { label: 'Dashboard', to: '/', keywords: 'home overview stats' },
  { label: 'Chat', to: '/chat', keywords: 'message conversation talk' },
  { label: 'Channels', to: '/channels', keywords: 'telegram slack whatsapp' },
  { label: 'Conversations', to: '/conversations', keywords: 'history messages' },
  { label: 'Tasks', to: '/tasks', keywords: 'jobs schedule cron' },
  { label: 'Agents', to: '/agents', keywords: 'bot ai assistant' },
  { label: 'Skills', to: '/skills', keywords: 'capability plugin' },
  { label: 'Tools', to: '/tools', keywords: 'function action' },
  { label: 'Settings', to: '/settings', keywords: 'config provider api' },
  { label: 'Logs', to: '/logs', keywords: 'events audit activity' },
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

watch(() => props.open, async (isOpen) => {
  if (!isOpen) return
  try {
    const [agentData, convoData] = await Promise.all([
      $fetch<Agent[]>('/api/agents'),
      $fetch<Conversation[]>('/api/conversations?limit=10'),
    ])
    agents.value = agentData ?? []
    conversations.value = convoData ?? []
  } catch {
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

function openAgent(id: number) {
  close()
  router.push(`/agents?edit=${id}`)
}

function openConversation(id: number) {
  close()
  router.push(`/chat?conversation=${id}`)
}

function toggleTheme() {
  const html = document.documentElement
  const isDark = html.classList.contains('dark')
  setTheme(isDark ? 'light' : 'dark')
  close()
}
</script>

<template>
  <CommandDialog :open="open" @update:open="emit('update:open', $event)">
    <CommandInput placeholder="Search pages, agents, conversations..." />
    <CommandList>
      <CommandEmpty>No results found.</CommandEmpty>

      <CommandGroup heading="Navigation">
        <CommandItem
          v-for="item in navItems"
          :key="item.to"
          :value="`${item.label} ${item.keywords}`"
          @select="navigateTo(item.to)"
        >
          <span>{{ item.label }}</span>
        </CommandItem>
      </CommandGroup>

      <template v-if="agents.length">
        <CommandSeparator />
        <CommandGroup heading="Agents">
          <CommandItem
            v-for="agent in agents"
            :key="agent.id"
            :value="`agent ${agent.name} ${agent.modelProvider} ${agent.modelId}`"
            @select="openAgent(agent.id)"
          >
            <div class="flex items-center justify-between w-full">
              <span>{{ agent.name }}</span>
              <span class="text-xs text-fg-muted">{{ agent.modelId }}</span>
            </div>
          </CommandItem>
        </CommandGroup>
      </template>

      <template v-if="conversations.length">
        <CommandSeparator />
        <CommandGroup heading="Recent Conversations">
          <CommandItem
            v-for="convo in conversations"
            :key="convo.id"
            :value="`conversation ${convo.agentName} ${convo.channelType} ${convo.preview}`"
            @select="openConversation(convo.id)"
          >
            <div class="flex items-center justify-between w-full">
              <span class="truncate">{{ convo.preview || '(empty)' }}</span>
              <span class="text-xs text-fg-muted shrink-0 ml-2">{{ convo.agentName }}</span>
            </div>
          </CommandItem>
        </CommandGroup>
      </template>

      <CommandSeparator />
      <CommandGroup heading="Actions">
        <CommandItem value="toggle theme light dark mode" @select="toggleTheme">
          Toggle theme
        </CommandItem>
      </CommandGroup>
    </CommandList>
  </CommandDialog>
</template>
