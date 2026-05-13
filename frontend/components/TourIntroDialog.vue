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
  // Closing via Escape / overlay click counts as Skip — same outcome:
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
    <DialogContent
      class="jclaw-tour-intro max-w-md"
      :show-close-button="false"
    >
      <DialogHeader class="items-center text-center sm:items-center sm:text-center">
        <img
          src="/clawdia-waving.webp"
          alt="Clawdia, the JClaw mascot, waving hello"
          width="119"
          height="128"
          class="select-none mb-2"
        >
        <DialogTitle>Welcome to JClaw</DialogTitle>
        <DialogDescription class="pt-2">
          Hi, I'm Clawdia — your JClaw sidekick. I help you wire up AI agents to chat, skills,
          and channels, all from one place. Want me to show you around? This quick tour walks
          through the four things you need before your first real conversation — an LLM
          provider, your Main Agent, the model it'll use, and the chat composer — plus two
          optional stops for adding skills and connecting a channel. If you'd rather poke
          around on your own, no worries — I'll be waving from the sidebar any time you want
          a refresher.
        </DialogDescription>
      </DialogHeader>
      <DialogFooter class="gap-2 sm:gap-2">
        <Button
          variant="ghost"
          @click="onSkip"
        >
          Skip for now
        </Button>
        <Button
          class="jclaw-tour-intro-primary"
          @click="onStart"
        >
          Start tour
        </Button>
      </DialogFooter>
    </DialogContent>
  </Dialog>
</template>
