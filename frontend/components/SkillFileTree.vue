<script setup lang="ts">
import type { SkillFile } from '~/types/api'
import { formatSize } from '~/utils/format'

export type SkillFileNode = {
  name: string
  isDir: boolean
  path?: string
  file?: SkillFile
  children?: SkillFileNode[]
}

defineProps<{
  nodes: SkillFileNode[]
  activePath: string | null
  depth?: number
}>()

const emit = defineEmits<{
  (e: 'select', file: SkillFile): void
}>()

const openDirs = ref<Record<string, boolean>>({})

function toggle(key: string) {
  openDirs.value[key] = !isOpen(key)
}

function isOpen(key: string) {
  return openDirs.value[key] !== false
}

function iconFor(file: SkillFile | undefined) {
  const p = (file?.path ?? '').toLowerCase()
  if (p.endsWith('.md')) return 'MD'
  if (p.endsWith('.py')) return 'PY'
  if (p.endsWith('.js') || p.endsWith('.ts')) return 'JS'
  if (p.endsWith('.json')) return '{}'
  if (p.endsWith('.sh') || p.endsWith('.bash')) return 'SH'
  if (p.endsWith('.yml') || p.endsWith('.yaml')) return 'YML'
  return file?.isText ? 'TXT' : 'BIN'
}
</script>

<template>
  <div>
    <template
      v-for="node in nodes"
      :key="(node.path ?? node.name) + (node.isDir ? '/' : '')"
    >
      <!-- Directory row -->
      <button
        v-if="node.isDir"
        type="button"
        class="w-full flex items-center gap-1.5 px-3 py-1.5 cursor-pointer text-fg-muted hover:bg-muted/50 transition-colors select-none bg-transparent border-0 text-left"
        :style="{ paddingLeft: `${12 + (depth ?? 0) * 12}px` }"
        @click="toggle(node.path ?? (node.name + (depth ?? 0)))"
      >
        <svg
          class="w-3 h-3 shrink-0 transition-transform"
          :class="isOpen(node.path ?? (node.name + (depth ?? 0))) ? 'rotate-90' : ''"
          fill="none"
          stroke="currentColor"
          viewBox="0 0 24 24"
        >
          <path
            stroke-linecap="round"
            stroke-linejoin="round"
            stroke-width="2"
            d="M9 5l7 7-7 7"
          />
        </svg>
        <svg
          class="w-3.5 h-3.5 text-fg-muted shrink-0"
          fill="currentColor"
          viewBox="0 0 20 20"
        >
          <path d="M2 6a2 2 0 012-2h3.172a2 2 0 011.414.586l1.828 1.828A2 2 0 0011.828 7H16a2 2 0 012 2v5a2 2 0 01-2 2H4a2 2 0 01-2-2V6z" />
        </svg>
        <span class="text-xs truncate">{{ node.name }}</span>
      </button>

      <!-- Children -->
      <SkillFileTree
        v-if="node.isDir && isOpen(node.path ?? (node.name + (depth ?? 0)))"
        :nodes="node.children ?? []"
        :active-path="activePath"
        :depth="(depth ?? 0) + 1"
        @select="(f) => emit('select', f)"
      />

      <!-- File row -->
      <button
        v-else-if="!node.isDir"
        type="button"
        :disabled="!node.file?.isText"
        :class="[
          'w-full flex items-center gap-2 px-3 py-1.5 transition-colors bg-transparent border-0 text-left',
          activePath === node.path ? 'bg-muted text-fg-strong' : 'text-fg-muted hover:bg-muted/50',
          node.file?.isText ? 'cursor-pointer' : 'cursor-default opacity-60',
        ]"
        :style="{ paddingLeft: `${12 + (depth ?? 0) * 12}px` }"
        @click="node.file ? emit('select', node.file) : undefined"
      >
        <span
          class="text-[9px] font-mono px-1 py-0.5 rounded shrink-0"
          :class="node.file?.isText ? 'bg-emerald-100 dark:bg-emerald-900/40 text-emerald-700 dark:text-emerald-400' : 'bg-muted text-fg-muted'"
        >
          {{ iconFor(node.file) }}
        </span>
        <div class="min-w-0">
          <div class="text-xs truncate">
            {{ node.name }}
          </div>
          <div class="text-[10px] text-fg-muted">
            {{ formatSize(node.file?.size ?? 0) }}
          </div>
        </div>
      </button>
    </template>
  </div>
</template>
