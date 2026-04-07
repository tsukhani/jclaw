<script setup lang="ts">
const { data: conversations, refresh } = await useFetch<any[]>('/api/conversations?limit=50')
const selectedConvo = ref<any>(null)
const messages = ref<any[]>([])

async function selectConversation(convo: any) {
  selectedConvo.value = convo
  messages.value = await $fetch<any[]>(`/api/conversations/${convo.id}/messages`) ?? []
}

function back() {
  selectedConvo.value = null
  messages.value = []
}
</script>

<template>
  <div>
    <h1 class="text-lg font-semibold text-white mb-6">Conversations</h1>

    <!-- List view -->
    <div v-if="!selectedConvo" class="bg-neutral-900 border border-neutral-800">
      <table class="w-full text-sm">
        <thead>
          <tr class="border-b border-neutral-800 text-left text-xs text-neutral-500">
            <th class="px-4 py-2.5 font-medium">Channel</th>
            <th class="px-4 py-2.5 font-medium">Agent</th>
            <th class="px-4 py-2.5 font-medium">Peer</th>
            <th class="px-4 py-2.5 font-medium">Messages</th>
            <th class="px-4 py-2.5 font-medium">Last Activity</th>
          </tr>
        </thead>
        <tbody class="divide-y divide-neutral-800/50">
          <tr
            v-for="convo in conversations"
            :key="convo.id"
            @click="selectConversation(convo)"
            class="hover:bg-neutral-800/50 cursor-pointer transition-colors"
          >
            <td class="px-4 py-2.5 text-neutral-300">
              <span class="font-mono text-xs bg-neutral-800 px-1.5 py-0.5">{{ convo.channelType }}</span>
            </td>
            <td class="px-4 py-2.5 text-neutral-300">{{ convo.agentName }}</td>
            <td class="px-4 py-2.5 text-neutral-400 font-mono text-xs">{{ convo.peerId || '—' }}</td>
            <td class="px-4 py-2.5 text-neutral-400">{{ convo.messageCount }}</td>
            <td class="px-4 py-2.5 text-neutral-500 text-xs">{{ new Date(convo.updatedAt).toLocaleString() }}</td>
          </tr>
        </tbody>
      </table>
      <div v-if="!conversations?.length" class="px-4 py-8 text-center text-sm text-neutral-600">
        No conversations yet
      </div>
    </div>

    <!-- Detail view -->
    <div v-else>
      <button @click="back" class="text-xs text-neutral-500 hover:text-white mb-4 transition-colors">&larr; Back</button>
      <div class="bg-neutral-900 border border-neutral-800 p-4 mb-4">
        <div class="flex gap-6 text-xs text-neutral-400">
          <span>Channel: <strong class="text-neutral-300">{{ selectedConvo.channelType }}</strong></span>
          <span>Agent: <strong class="text-neutral-300">{{ selectedConvo.agentName }}</strong></span>
          <span>Peer: <strong class="text-neutral-300 font-mono">{{ selectedConvo.peerId || '—' }}</strong></span>
        </div>
      </div>
      <div class="space-y-3">
        <div
          v-for="msg in messages"
          :key="msg.id"
          :class="msg.role === 'user' ? 'ml-16' : msg.role === 'tool' ? 'ml-8' : ''"
        >
          <div class="flex items-center gap-2 mb-0.5">
            <span class="text-xs font-mono" :class="{
              'text-neutral-500': msg.role === 'user',
              'text-neutral-400': msg.role === 'assistant',
              'text-neutral-600': msg.role === 'tool'
            }">{{ msg.role }}</span>
            <span class="text-xs text-neutral-700">{{ new Date(msg.createdAt).toLocaleTimeString() }}</span>
          </div>
          <div class="bg-neutral-800/50 border border-neutral-800 px-3 py-2 text-sm text-neutral-300 whitespace-pre-wrap">{{ msg.content || '(tool call)' }}</div>
        </div>
      </div>
    </div>
  </div>
</template>
