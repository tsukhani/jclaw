import type { Message } from '~/types/api'

/**
 * Server-id reconciliation for optimistic chat rows (JCLAW-690 stage 4e; pure
 * logic extracted verbatim from pages/chat.vue). Kept as pure functions here so
 * the subtle role-stack pairing — the part most likely to regress — is
 * unit-testable without mounting the page or faking a stream. The impure
 * orchestrator (reconcileMessageIds: fetch + messages shallowRef + triggerRef)
 * stays in the page and calls {@link backfillServerIds}.
 *
 * Pair id-less local rows with their server-side counterparts by role,
 * mutating local rows to carry the server id. Used both at end-of-stream
 * (reconcileMessageIds) and inside the announce poll loop so subsequent
 * id-membership checks dedupe correctly.
 *
 * Algorithm: build a per-role LIFO stack of unpaired server ids, then walk
 * local rows back to front and pop the latest matching-role server id off
 * the stack. Server rows whose ids are already present locally are excluded
 * from the stacks up front so they can't be re-stolen.
 *
 * Earlier versions walked both lists backwards in lockstep and broke on the
 * first role mismatch — that fell over when the server list contained
 * intermediate {@code tool} / {@code system} rows the local list never
 * tracked (e.g., a JCLAW-270 async subagent that injects a system-role
 * announce between the parent's user turn and the parent's final assistant
 * reply). The role-stack walk skips those intermediates without false
 * pairings: a local row whose role's stack runs dry stays id-less.
 */

/** Set of message ids already present on local rows. */
export function collectLocalTakenIds(local: Message[]): Set<number> {
  const ids = new Set<number>()
  for (const L of local) {
    if (typeof L.id === 'number') ids.add(L.id)
  }
  return ids
}

/**
 * Build a per-role LIFO stack of unpaired server ids, skipping any id
 * already present locally so it can't be re-stolen.
 */
export function groupUnpairedServerIdsByRole(fresh: Message[], localTakenIds: Set<number>): Map<string, number[]> {
  const idsByRole = new Map<string, number[]>()
  for (const R of fresh) {
    if (typeof R.id !== 'number' || !R.role) continue
    if (localTakenIds.has(R.id)) continue
    const list = idsByRole.get(R.role) ?? []
    list.push(R.id)
    idsByRole.set(R.role, list)
  }
  return idsByRole
}

/** Index server rows by id for fast post-pairing flag-copy lookups. */
export function indexFreshById(fresh: Message[]): Map<number, Message> {
  const freshById = new Map<number, Message>()
  for (const R of fresh) {
    if (typeof R.id === 'number') freshById.set(R.id, R)
  }
  return freshById
}

export function backfillServerIds(local: Message[], fresh: Message[]): boolean {
  const localTakenIds = collectLocalTakenIds(local)
  const idsByRole = groupUnpairedServerIdsByRole(fresh, localTakenIds)
  // Index server rows by id so the post-pairing pass below can also copy
  // server-side flags (currently just JCLAW-291 truncated) onto the local
  // optimistic rows. Without this the in-flight assistant bubble would
  // never gain its truncation marker until a hard reload.
  const freshById = indexFreshById(fresh)
  let mutated = false
  for (let li = local.length - 1; li >= 0; li--) {
    const L = local[li]
    if (!L || L.id || !L.role) continue
    const list = idsByRole.get(L.role)
    if (!list?.length) continue
    L.id = list.pop()!
    const R = freshById.get(L.id)
    if (R?.truncated) L.truncated = true
    mutated = true
  }
  return mutated
}
