import type { InjectionKey } from 'vue'

/**
 * Provided by the chat page (JCLAW-1273): a background scrape job's card calls it when its job ends, so
 * the page picks up the completion message and the agent's reply, which arrive with no SSE channel.
 */
export const scrapeJobEndedKey: InjectionKey<() => void> = Symbol('scrapeJobEnded')

/** Long enough for the completion turn — one model call — to write its reply. */
export const SCRAPE_JOB_REPLY_GRACE_MS = 180_000
