<script setup lang="ts">
/**
 * One ```structure fence drawn in 3D (JCLAW-1321). utils/structure/directive.ts mounts it into a finished message's
 * figure while the figure is near the viewport; the viewer itself is borrowed from a pool (see utils/structure/viewer).
 */
import { computed, onBeforeUnmount, onMounted, reactive, ref, useId, watch } from 'vue'
import { workspaceFileUrl, type StructureSource } from '~/utils/structure/fence'
import { acquireViewer, buildScene, fetchStructureFile, type Lease, type SceneInfo, type ViewerOptions } from '~/utils/structure/viewer'

const props = defineProps<{ source: StructureSource, agentId: number | null, initial: ViewerOptions }>()
const emit = defineEmits<{ failed: [message: string], change: [options: ViewerOptions] }>()

const uid = useId()
const host = ref<HTMLElement>()
const status = ref<'loading' | 'ready' | 'evicted'>('loading')
const scene = ref<SceneInfo | null>(null)
const options = reactive<ViewerOptions>({ ...props.initial })
let lease: Lease | null = null
let text: string | null = null
let unmounted = false

const fileUrl = computed(() => props.source.kind === 'file' && props.agentId != null
  ? workspaceFileUrl(props.agentId, props.source.path)
  : null)
const label = computed(() => {
  const subject = props.source.caption ? ` of ${props.source.caption}` : ''
  return `3D structure${subject}, drag to rotate`
})

// 3Dmol wants concrete colors; reading them from the canvas frame keeps the view in step with the theme.
function hex(cssColor: string): string {
  const [r = 0, g = 0, b = 0] = (cssColor.match(/\d+/g) ?? []).map(Number)
  return `#${[r, g, b].map(v => v.toString(16).padStart(2, '0')).join('')}`
}

function draw() {
  const style = getComputedStyle(host.value!.parentElement!)
  scene.value = buildScene(lease!.viewer, props.source.format, text!, options,
    { ink: hex(style.color), background: hex(style.backgroundColor) })
}

function fail(err: unknown) {
  lease?.release()
  lease = null
  emit('failed', err instanceof Error ? err.message : String(err))
}

async function start() {
  status.value = 'loading'
  try {
    if (text === null) {
      if (props.source.kind === 'inline') text = props.source.text
      else if (fileUrl.value) text = await fetchStructureFile(fileUrl.value, props.source.path)
      else throw new Error('This structure names a workspace file, but the message has no agent to read it from.')
    }
    const borrowed = await acquireViewer(host.value!, () => {
      lease = null
      status.value = 'evicted'
    })
    if (unmounted) {
      borrowed.release()
      return
    }
    lease = borrowed
    draw()
    status.value = 'ready'
  }
  catch (err) {
    if (!unmounted) fail(err)
  }
}

watch(options, () => {
  emit('change', { ...options })
  if (!lease) return
  try {
    draw()
  }
  catch (err) {
    fail(err)
  }
})

onMounted(start)
onBeforeUnmount(() => {
  unmounted = true
  lease?.release()
})

const cellChoices = [1, 2, 3] as const
const toggle = 'px-2 py-0.5 transition-colors'
const pressed = (on: boolean) => on ? 'bg-muted text-fg-strong' : 'text-fg-muted hover:text-fg-strong'
</script>

<template>
  <div class="structure-viewer">
    <div
      class="structure-canvas"
      role="img"
      :aria-label="label"
    >
      <!-- Holds the pooled viewer's container, which moves between figures; Vue renders no children here. -->
      <div
        ref="host"
        class="structure-gl-host"
      />
      <p
        v-if="status === 'loading'"
        class="structure-status"
      >
        Loading 3D view…
      </p>
      <p
        v-else-if="status === 'evicted'"
        class="structure-status"
      >
        <button
          type="button"
          class="underline hover:text-fg-strong"
          @click="start"
        >
          Show 3D view
        </button>
      </p>
    </div>
    <div
      v-if="status === 'ready' && scene"
      class="structure-toolbar"
      role="toolbar"
      aria-label="3D view controls"
    >
      <div
        class="inline-flex border border-input divide-x divide-border"
        role="group"
        aria-label="Atom style"
      >
        <button
          type="button"
          :class="[toggle, pressed(options.style === 'ball')]"
          :aria-pressed="options.style === 'ball'"
          @click="options.style = 'ball'"
        >
          Ball &amp; stick
        </button>
        <button
          type="button"
          :class="[toggle, pressed(options.style === 'space')]"
          :aria-pressed="options.style === 'space'"
          @click="options.style = 'space'"
        >
          Space-filling
        </button>
      </div>
      <template v-if="scene.crystal">
        <div
          class="inline-flex border border-input divide-x divide-border"
          role="group"
          aria-label="Cells shown"
        >
          <button
            v-for="n in cellChoices"
            :key="n"
            type="button"
            :class="[toggle, pressed(options.cells === n)]"
            :aria-pressed="options.cells === n"
            :disabled="n > scene.maxCells"
            :title="n > scene.maxCells ? 'Too many atoms to draw' : undefined"
            @click="options.cells = n"
          >
            {{ n }}×{{ n }}×{{ n }}
          </button>
        </div>
        <label
          :for="`${uid}-cell`"
          class="inline-flex items-center gap-1.5"
        >
          <input
            :id="`${uid}-cell`"
            v-model="options.cellBox"
            type="checkbox"
          >
          Unit cell
        </label>
      </template>
      <label
        v-if="scene.polyhedra"
        :for="`${uid}-polyhedra`"
        class="inline-flex items-center gap-1.5"
      >
        <input
          :id="`${uid}-polyhedra`"
          v-model="options.polyhedra"
          type="checkbox"
        >
        Coordination polyhedra
      </label>
      <a
        v-if="fileUrl && source.kind === 'file'"
        :href="fileUrl"
        class="workspace-file"
        download
      >{{ source.path.split('/').pop() }}</a>
    </div>
  </div>
</template>
