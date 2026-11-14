import type { HolderState, SignInHolder } from '~/types/api'

/** A skill whose sign-in fills names another skill needs (JF-374), those names, and where that skill stands. */
export interface SignInGroup {
  holder: string
  names: string[]
  state: HolderState
}

/** The names of `needs` another skill's sign-in fills, grouped by that skill in the order `needs` first names it. */
export function signInGroups(needs: string[], filledBySignIn: Record<string, SignInHolder>): SignInGroup[] {
  const groups = new Map<string, SignInGroup>()
  for (const name of needs) {
    const filling = filledBySignIn[name]
    if (!filling) continue
    const group = groups.get(filling.skill) ?? { holder: filling.skill, names: [], state: filling.state }
    group.names.push(name)
    groups.set(filling.skill, group)
  }
  return [...groups.values()]
}

/** What follows a group's names, as the agent is told it: whose sign-in fills them, and what lets it run. */
export function signInClause({ holder, names, state }: SignInGroup): string {
  const them = names.length === 1 ? 'it' : 'them'
  const lead = `filled by ${holder}'s sign-in`
  if (state === 'switchedOff') return `${lead}, and ${holder} is switched off: switch ${holder} on so its sign-in can fill ${them}`
  if (state === 'notOffered') return `${lead}, and ${holder} is not offered right now: once it is, its sign-in can fill ${them}`
  return `${lead}, which has not run yet: once it has, this skill can use ${them}`
}
