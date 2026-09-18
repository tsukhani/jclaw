import type { Message } from '~/types/api'
import type { MessageRoute } from '~/utils/usage-cost'

/** The provider name the backend's ModelRouter.PROVIDER lists the router under. */
export const ROUTER_PROVIDER = 'router'
/** The router's one model id, ModelRouter.MODEL_ID. */
export const ROUTER_MODEL_ID = 'auto'

/** Operator-facing names for the router's task classes, in the order Settings lists them. */
export const ROUTE_CLASS_LABELS: Record<string, string> = {
  chat: 'Chat',
  summarize: 'Summarize',
  agentic: 'Agent work',
  reasoning: 'Reasoning',
  coding: 'Coding',
}

/**
 * The router's choice for an assistant turn (JCLAW-1222): the persisted usage record once it has
 * landed, the stream's status frame before that. Null for a turn not on the router.
 */
export function routeOf(msg: Message): MessageRoute | null {
  return msg.usage?.route ?? msg._route ?? null
}

/**
 * The most recent routed turn in a conversation, or null when no turn has been routed yet. On an
 * Auto conversation this is the model the composer's capability pills describe: the router picks
 * per prompt, so before the first route frame there is no model to describe.
 */
export function latestRouteOf(messages: readonly Message[]): MessageRoute | null {
  for (let i = messages.length - 1; i >= 0; i--) {
    const route = routeOf(messages[i]!)
    if (route) return route
  }
  return null
}

export function routeClassLabel(route: MessageRoute): string {
  return ROUTE_CLASS_LABELS[route.class] ?? route.class
}

/** One sentence for a tooltip or a screen reader: the class, the model, and whatever bent the choice. */
export function routeDescription(route: MessageRoute): string {
  const parts = [`Auto router: ${routeClassLabel(route)} → ${route.provider}/${route.model}`]
  if (route.failover) parts.push('the first choice failed, so its fallback answered')
  if (route.downshifted) parts.push('moved to a lighter model to save subscription credit')
  if (route.reason) parts.push(route.reason)
  return parts.join(' · ')
}
