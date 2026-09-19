# JCLAW-1226 — doc fragment

Two paragraphs for the integration agent to merge into `AGENTS.md`.

---

## 1. For **Known pitfalls** (append as a bullet, after the Personal Edition bullet)

- A standing tool-approval grant is the *operator's* approval, and `DangerousActionGate`
  enforces both halves of that. It is only spendable on an owner-initiated turn or one whose
  effective origin classifies as `OPERATOR` — the grant key is `(agentId, toolName)` with no
  channel in it, so before JCLAW-1226 an "Always" tap in the owner's DM also covered a group
  guest's turn on the same agent. And `hasStandingGrant` is an OR over a never-pruned static
  set and the `tool_approval_grant` table, so deleting the row alone can only fail open:
  every revoke path pairs `ToolApprovalGrant.revoke` with
  `DangerousActionGate.revokeGrant` (the endpoint) or `revokeGrantsForAgent` (the delete
  cascade, where the rows go by `ON DELETE CASCADE`). The row records the granting channel
  and peer as provenance only; the gate never reads them.

## 2. For **Wall clock — AppClock → Propagation boundary** (append after the `ScopedValue` paragraph)

`InheritableThreadLocal` has the *opposite* boundary, and that asymmetry has bitten once.
`DangerousActionGate`'s `OWNER_INITIATED` and the task-fire origin are the only two in `app/`,
and both are deliberately inheritable so a subagent fork inherits the turn's trust as a floor.
But `Thread.ofVirtual()` inherits them by default, so a fork that starts work for a *different*
sender must opt out: `QueueDrainOrchestrator.startDrainThread` builds the `agent-drain` thread
with `inheritInheritableThreadLocals(false)`, because a guest message queued behind an owner's
turn would otherwise be drained carrying the owner's identity (JCLAW-1226). A new fork that
crosses a sender boundary needs the same flag.
