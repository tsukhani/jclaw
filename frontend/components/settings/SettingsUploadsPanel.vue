<script setup lang="ts">
import { CheckIcon, InformationCircleIcon, PencilIcon, XMarkIcon } from '@heroicons/vue/24/outline'

// JCLAW-131: Uploads settings — per-kind attachment size caps. Values are
// stored in bytes in the DB (matches services/UploadLimits.java) but
// surfaced as MB in the UI, where operators actually think. Defaults match
// the server-side constants so a fresh install with no rows shows the real
// effective limits.
const { configValue, saveField } = useSettingsConfig()

const DEFAULT_MAX_IMAGE_MB = 20
const DEFAULT_MAX_AUDIO_MB = 100
const DEFAULT_MAX_FILE_MB = 100
// Hard ceilings — operators can lower these (be stricter) but not raise
// them above the defaults. Must stay in sync with the server-side constants
// in services/UploadLimits.java so the UI never lets the operator set a
// value the backend would silently clamp.
const MAX_IMAGE_MB = 20
const MAX_AUDIO_MB = 100
const MAX_FILE_MB = 100

// Per-message upload count cap (mirrors services/UploadLimits.java ABSOLUTE_MAX_FILES).
const DEFAULT_MAX_FILES = 5
const MAX_FILES = 5

function bytesToMb(raw: string | undefined, fallback: number): string {
  if (!raw) return String(fallback)
  const bytes = Number.parseInt(raw, 10)
  if (!Number.isFinite(bytes) || bytes <= 0) return String(fallback)
  return String(Math.round(bytes / (1024 * 1024)))
}

const uploadMaxImageMb = computed(() =>
  bytesToMb(configValue('upload.maxImageBytes') || undefined, DEFAULT_MAX_IMAGE_MB),
)
const uploadMaxAudioMb = computed(() =>
  bytesToMb(configValue('upload.maxAudioBytes') || undefined, DEFAULT_MAX_AUDIO_MB),
)
const uploadMaxFileMb = computed(() =>
  bytesToMb(configValue('upload.maxFileBytes') || undefined, DEFAULT_MAX_FILE_MB),
)
const uploadMaxFiles = computed(() => {
  const raw = configValue('upload.maxFiles')
  if (!raw) return String(DEFAULT_MAX_FILES)
  const n = Number.parseInt(raw, 10)
  if (!Number.isFinite(n) || n < 1) return '1'
  if (n > MAX_FILES) return String(MAX_FILES)
  return String(n)
})

const editingUploadField = ref<string | null>(null)
const uploadFieldEdit = ref('')

async function saveUploadMb(configKey: string, mbValue: string, hardMax: number) {
  const mb = Math.max(1, Math.min(hardMax, Number.parseInt(mbValue, 10) || 0))
  const bytes = mb * 1024 * 1024
  await saveField(configKey, String(bytes))
  editingUploadField.value = null
}

async function saveUploadCount(value: string) {
  const n = Math.max(1, Math.min(MAX_FILES, Number.parseInt(value, 10) || 1))
  await saveField('upload.maxFiles', String(n))
  editingUploadField.value = null
}
</script>

<template>
  <!-- Uploads (JCLAW-131) -->
  <div class="mb-6 space-y-4">
    <h2 class="text-sm font-medium text-fg-muted">
      Uploads
    </h2>
    <p class="text-xs text-fg-muted">
      Per-kind attachment size caps and per-message file count. The sniffed MIME
      decides which size limit applies — images, audio, or everything else. Takes
      effect without a restart; raise <code class="font-mono">play.netty.maxContentLength</code>
      in <code class="font-mono">conf/application.conf</code> if you need over the bundled 512 MB
      transport-layer ceiling.
    </p>
    <div class="bg-surface-elevated border border-border">
      <div class="divide-y divide-border">
        <div class="px-4 py-2.5 flex items-center gap-3">
          <span class="text-xs font-mono text-fg-muted w-48 shrink-0 flex items-center gap-1.5">
            maxImageBytes
            <span class="relative group/tip">
              <InformationCircleIcon
                class="w-3 h-3 text-fg-muted group-hover/tip:text-fg-muted cursor-help transition-colors"
                aria-hidden="true"
              />
              <span class="absolute left-0 top-5 z-20 hidden group-hover/tip:block w-64 px-2.5 py-2 bg-muted border border-input text-[10px] text-fg-muted leading-relaxed shadow-xl pointer-events-none">
                Max upload size for image attachments, in megabytes (1–{{ MAX_IMAGE_MB }}). Stored as bytes; most vision models accept up to 20 MB per image.
              </span>
            </span>
          </span>
          <template v-if="editingUploadField === 'maxImageMb'">
            <input
              v-model="uploadFieldEdit"
              type="number"
              min="1"
              :max="MAX_IMAGE_MB"
              aria-label="Max image upload MB"
              class="w-24 px-2 py-1 bg-muted border border-input text-sm text-fg-strong font-mono focus:outline-hidden"
            >
            <button
              class="p-1 text-fg-muted hover:text-emerald-700 dark:hover:text-emerald-400 transition-colors"
              title="Save"
              @click="saveUploadMb('upload.maxImageBytes', uploadFieldEdit, MAX_IMAGE_MB)"
            >
              <CheckIcon
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
            </button>
            <button
              class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
              title="Cancel"
              @click="editingUploadField = null"
            >
              <XMarkIcon
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
            </button>
          </template>
          <template v-else>
            <span class="flex-1 text-sm text-fg-primary font-mono">{{ uploadMaxImageMb }} MB</span>
            <button
              class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
              title="Edit"
              @click="editingUploadField = 'maxImageMb'; uploadFieldEdit = uploadMaxImageMb"
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
            maxAudioBytes
            <span class="relative group/tip">
              <InformationCircleIcon
                class="w-3 h-3 text-fg-muted group-hover/tip:text-fg-muted cursor-help transition-colors"
                aria-hidden="true"
              />
              <span class="absolute left-0 top-5 z-20 hidden group-hover/tip:block w-64 px-2.5 py-2 bg-muted border border-input text-[10px] text-fg-muted leading-relaxed shadow-xl pointer-events-none">
                Max upload size for audio attachments, in megabytes (1–{{ MAX_AUDIO_MB }}). 100 MB holds roughly an hour of 128 kbps recording.
              </span>
            </span>
          </span>
          <template v-if="editingUploadField === 'maxAudioMb'">
            <input
              v-model="uploadFieldEdit"
              type="number"
              min="1"
              :max="MAX_AUDIO_MB"
              aria-label="Max audio upload MB"
              class="w-24 px-2 py-1 bg-muted border border-input text-sm text-fg-strong font-mono focus:outline-hidden"
            >
            <button
              class="p-1 text-fg-muted hover:text-emerald-700 dark:hover:text-emerald-400 transition-colors"
              title="Save"
              @click="saveUploadMb('upload.maxAudioBytes', uploadFieldEdit, MAX_AUDIO_MB)"
            >
              <CheckIcon
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
            </button>
            <button
              class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
              title="Cancel"
              @click="editingUploadField = null"
            >
              <XMarkIcon
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
            </button>
          </template>
          <template v-else>
            <span class="flex-1 text-sm text-fg-primary font-mono">{{ uploadMaxAudioMb }} MB</span>
            <button
              class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
              title="Edit"
              @click="editingUploadField = 'maxAudioMb'; uploadFieldEdit = uploadMaxAudioMb"
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
            maxFileBytes
            <span class="relative group/tip">
              <InformationCircleIcon
                class="w-3 h-3 text-fg-muted group-hover/tip:text-fg-muted cursor-help transition-colors"
                aria-hidden="true"
              />
              <span class="absolute left-0 top-5 z-20 hidden group-hover/tip:block w-64 px-2.5 py-2 bg-muted border border-input text-[10px] text-fg-muted leading-relaxed shadow-xl pointer-events-none">
                Max upload size for every other attachment type (PDFs, text, archives, etc.), in megabytes (1–{{ MAX_FILE_MB }}).
              </span>
            </span>
          </span>
          <template v-if="editingUploadField === 'maxFileMb'">
            <input
              v-model="uploadFieldEdit"
              type="number"
              min="1"
              :max="MAX_FILE_MB"
              aria-label="Max file upload MB"
              class="w-24 px-2 py-1 bg-muted border border-input text-sm text-fg-strong font-mono focus:outline-hidden"
            >
            <button
              class="p-1 text-fg-muted hover:text-emerald-700 dark:hover:text-emerald-400 transition-colors"
              title="Save"
              @click="saveUploadMb('upload.maxFileBytes', uploadFieldEdit, MAX_FILE_MB)"
            >
              <CheckIcon
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
            </button>
            <button
              class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
              title="Cancel"
              @click="editingUploadField = null"
            >
              <XMarkIcon
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
            </button>
          </template>
          <template v-else>
            <span class="flex-1 text-sm text-fg-primary font-mono">{{ uploadMaxFileMb }} MB</span>
            <button
              class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
              title="Edit"
              @click="editingUploadField = 'maxFileMb'; uploadFieldEdit = uploadMaxFileMb"
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
            maxFiles
            <span class="relative group/tip">
              <InformationCircleIcon
                class="w-3 h-3 text-fg-muted group-hover/tip:text-fg-muted cursor-help transition-colors"
                aria-hidden="true"
              />
              <span class="absolute left-0 top-5 z-20 hidden group-hover/tip:block w-64 px-2.5 py-2 bg-muted border border-input text-[10px] text-fg-muted leading-relaxed shadow-xl pointer-events-none">
                Maximum number of files a user can attach to a single chat message (1–{{ MAX_FILES }}). Capped at 5 system-wide; this lets operators be stricter.
              </span>
            </span>
          </span>
          <template v-if="editingUploadField === 'maxFiles'">
            <input
              v-model="uploadFieldEdit"
              type="number"
              min="1"
              :max="MAX_FILES"
              aria-label="Max files per message"
              class="w-24 px-2 py-1 bg-muted border border-input text-sm text-fg-strong font-mono focus:outline-hidden"
            >
            <button
              class="p-1 text-fg-muted hover:text-emerald-700 dark:hover:text-emerald-400 transition-colors"
              title="Save"
              @click="saveUploadCount(uploadFieldEdit)"
            >
              <CheckIcon
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
            </button>
            <button
              class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
              title="Cancel"
              @click="editingUploadField = null"
            >
              <XMarkIcon
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
            </button>
          </template>
          <template v-else>
            <span class="flex-1 text-sm text-fg-primary font-mono">{{ uploadMaxFiles }} files</span>
            <button
              class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
              title="Edit"
              @click="editingUploadField = 'maxFiles'; uploadFieldEdit = uploadMaxFiles"
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
