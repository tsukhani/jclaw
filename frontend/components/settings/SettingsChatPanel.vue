<script setup lang="ts">
import { CheckIcon, ChevronUpIcon, InformationCircleIcon, PencilIcon, XMarkIcon } from '@heroicons/vue/24/outline'

const { configValue, saveField } = useSettingsConfig()

// Chat config
const chatMaxToolRounds = computed(() => configValue('chat.maxToolRounds', '10'))
const chatMaxContextMessages = computed(() => configValue('chat.maxContextMessages', '50'))

// Advanced context-management knobs — collapsed by default in the Chat panel.
// All four are runtime-tunable via ConfigService side-effects (no restart).
// Defaults mirror the Java-side fallbacks: SessionCompactor for the chat.compaction*
// trio, ContextWindowManager.DEFAULT_SAFETY_MULTIPLIER for the jtokkit knob.
const compactionReserveTokens = computed(() => configValue('chat.compactionReserveTokens', '15000'))
const compactionMinTurns = computed(() => configValue('chat.compactionMinTurns', '10'))
const compactionKeepMessages = computed(() => configValue('chat.compactionKeepMessages', '10'))
const jtokkitSafetyMultiplier = computed(() => configValue('jtokkit.safetyMultiplier.unmatched', '1.4'))

const showAdvancedChat = ref(false)

const editingChatField = ref<string | null>(null)
const chatFieldEdit = ref('')

async function saveChatField(configKey: string, value: string) {
  await saveField(configKey, value)
  editingChatField.value = null
}
</script>

<template>
  <!-- Chat Settings -->
  <div class="mb-6 space-y-4">
    <h2 class="text-sm font-medium text-fg-muted">
      Chat
    </h2>
    <p class="text-xs text-fg-muted">
      Configure chat behavior limits.
    </p>
    <div class="bg-surface-elevated border border-border">
      <div class="divide-y divide-border">
        <div class="px-4 py-2.5 flex items-center gap-3">
          <span class="text-xs font-mono text-fg-muted w-48 shrink-0 flex items-center gap-1.5">
            maxToolRounds
            <span class="relative group/tip">
              <InformationCircleIcon
                class="w-3 h-3 text-fg-muted group-hover/tip:text-fg-muted cursor-help transition-colors"
                aria-hidden="true"
              />
              <span class="absolute left-0 top-5 z-20 hidden group-hover/tip:block w-56 px-2.5 py-2 bg-muted border border-input text-[10px] text-fg-muted leading-relaxed shadow-xl pointer-events-none">
                Max tool calls the agent can make per turn. Once reached, it must give a final answer without calling more tools.
              </span>
            </span>
          </span>
          <template v-if="editingChatField === 'maxToolRounds'">
            <input
              v-model="chatFieldEdit"
              type="number"
              min="1"
              max="50"
              aria-label="Max tool rounds"
              class="w-24 px-2 py-1 bg-muted border border-input text-sm text-fg-strong font-mono focus:outline-hidden"
            >
            <button
              class="p-1 text-fg-muted hover:text-emerald-700 dark:hover:text-emerald-400 transition-colors"
              title="Save"
              @click="saveChatField('chat.maxToolRounds', chatFieldEdit)"
            >
              <CheckIcon
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
            </button>
            <button
              class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
              title="Cancel"
              @click="editingChatField = null"
            >
              <XMarkIcon
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
            </button>
          </template>
          <template v-else>
            <span class="flex-1 text-sm text-fg-primary font-mono">{{ chatMaxToolRounds }} rounds</span>
            <button
              class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
              title="Edit"
              @click="editingChatField = 'maxToolRounds'; chatFieldEdit = chatMaxToolRounds"
            >
              <PencilIcon
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
            </button>
          </template>
        </div>
        <div class="px-4 py-2.5 flex items-center gap-3">
          <span class="text-xs font-mono text-fg-muted w-48 shrink-0 flex items-center gap-1.5">
            maxContextMessages
            <span class="relative group/tip">
              <InformationCircleIcon
                class="w-3 h-3 text-fg-muted group-hover/tip:text-fg-muted cursor-help transition-colors"
                aria-hidden="true"
              />
              <span class="absolute left-0 top-5 z-20 hidden group-hover/tip:block w-64 px-2.5 py-2 bg-muted border border-input text-[10px] text-fg-muted leading-relaxed shadow-xl pointer-events-none">
                How many recent messages are sent with each LLM request. Older messages are dropped when the limit is reached to stay within the context window.
              </span>
            </span>
          </span>
          <template v-if="editingChatField === 'maxContextMessages'">
            <input
              v-model="chatFieldEdit"
              type="number"
              min="1"
              max="500"
              aria-label="Max context messages"
              class="w-24 px-2 py-1 bg-muted border border-input text-sm text-fg-strong font-mono focus:outline-hidden"
            >
            <button
              class="p-1 text-fg-muted hover:text-emerald-700 dark:hover:text-emerald-400 transition-colors"
              title="Save"
              @click="saveChatField('chat.maxContextMessages', chatFieldEdit)"
            >
              <CheckIcon
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
            </button>
            <button
              class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
              title="Cancel"
              @click="editingChatField = null"
            >
              <XMarkIcon
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
            </button>
          </template>
          <template v-else>
            <span class="flex-1 text-sm text-fg-primary font-mono">{{ chatMaxContextMessages }} messages</span>
            <button
              class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
              title="Edit"
              @click="editingChatField = 'maxContextMessages'; chatFieldEdit = chatMaxContextMessages"
            >
              <PencilIcon
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
            </button>
          </template>
        </div>
      </div>
    </div>

    <!-- Advanced — context window & compaction. Collapsed by default; these
         knobs trade off conversational continuity against context-budget
         safety, and the defaults are deliberately conservative. Surface them
         because non-OpenAI providers (Kimi, DeepSeek, Gemma, Qwen, GLM,
         Mistral, Llama) have systematic tokenizer-bias gaps the safety
         multiplier compensates for; the compaction trio also lets operators
         tune when summary-vs-trim takes over. -->
    <div class="bg-surface-elevated border border-border">
      <button
        class="w-full px-4 py-2.5 flex items-center gap-2 text-xs text-fg-muted hover:text-fg-strong transition-colors"
        :aria-expanded="showAdvancedChat"
        @click="showAdvancedChat = !showAdvancedChat"
      >
        <ChevronUpIcon
          class="w-3.5 h-3.5 transition-transform"
          :class="{ 'rotate-180': !showAdvancedChat }"
          aria-hidden="true"
        />
        <span class="font-medium">Advanced — context window & compaction</span>
      </button>
      <div
        v-if="showAdvancedChat"
        class="divide-y divide-border border-t border-border"
      >
        <!-- chat.compactionReserveTokens -->
        <div class="px-4 py-2.5 flex items-center gap-3">
          <span class="text-xs font-mono text-fg-muted w-60 shrink-0 flex items-center gap-1.5">
            compactionReserveTokens
            <span class="relative group/tip">
              <InformationCircleIcon
                class="w-3 h-3 text-fg-muted group-hover/tip:text-fg-muted cursor-help transition-colors"
                aria-hidden="true"
              />
              <span class="absolute left-0 top-5 z-20 hidden group-hover/tip:block w-72 px-2.5 py-2 bg-muted border border-input text-[10px] text-fg-muted leading-relaxed shadow-xl pointer-events-none">
                Tokens reserved at the end of the context window for the assistant reply. Auto-compaction triggers when the next prompt would exceed contextWindow minus this reserve. Larger reserve = compaction fires sooner.
              </span>
            </span>
          </span>
          <template v-if="editingChatField === 'compactionReserveTokens'">
            <input
              v-model="chatFieldEdit"
              type="number"
              min="1000"
              max="100000"
              step="1000"
              aria-label="Compaction reserve tokens"
              class="w-28 px-2 py-1 bg-muted border border-input text-sm text-fg-strong font-mono focus:outline-hidden"
            >
            <button
              class="p-1 text-fg-muted hover:text-emerald-700 dark:hover:text-emerald-400 transition-colors"
              title="Save"
              @click="saveChatField('chat.compactionReserveTokens', chatFieldEdit)"
            >
              <CheckIcon
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
            </button>
            <button
              class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
              title="Cancel"
              @click="editingChatField = null"
            >
              <XMarkIcon
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
            </button>
          </template>
          <template v-else>
            <span class="flex-1 text-sm text-fg-primary font-mono">{{ compactionReserveTokens }} tokens</span>
            <button
              class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
              title="Edit"
              @click="editingChatField = 'compactionReserveTokens'; chatFieldEdit = compactionReserveTokens"
            >
              <PencilIcon
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
            </button>
          </template>
        </div>
        <!-- chat.compactionMinTurns -->
        <div class="px-4 py-2.5 flex items-center gap-3">
          <span class="text-xs font-mono text-fg-muted w-60 shrink-0 flex items-center gap-1.5">
            compactionMinTurns
            <span class="relative group/tip">
              <InformationCircleIcon
                class="w-3 h-3 text-fg-muted group-hover/tip:text-fg-muted cursor-help transition-colors"
                aria-hidden="true"
              />
              <span class="absolute left-0 top-5 z-20 hidden group-hover/tip:block w-72 px-2.5 py-2 bg-muted border border-input text-[10px] text-fg-muted leading-relaxed shadow-xl pointer-events-none">
                Minimum messages in the to-summarize prefix for auto-compaction. Below this, the gate skips and trim drops oldest instead. Manual /compact uses the relaxed forced threshold (default 2).
              </span>
            </span>
          </span>
          <template v-if="editingChatField === 'compactionMinTurns'">
            <input
              v-model="chatFieldEdit"
              type="number"
              min="1"
              max="50"
              aria-label="Compaction min turns"
              class="w-24 px-2 py-1 bg-muted border border-input text-sm text-fg-strong font-mono focus:outline-hidden"
            >
            <button
              class="p-1 text-fg-muted hover:text-emerald-700 dark:hover:text-emerald-400 transition-colors"
              title="Save"
              @click="saveChatField('chat.compactionMinTurns', chatFieldEdit)"
            >
              <CheckIcon
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
            </button>
            <button
              class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
              title="Cancel"
              @click="editingChatField = null"
            >
              <XMarkIcon
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
            </button>
          </template>
          <template v-else>
            <span class="flex-1 text-sm text-fg-primary font-mono">{{ compactionMinTurns }} messages</span>
            <button
              class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
              title="Edit"
              @click="editingChatField = 'compactionMinTurns'; chatFieldEdit = compactionMinTurns"
            >
              <PencilIcon
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
            </button>
          </template>
        </div>
        <!-- chat.compactionKeepMessages -->
        <div class="px-4 py-2.5 flex items-center gap-3">
          <span class="text-xs font-mono text-fg-muted w-60 shrink-0 flex items-center gap-1.5">
            compactionKeepMessages
            <span class="relative group/tip">
              <InformationCircleIcon
                class="w-3 h-3 text-fg-muted group-hover/tip:text-fg-muted cursor-help transition-colors"
                aria-hidden="true"
              />
              <span class="absolute left-0 top-5 z-20 hidden group-hover/tip:block w-72 px-2.5 py-2 bg-muted border border-input text-[10px] text-fg-muted leading-relaxed shadow-xl pointer-events-none">
                Minimum messages kept verbatim at the end of the conversation after compaction. Below this, the gate finds no safe boundary and skips. Smaller keep = more aggressive summarization.
              </span>
            </span>
          </span>
          <template v-if="editingChatField === 'compactionKeepMessages'">
            <input
              v-model="chatFieldEdit"
              type="number"
              min="1"
              max="50"
              aria-label="Compaction keep messages"
              class="w-24 px-2 py-1 bg-muted border border-input text-sm text-fg-strong font-mono focus:outline-hidden"
            >
            <button
              class="p-1 text-fg-muted hover:text-emerald-700 dark:hover:text-emerald-400 transition-colors"
              title="Save"
              @click="saveChatField('chat.compactionKeepMessages', chatFieldEdit)"
            >
              <CheckIcon
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
            </button>
            <button
              class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
              title="Cancel"
              @click="editingChatField = null"
            >
              <XMarkIcon
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
            </button>
          </template>
          <template v-else>
            <span class="flex-1 text-sm text-fg-primary font-mono">{{ compactionKeepMessages }} messages</span>
            <button
              class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
              title="Edit"
              @click="editingChatField = 'compactionKeepMessages'; chatFieldEdit = compactionKeepMessages"
            >
              <PencilIcon
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
            </button>
          </template>
        </div>
        <!-- jtokkit.safetyMultiplier.unmatched -->
        <div class="px-4 py-2.5 flex items-center gap-3">
          <span class="text-xs font-mono text-fg-muted w-60 shrink-0 flex items-center gap-1.5">
            jtokkit safety multiplier
            <span class="relative group/tip">
              <InformationCircleIcon
                class="w-3 h-3 text-fg-muted group-hover/tip:text-fg-muted cursor-help transition-colors"
                aria-hidden="true"
              />
              <span class="absolute left-0 top-5 z-20 hidden group-hover/tip:block w-72 px-2.5 py-2 bg-muted border border-input text-[10px] text-fg-muted leading-relaxed shadow-xl pointer-events-none">
                Multiplier applied to jtokkit's token estimate when the model uses a fallback encoding (non-OpenAI providers like Kimi, DeepSeek, Gemma, Qwen, GLM). Higher = trim/compact earlier, safer. Lower = closer to raw estimate, more provider-rejection risk. OpenAI-family models use 1.0 regardless.
              </span>
            </span>
          </span>
          <template v-if="editingChatField === 'jtokkitSafetyMultiplier'">
            <input
              v-model="chatFieldEdit"
              type="number"
              min="1.0"
              max="2.0"
              step="0.1"
              aria-label="jtokkit safety multiplier"
              class="w-24 px-2 py-1 bg-muted border border-input text-sm text-fg-strong font-mono focus:outline-hidden"
            >
            <button
              class="p-1 text-fg-muted hover:text-emerald-700 dark:hover:text-emerald-400 transition-colors"
              title="Save"
              @click="saveChatField('jtokkit.safetyMultiplier.unmatched', chatFieldEdit)"
            >
              <CheckIcon
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
            </button>
            <button
              class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
              title="Cancel"
              @click="editingChatField = null"
            >
              <XMarkIcon
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
            </button>
          </template>
          <template v-else>
            <span class="flex-1 text-sm text-fg-primary font-mono">{{ jtokkitSafetyMultiplier }}×</span>
            <button
              class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
              title="Edit"
              @click="editingChatField = 'jtokkitSafetyMultiplier'; chatFieldEdit = jtokkitSafetyMultiplier"
            >
              <PencilIcon
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
            </button>
          </template>
        </div>
      </div>
    </div>
  </div>
</template>
