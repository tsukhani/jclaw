<script setup lang="ts">
// A form an MCP server asked the operator to fill in mid-call. The server's text is shown as plain text, so a link
// in it never becomes clickable.
import { computed, onMounted, reactive, ref, useId, watch } from 'vue'
import { ChatBubbleLeftEllipsisIcon } from '@heroicons/vue/24/outline'
import { useApiMutation } from '~/composables/useApiMutation'
import type { McpElicitation } from '~/types/api'
import {
  answerContent,
  formFields,
  initialValues,
  validate,
  type McpFieldValue,
  type McpFormField,
} from '~/utils/mcp-elicitation'

const props = defineProps<{ elicitation: McpElicitation }>()
const emit = defineEmits<{ done: [id: string] }>()

const fields = computed(() => formFields(props.elicitation))
const initial = initialValues(fields.value)
const values = reactive<Record<string, McpFieldValue>>(initialValues(fields.value))
// Set after mount, so a screen reader hears the polite status change without focus moving.
const announcement = ref('')
onMounted(() => {
  announcement.value = `MCP server ${props.elicitation.server} is asking a question.`
})
const problems = ref<Record<string, string>>({})
// A problem goes once its field changes, since it described the old value; Submit checks again.
watch(() => fields.value.map(f => JSON.stringify(values[f.key] ?? null)), (now, before) => {
  const changed = new Set(fields.value.filter((_, i) => now[i] !== before[i]).map(f => f.key))
  problems.value = Object.fromEntries(Object.entries(problems.value).filter(([key]) => !changed.has(key)))
})
const formError = ref<string | null>(null)
const { mutate, loading, errorDetails } = useApiMutation()
const uid = useId()

const deadline = computed(() => {
  const d = new Date(props.elicitation.expiresAt)
  return Number.isNaN(d.getTime()) ? null : d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
})

// By position: a field's key is the server's text, and a space in it would split aria-describedby.
const ids = computed(() => new Map(fields.value.map((f, i) => [f.key, `${uid}-f${i}`])))
const inputId = (f: McpFormField) => ids.value.get(f.key) ?? `${uid}-f`
function describedBy(f: McpFormField): string | undefined {
  const ids = [f.description ? `${inputId(f)}-help` : null, problems.value[f.key] ? `${inputId(f)}-problem` : null]
  return ids.filter(Boolean).join(' ') || undefined
}

async function send(action: 'accept' | 'decline' | 'cancel') {
  formError.value = null
  if (action === 'accept') {
    problems.value = validate(fields.value, values)
    if (Object.keys(problems.value).length) return
  }
  const body = action === 'accept' ? { action, content: answerContent(fields.value, values, initial) } : { action }
  const sent = await mutate(`/api/mcp-servers/elicitations/${encodeURIComponent(props.elicitation.id)}`, {
    method: 'POST',
    body,
  })
  if (sent !== null) {
    emit('done', props.elicitation.id)
    return
  }
  const failure = errorDetails.value
  // Answered elsewhere, timed out or the turn stopped: the form is gone either way.
  if (failure?.status === 404) {
    emit('done', props.elicitation.id)
    return
  }
  if (failure?.field && fields.value.some(f => f.key === failure.field)) {
    problems.value = { [failure.field]: failure.message }
    return
  }
  formError.value = failure?.message ?? 'The answer could not be sent.'
}

const inputClass = 'w-full px-2 py-1 bg-muted border border-input text-sm text-fg-strong '
  + 'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring'
</script>

<template>
  <section
    data-testid="mcp-elicitation"
    class="mx-auto w-full max-w-3xl px-4 pb-2"
    :aria-labelledby="`${uid}-title`"
  >
    <p
      role="status"
      class="sr-only"
      data-testid="mcp-elicitation-status"
    >
      {{ announcement }}
    </p>
    <form
      class="rounded-lg border border-border bg-surface-elevated text-sm"
      novalidate
      @submit.prevent="send('accept')"
    >
      <div class="px-3 pt-3 space-y-1">
        <h2
          :id="`${uid}-title`"
          class="flex items-center gap-2 font-medium text-fg-strong"
        >
          <ChatBubbleLeftEllipsisIcon
            class="w-4 h-4 shrink-0 text-sky-500"
            aria-hidden="true"
          />
          <span>Question from <span data-testid="mcp-elicitation-server">{{ elicitation.server }}</span></span>
        </h2>
        <p
          v-if="elicitation.message"
          data-testid="mcp-elicitation-message"
          class="text-fg-strong whitespace-pre-wrap break-words"
        >
          {{ elicitation.message }}
        </p>
      </div>

      <div
        v-if="fields.length"
        class="px-3 pt-3 space-y-3 max-h-80 overflow-y-auto"
      >
        <div
          v-for="f in fields"
          :key="f.key"
          :data-testid="`mcp-elicitation-field-${f.key}`"
        >
          <label
            v-if="f.kind === 'boolean'"
            :for="inputId(f)"
            class="flex items-center gap-2 text-fg-strong"
          >
            <input
              :id="inputId(f)"
              v-model="values[f.key]"
              type="checkbox"
              :aria-describedby="describedBy(f)"
            >
            {{ f.label }}
          </label>

          <fieldset
            v-else-if="f.kind === 'choices'"
            :aria-describedby="describedBy(f)"
            :aria-invalid="problems[f.key] ? true : undefined"
          >
            <legend class="text-xs font-medium text-fg-strong mb-1">
              {{ f.label }}<span
                v-if="f.required"
                aria-hidden="true"
              > *</span>
            </legend>
            <label
              v-for="(o, i) in f.options"
              :key="o.value"
              :for="`${inputId(f)}-${i}`"
              class="flex items-center gap-2 text-fg-strong"
            >
              <input
                :id="`${inputId(f)}-${i}`"
                v-model="values[f.key]"
                type="checkbox"
                :value="o.value"
              >
              {{ o.label }}
            </label>
          </fieldset>

          <label
            v-else
            :for="inputId(f)"
            class="block"
          >
            <span class="block text-xs font-medium text-fg-strong mb-1">
              {{ f.label }}<span
                v-if="f.required"
                aria-hidden="true"
              > *</span>
            </span>
            <select
              v-if="f.kind === 'choice'"
              :id="inputId(f)"
              v-model="values[f.key]"
              :class="inputClass"
              :required="f.required"
              :aria-invalid="problems[f.key] ? true : undefined"
              :aria-describedby="describedBy(f)"
            >
              <option value="">
                {{ f.required ? 'Choose…' : 'None' }}
              </option>
              <option
                v-for="o in f.options"
                :key="o.value"
                :value="o.value"
              >
                {{ o.label }}
              </option>
            </select>
            <input
              v-else-if="f.kind === 'number' || f.kind === 'integer'"
              :id="inputId(f)"
              v-model="values[f.key]"
              type="number"
              :step="f.kind === 'integer' ? 1 : 'any'"
              :min="f.schema.minimum"
              :max="f.schema.maximum"
              :class="inputClass"
              :required="f.required"
              :aria-invalid="problems[f.key] ? true : undefined"
              :aria-describedby="describedBy(f)"
            >
            <input
              v-else
              :id="inputId(f)"
              v-model="values[f.key]"
              :type="f.inputType"
              :step="f.inputType === 'datetime-local' ? 1 : undefined"
              :class="inputClass"
              :required="f.required"
              :aria-invalid="problems[f.key] ? true : undefined"
              :aria-describedby="describedBy(f)"
            >
          </label>

          <p
            v-if="f.description"
            :id="`${inputId(f)}-help`"
            class="mt-1 text-xs text-fg-muted"
          >
            {{ f.description }}
          </p>
          <p
            v-if="problems[f.key]"
            :id="`${inputId(f)}-problem`"
            data-testid="mcp-elicitation-problem"
            class="mt-1 text-xs text-red-700 dark:text-red-300"
          >
            {{ problems[f.key] }}
          </p>
        </div>
      </div>

      <p
        v-if="formError"
        role="alert"
        data-testid="mcp-elicitation-error"
        class="px-3 pt-3 text-xs text-red-700 dark:text-red-300"
      >
        {{ formError }}
      </p>

      <div class="mt-3 px-3 py-2 border-t border-border flex flex-wrap items-center gap-2">
        <span
          v-if="deadline"
          class="mr-auto text-xs text-fg-muted"
        >If you do not answer by {{ deadline }}, the request is cancelled.</span>
        <button
          type="button"
          data-testid="mcp-elicitation-cancel"
          class="px-3 py-1.5 text-xs text-fg-muted hover:text-fg-strong transition-colors disabled:opacity-40"
          :disabled="loading"
          @click="send('cancel')"
        >
          Cancel
        </button>
        <button
          type="button"
          data-testid="mcp-elicitation-decline"
          class="px-3 py-1.5 text-xs border border-border text-fg-strong hover:bg-muted transition-colors disabled:opacity-40"
          :disabled="loading"
          @click="send('decline')"
        >
          Decline
        </button>
        <button
          type="submit"
          data-testid="mcp-elicitation-submit"
          class="px-3 py-1.5 text-xs border transition-colors disabled:opacity-40 bg-emerald-50 dark:bg-emerald-950/40
                 border-emerald-200 dark:border-emerald-900/60 text-emerald-700 dark:text-emerald-300
                 hover:bg-emerald-100 dark:hover:bg-emerald-900/40"
          :disabled="loading"
        >
          Submit
        </button>
      </div>
    </form>
  </section>
</template>
