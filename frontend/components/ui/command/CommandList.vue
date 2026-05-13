<script setup lang="ts">
import type { ListboxContentProps } from "reka-ui"
import type { HTMLAttributes } from "vue"
import { reactiveOmit } from "@vueuse/core"
import { ListboxContent, useForwardProps } from "reka-ui"
import { cn } from "~/composables/ui-utils"

const props = defineProps<ListboxContentProps & { class?: HTMLAttributes["class"] }>()

const delegatedProps = reactiveOmit(props, "class")

const forwarded = useForwardProps(delegatedProps)
</script>

<template>
  <ListboxContent
    data-slot="command-list"
    v-bind="forwarded"
    :class="cn('max-h-[300px] scroll-py-1 overflow-x-hidden overflow-y-auto', props.class)"
  >
    <!-- NOSONAR(Web:S6819) — role="presentation" intentionally strips implicit semantics from the slot wrapper so the parent ListboxContent's listbox role is the only one assistive tech sees; no native HTML element provides this behavior. -->
    <div role="presentation">
      <slot />
    </div>
  </ListboxContent>
</template>
