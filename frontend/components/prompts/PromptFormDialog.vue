<script setup lang="ts">
// New/Edit prompt dialog (JCLAW-813).
//  - Create: a two-step "describe it" flow — step 1 is a single description box
//    + Generate; the LLM-generated title/category/content/tags then pre-fill the
//    same form (step 2) for the operator to tweak or save.
//  - Edit: opens straight to the form, pre-filled from the existing prompt.
// The form step is identical in both modes, so review-generated == edit.
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '~/components/ui/dialog'
import type { Prompt, PromptCategory } from '~/types/api'

const props = defineProps<{
  open: boolean
  editing: Prompt | null
  categories: PromptCategory[]
}>()

const emit = defineEmits<{
  (e: 'update:open', value: boolean): void
  (e: 'saved'): void
}>()

const step = ref<'describe' | 'form'>('form')
const description = ref('')
const title = ref('')
const content = ref('')
const category = ref('')
const tags = ref('')

const { mutate, loading: saving, error: saveError } = useApiMutation()
const { mutate: genMutate, loading: generating, error: generateError } = useApiMutation()

// Reset each time the dialog opens: edit → form (pre-filled); create → describe.
watch(() => props.open, (isOpen) => {
  if (!isOpen) return
  if (props.editing) {
    step.value = 'form'
    title.value = props.editing.title
    content.value = props.editing.content
    category.value = props.editing.category
    tags.value = props.editing.tags ?? ''
  }
  else {
    step.value = 'describe'
    description.value = ''
    title.value = ''
    content.value = ''
    category.value = props.categories[0]?.value ?? ''
    tags.value = ''
  }
})

const isEdit = computed(() => !!props.editing)
const dialogTitle = computed(() => {
  if (isEdit.value) return 'Edit prompt'
  return step.value === 'describe' ? 'New prompt' : 'Review prompt'
})
const canSave = computed(() => !!title.value.trim() && !!content.value.trim() && !!category.value)

async function generate() {
  if (!description.value.trim()) return
  const res = await genMutate<{ title: string, category: string, content: string, tags: string }>(
    '/api/prompts/generate', { method: 'POST', body: { description: description.value.trim() } })
  if (res) {
    title.value = res.title
    category.value = res.category || props.categories[0]?.value || ''
    content.value = res.content
    tags.value = res.tags
    step.value = 'form'
  }
}

async function save() {
  if (!canSave.value) return
  const body = {
    title: title.value.trim(),
    content: content.value,
    category: category.value,
    tags: tags.value.trim(),
  }
  const url = props.editing ? `/api/prompts/${props.editing.id}` : '/api/prompts'
  const method = props.editing ? 'PUT' : 'POST'
  const res = await mutate(url, { method, body })
  if (res) {
    emit('saved')
    emit('update:open', false)
  }
}
</script>

<template>
  <Dialog
    :open="open"
    @update:open="(v) => emit('update:open', v)"
  >
    <DialogContent class="max-w-lg">
      <DialogHeader>
        <DialogTitle>{{ dialogTitle }}</DialogTitle>
      </DialogHeader>

      <!-- Step 1 (create only): describe → generate -->
      <form
        v-if="step === 'describe'"
        class="space-y-3"
        @submit.prevent="generate"
      >
        <label
          for="pf-description"
          class="block"
        >
          <span class="block text-xs text-fg-muted mb-1">Describe the prompt you want</span>
          <textarea
            id="pf-description"
            v-model="description"
            rows="6"
            required
            placeholder="A checklist for reviewing code — security, performance, edge cases, grouped by severity."
            data-testid="prompt-description"
            class="w-full px-2 py-1.5 bg-muted border border-input text-sm text-fg-strong focus:outline-hidden resize-y"
          />
        </label>
        <p class="text-[11px] text-fg-muted">
          The prompt, category, and tags are generated from this — you can edit them all before saving.
        </p>
        <p
          v-if="generateError"
          class="text-xs text-red-600 dark:text-red-400"
        >
          {{ generateError }}
        </p>
        <DialogFooter class="gap-2">
          <button
            type="button"
            class="px-3 py-1.5 text-sm text-fg-muted hover:text-fg-strong transition-colors"
            @click="emit('update:open', false)"
          >
            Cancel
          </button>
          <button
            type="submit"
            :disabled="!description.trim() || generating"
            data-testid="prompt-generate"
            class="inline-flex items-center gap-1.5 px-3 py-1.5 text-sm font-medium bg-emerald-600 text-white rounded hover:bg-emerald-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
          >
            <span
              v-if="generating"
              class="inline-block w-3.5 h-3.5 border-2 border-current border-t-transparent rounded-full animate-spin"
            />
            {{ generating ? 'Generating…' : 'Generate' }}
          </button>
        </DialogFooter>
      </form>

      <!-- Step 2 / edit: the form (identical for review-generated and edit) -->
      <form
        v-else
        class="space-y-3"
        @submit.prevent="save"
      >
        <label
          for="pf-title"
          class="block"
        >
          <span class="block text-xs text-fg-muted mb-1">Title</span>
          <input
            id="pf-title"
            v-model="title"
            type="text"
            required
            maxlength="200"
            placeholder="Code Review Checklist"
            data-testid="prompt-title"
            class="w-full px-2 py-1.5 bg-muted border border-input text-sm text-fg-strong focus:outline-hidden"
          >
        </label>
        <label
          for="pf-category"
          class="block"
        >
          <span class="block text-xs text-fg-muted mb-1">Category</span>
          <select
            id="pf-category"
            v-model="category"
            required
            data-testid="prompt-category"
            class="w-full px-2 py-1.5 bg-muted border border-input text-sm text-fg-strong focus:outline-hidden"
          >
            <option
              v-for="c in categories"
              :key="c.value"
              :value="c.value"
            >
              {{ c.label }}
            </option>
          </select>
        </label>
        <label
          for="pf-content"
          class="block"
        >
          <span class="block text-xs text-fg-muted mb-1">Prompt text</span>
          <textarea
            id="pf-content"
            v-model="content"
            rows="8"
            required
            placeholder="Write your reusable prompt…"
            data-testid="prompt-content"
            class="w-full px-2 py-1.5 bg-muted border border-input text-sm text-fg-strong focus:outline-hidden resize-y"
          />
        </label>
        <label
          for="pf-tags"
          class="block"
        >
          <span class="block text-xs text-fg-muted mb-1">
            Tags <span class="text-fg-muted/70">(optional, comma-separated)</span>
          </span>
          <input
            id="pf-tags"
            v-model="tags"
            type="text"
            placeholder="code, review, python"
            data-testid="prompt-tags"
            class="w-full px-2 py-1.5 bg-muted border border-input text-sm text-fg-strong focus:outline-hidden"
          >
        </label>
        <p
          v-if="saveError"
          class="text-xs text-red-600 dark:text-red-400"
        >
          {{ saveError }}
        </p>
        <DialogFooter class="gap-2 sm:justify-between">
          <button
            v-if="!isEdit"
            type="button"
            class="px-3 py-1.5 text-sm text-fg-muted hover:text-fg-strong transition-colors sm:mr-auto"
            @click="step = 'describe'"
          >
            ← Back
          </button>
          <div class="flex gap-2">
            <button
              type="button"
              class="px-3 py-1.5 text-sm text-fg-muted hover:text-fg-strong transition-colors"
              @click="emit('update:open', false)"
            >
              Cancel
            </button>
            <button
              type="submit"
              :disabled="!canSave || saving"
              data-testid="prompt-save"
              class="px-3 py-1.5 text-sm font-medium bg-emerald-600 text-white rounded hover:bg-emerald-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
            >
              {{ isEdit ? 'Save' : 'Create' }}
            </button>
          </div>
        </DialogFooter>
      </form>
    </DialogContent>
  </Dialog>
</template>
