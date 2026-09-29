<script setup lang="ts">
// Every saved secret in the UI goes through here (JCLAW-1330): the stored value is never shown back, masked
// or not, so eight dots and a pencil stand in for it, and the editor always opens empty.
import { CheckIcon, PencilIcon, TrashIcon, XMarkIcon } from '@heroicons/vue/24/outline'

const props = withDefaults(defineProps<{
  saved: boolean
  /** Names the input and the buttons for assistive technology, e.g. "TypeSafe API key". */
  label: string
  placeholder?: string
  inputId?: string
  /** In a form whose own Save sends the value: no save or remove buttons, and nothing saved means an input. */
  form?: boolean
  removable?: boolean
  busy?: boolean
  /** The surrounding form's input class, so the dots sit in a box the same size as the input. */
  inputClass?: string
  /** Bound onto the input itself, e.g. aria-invalid, aria-describedby or disabled. */
  inputAttrs?: Record<string, unknown>
}>(), { placeholder: undefined, inputId: undefined, form: false, removable: false, busy: false, inputClass: undefined, inputAttrs: undefined })

const value = defineModel<string>({ default: '' })
// Unbound, the field keeps its own state; a settings row binds it to the page-wide editor instead.
const editing = defineModel<boolean>('editing', { default: false })
const emit = defineEmits<{ edit: [], save: [], cancel: [], remove: [] }>()

const ROW_INPUT = 'flex-1 min-w-0 px-2 py-1 bg-muted border border-input text-sm text-fg-strong focus:outline-hidden'
const ICON_BUTTON = 'p-1 text-fg-muted hover:text-fg-strong transition-colors disabled:opacity-50'

// In a form, anything typed stays visible, since the form's Save sends it even once a saved secret appears.
const showInput = computed(() => editing.value || (props.form && (!props.saved || value.value !== '')))

function edit() {
  value.value = ''
  editing.value = true
  emit('edit')
}

function cancel() {
  value.value = ''
  editing.value = false
  emit('cancel')
}

// A blank save keeps the stored secret; removing it is its own button.
function save() {
  if (value.value.trim()) emit('save')
  else cancel()
}
</script>

<template>
  <div class="flex-1 min-w-0 flex items-center gap-3">
    <template v-if="showInput">
      <input
        :id="inputId"
        v-model="value"
        type="password"
        autocomplete="new-password"
        :aria-label="label"
        :placeholder="placeholder"
        :class="[inputClass ?? ROW_INPUT, 'min-w-0']"
        v-bind="inputAttrs"
      >
      <template v-if="!form">
        <button
          type="button"
          :class="ICON_BUTTON"
          title="Save"
          :aria-label="`Save ${label}`"
          :disabled="busy"
          @click="save"
        >
          <CheckIcon
            class="w-3.5 h-3.5"
            aria-hidden="true"
          />
        </button>
        <button
          type="button"
          :class="ICON_BUTTON"
          title="Cancel"
          :aria-label="`Cancel editing ${label}`"
          @click="cancel"
        >
          <XMarkIcon
            class="w-3.5 h-3.5"
            aria-hidden="true"
          />
        </button>
        <button
          v-if="removable && saved"
          type="button"
          :class="ICON_BUTTON"
          title="Remove"
          :aria-label="`Remove ${label}`"
          :disabled="busy"
          @click="emit('remove')"
        >
          <TrashIcon
            class="w-3.5 h-3.5"
            aria-hidden="true"
          />
        </button>
      </template>
      <button
        v-else-if="saved"
        type="button"
        :class="ICON_BUTTON"
        title="Keep the saved one"
        :aria-label="`Keep the saved ${label}`"
        @click="cancel"
      >
        <XMarkIcon
          class="w-3.5 h-3.5"
          aria-hidden="true"
        />
      </button>
    </template>
    <template v-else>
      <span
        data-secret-display
        :class="form && inputClass ? [inputClass, 'font-mono truncate'] : 'flex-1 text-sm text-fg-primary font-mono truncate'"
      >{{ saved ? '••••••••' : '(not set)' }}</span>
      <button
        type="button"
        :class="ICON_BUTTON"
        :title="saved ? 'Change' : 'Set'"
        :aria-label="`Edit ${label}`"
        :disabled="busy"
        @click="edit"
      >
        <PencilIcon
          class="w-3.5 h-3.5"
          aria-hidden="true"
        />
      </button>
    </template>
  </div>
</template>
