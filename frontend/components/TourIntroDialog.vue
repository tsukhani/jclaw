<script setup lang="ts">
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '~/components/ui/dialog'
import { Button } from '~/components/ui/button'

defineProps<{
  open: boolean
}>()

const emit = defineEmits<{
  (e: 'start' | 'skip'): void
  (e: 'update:open', value: boolean): void
}>()

function onOpenChange(value: boolean) {
  emit('update:open', value)
  // Closing via X / Escape / overlay click counts as Skip — same outcome:
  // the dialog goes away for this session and the threshold isn't advanced.
  if (!value) emit('skip')
}

function onStart() {
  emit('start')
}

function onSkip() {
  emit('skip')
}
</script>

<template>
  <Dialog
    :open="open"
    @update:open="onOpenChange"
  >
    <DialogContent class="max-w-md">
      <DialogHeader>
        <DialogTitle>Welcome to JClaw</DialogTitle>
        <DialogDescription class="pt-2">
          JClaw helps you wire AI agents to chat, skills, and channels — all from one place.
          This 30-second tour walks you through the four things you need to set up before your
          first conversation: an LLM provider, your Main Agent, the model you want it to use,
          and the chat composer itself. You can come back to it any time from the sidebar.
        </DialogDescription>
      </DialogHeader>
      <DialogFooter class="gap-2 sm:gap-2">
        <Button
          variant="ghost"
          @click="onSkip"
        >
          Skip for now
        </Button>
        <Button @click="onStart">
          Start tour
        </Button>
      </DialogFooter>
    </DialogContent>
  </Dialog>
</template>
