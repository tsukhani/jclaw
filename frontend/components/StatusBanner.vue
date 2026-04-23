<script setup lang="ts">
import {
  ExclamationCircleIcon,
  ExclamationTriangleIcon,
  InformationCircleIcon,
  XMarkIcon,
} from '@heroicons/vue/24/outline'
import type { FunctionalComponent } from 'vue'

type BannerVariant = 'warning' | 'error' | 'info'

const props = withDefaults(defineProps<{
  /** The banner message */
  message: string
  /** Visual variant: warning (amber), error (red), info (blue) */
  variant?: BannerVariant
  /** Action link text (e.g., "Check settings", "Retry") */
  actionText?: string
  /** Whether the banner can be dismissed (transient conditions only) */
  dismissable?: boolean
}>(), {
  variant: 'warning',
  actionText: '',
  dismissable: false,
})

const emit = defineEmits<{
  (e: 'action' | 'dismiss'): void
}>()

const dismissed = ref(false)

interface VariantStyle {
  bg: string
  border: string
  text: string
  icon: FunctionalComponent
}

const variantStyles: Record<BannerVariant, VariantStyle> = {
  warning: {
    bg: 'bg-amber-50 dark:bg-amber-900/20',
    border: 'border-amber-200 dark:border-amber-800/40',
    text: 'text-amber-800 dark:text-amber-300',
    icon: ExclamationTriangleIcon,
  },
  error: {
    bg: 'bg-red-50 dark:bg-red-900/20',
    border: 'border-red-200 dark:border-red-800/40',
    text: 'text-red-800 dark:text-red-300',
    icon: ExclamationCircleIcon,
  },
  info: {
    bg: 'bg-blue-50 dark:bg-blue-900/20',
    border: 'border-blue-200 dark:border-blue-800/40',
    text: 'text-blue-800 dark:text-blue-300',
    icon: InformationCircleIcon,
  },
}

const style = computed(() => variantStyles[props.variant])
</script>

<template>
  <div
    v-if="!dismissed"
    role="alert"
    aria-live="assertive"
    :class="[style.bg, style.border, style.text]"
    class="flex items-center gap-3 px-4 py-2.5 border-b text-sm"
  >
    <!-- Status icon -->
    <component
      :is="style.icon"
      class="w-5 h-5 shrink-0"
      aria-hidden="true"
    />

    <!-- Message -->
    <span class="flex-1">{{ message }}</span>

    <!-- Action link -->
    <button
      v-if="actionText"
      class="text-xs font-medium underline underline-offset-2 hover:no-underline shrink-0"
      @click="emit('action')"
    >
      {{ actionText }}
    </button>

    <!-- Dismiss button (only for transient conditions) -->
    <button
      v-if="dismissable"
      class="p-0.5 opacity-60 hover:opacity-100 transition-opacity shrink-0"
      aria-label="Dismiss"
      @click="dismissed = true; emit('dismiss')"
    >
      <XMarkIcon
        class="w-4 h-4"
        aria-hidden="true"
      />
    </button>
  </div>
</template>
