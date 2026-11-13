<script setup lang="ts">
/**
 * JF-327: the agent's skills it is not offered because a secret or setting they need has no value, each with a link
 * to that skill's Secrets panel on the Skills page. The agent is told the same names in each turn the operator starts,
 * so it can say why it cannot do what one of them does; this lets the operator act on it in one click. A value another
 * skill holds is fixed on that skill, so its link opens the holder instead.
 *
 * Every link is built here from the agent id and a skill folder, never from anything the model wrote.
 */
export interface WithheldSkill {
  folder: string
  needs: string[]
  /** For each of `needs` another skill of the agent holds whose switch is off, that skill. */
  heldBySwitchedOff: Record<string, string>
  /** For each of `needs` another skill of the agent holds that is on but not offered, that skill. */
  heldByNotOffered: Record<string, string>
}

const props = defineProps<{
  agentId: number
  skills: WithheldSkill[]
}>()

const items = computed(() => props.skills.map((skill) => {
  const toAdd: string[] = []
  const held = new Map<string, { names: string[], switchedOff: boolean }>()
  for (const name of skill.needs) {
    const off = skill.heldBySwitchedOff[name]
    const holder = off ?? skill.heldByNotOffered[name]
    if (!holder) {
      toAdd.push(name)
      continue
    }
    const group = held.get(holder) ?? { names: [], switchedOff: off != null }
    group.names.push(name)
    held.set(holder, group)
  }
  return {
    folder: skill.folder,
    toAdd: toAdd.join(', '),
    held: [...held].map(([holder, group]) => ({ holder, names: group.names.join(', '), switchedOff: group.switchedOff })),
  }
}))

function linkTo(folder: string) {
  return { path: '/skills', query: { agent: String(props.agentId), skill: folder } }
}
</script>

<template>
  <div
    data-testid="withheld-skills-notice"
    class="mx-auto w-full max-w-3xl px-4 pt-3"
  >
    <div
      class="flex items-start gap-2 rounded border border-amber-300 bg-amber-50 px-3 py-2 text-xs
             text-amber-800 dark:border-amber-700/40 dark:bg-amber-900/20 dark:text-amber-300"
      role="status"
    >
      <span class="font-mono uppercase tracking-wide shrink-0">Needs values</span>
      <ul class="flex flex-col gap-1 min-w-0">
        <li
          v-for="item in items"
          :key="item.folder"
          :data-testid="`withheld-skill-${item.folder}`"
        >
          <template v-if="item.toAdd">
            <strong class="font-mono">{{ item.folder }}</strong> isn't offered to this agent until it has
            <span class="font-mono break-all">{{ item.toAdd }}</span>.
            <NuxtLink
              :to="linkTo(item.folder)"
              class="underline hover:no-underline"
              :data-testid="`withheld-skill-link-${item.folder}`"
            >
              Open Secrets panel<span class="sr-only"> of {{ item.folder }}</span>
            </NuxtLink>
          </template>
          <template v-else>
            <strong class="font-mono">{{ item.folder }}</strong> isn't offered to this agent.
          </template>
          <template
            v-for="group in item.held"
            :key="group.holder"
          >
            It needs <span class="font-mono break-all">{{ group.names }}</span>, set in
            <span class="font-mono">{{ group.holder }}</span>,
            {{ group.switchedOff ? 'which is switched off' : 'which is not offered right now (a tool it needs is off)' }}.
            <NuxtLink
              :to="linkTo(group.holder)"
              class="underline hover:no-underline"
              :data-testid="`withheld-holder-link-${item.folder}-${group.holder}`"
            >
              Open {{ group.holder }}<span class="sr-only"> for {{ item.folder }}</span>
            </NuxtLink>
          </template>
        </li>
      </ul>
    </div>
  </div>
</template>
