<script setup lang="ts">
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

const variantStyles: Record<BannerVariant, { bg: string, border: string, text: string, icon: string }> = {
  warning: {
    bg: 'bg-amber-50 dark:bg-amber-900/20',
    border: 'border-amber-200 dark:border-amber-800/40',
    text: 'text-amber-800 dark:text-amber-300',
    icon: 'M12 9v3.75m-9.303 3.376c-.866 1.5.217 3.374 1.948 3.374h14.71c1.73 0 2.813-1.874 1.948-3.374L13.949 3.378c-.866-1.5-3.032-1.5-3.898 0L2.697 16.126ZM12 15.75h.007v.008H12v-.008Z',
  },
  error: {
    bg: 'bg-red-50 dark:bg-red-900/20',
    border: 'border-red-200 dark:border-red-800/40',
    text: 'text-red-800 dark:text-red-300',
    icon: 'M12 9v3.75m9-.75a9 9 0 1 1-18 0 9 9 0 0 1 18 0Zm-9 3.75h.008v.008H12v-.008Z',
  },
  info: {
    bg: 'bg-blue-50 dark:bg-blue-900/20',
    border: 'border-blue-200 dark:border-blue-800/40',
    text: 'text-blue-800 dark:text-blue-300',
    icon: 'm11.25 11.25.041-.02a.75.75 0 0 1 1.063.852l-.708 2.836a.75.75 0 0 0 1.063.853l.041-.021M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Zm-9-3.75h.008v.008H12V8.25Z',
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
    <svg
      class="w-5 h-5 shrink-0"
      fill="none"
      stroke="currentColor"
      viewBox="0 0 24 24"
    >
      <path
        stroke-linecap="round"
        stroke-linejoin="round"
        stroke-width="1.5"
        :d="style.icon"
      />
    </svg>

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
      <svg
        class="w-4 h-4"
        fill="none"
        stroke="currentColor"
        viewBox="0 0 24 24"
      >
        <path
          stroke-linecap="round"
          stroke-linejoin="round"
          stroke-width="2"
          d="M6 18L18 6M6 6l12 12"
        />
      </svg>
    </button>
  </div>
</template>
