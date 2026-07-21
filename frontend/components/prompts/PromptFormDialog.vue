<script setup lang="ts">
// Add/edit modal for a prompt (JCLAW-813). Create mode when `editing` is null,
// edit mode otherwise. Uses the shared ui/dialog primitives and useApiMutation;
// a category dropdown is populated from the fixed category list.
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

const title = ref('')
const content = ref('')
const category = ref('')
const tags = ref('')

const { mutate, loading, error } = useApiMutation()

// Reset/prefill the form each time the dialog opens.
watch(() => props.open, (isOpen) => {
  if (!isOpen) return
  title.value = props.editing?.title ?? ''
  content.value = props.editing?.content ?? ''
  category.value = props.editing?.category ?? props.categories[0]?.value ?? ''
  tags.value = props.editing?.tags ?? ''
})

const isEdit = computed(() => !!props.editing)
const canSave = computed(() => !!title.value.trim() && !!content.value.trim() && !!category.value)

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
        <DialogTitle>{{ isEdit ? 'Edit prompt' : 'New prompt' }}</DialogTitle>
      </DialogHeader>
      <form
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
          v-if="error"
          class="text-xs text-red-600 dark:text-red-400"
        >
          {{ error }}
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
            :disabled="!canSave || loading"
            data-testid="prompt-save"
            class="px-3 py-1.5 text-sm font-medium bg-emerald-600 text-white rounded hover:bg-emerald-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
          >
            {{ isEdit ? 'Save' : 'Create' }}
          </button>
        </DialogFooter>
      </form>
    </DialogContent>
  </Dialog>
</template>
