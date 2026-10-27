export interface LayaCheckpoint {
  name: string
  /** How many tokens of one question the checkpoint reads; the rest of the prompt is cut, not refused. */
  contextTokens: number
}

/** The contexts are from Laya's model card. LayaDecisionTest holds the names equal to `CHECKPOINTS` in app/services/decision/LayaDecision.java. */
export const LAYA_CHECKPOINTS: readonly LayaCheckpoint[] = [
  { name: 'english', contextTokens: 512 },
  { name: 'multilingual', contextTokens: 1024 },
  { name: 'typed-decisions', contextTokens: 1024 },
]

/** laya-serve's own default on this machine. LayaDecisionTest holds it equal to `DEFAULT_BASE_URL` in app/services/decision/LayaDecision.java. */
export const LAYA_DEFAULT_ADDRESS = 'http://localhost:8000'

export interface BasetenModel {
  /** What Baseten's API takes, and what `router.classifier.model` stores. */
  id: string
  name: string
  contextTokens: number
  parameters: string
}

/** From Baseten's decision-models page; mammoth-1's size is from its ID. BasetenDecisionTest holds the IDs and names equal to `MODELS` in app/services/decision/BasetenDecision.java. */
export const BASETEN_MODELS: readonly BasetenModel[] = [
  { id: 'decision-models/baseten-elephant-9b-latest', name: 'elephant-1', contextTokens: 16384, parameters: '9B' },
  { id: 'decision-models/baseten-mammoth-27b-latest', name: 'mammoth-1', contextTokens: 16384, parameters: '27B' },
]

/** A context as the UI words it, such as `1,024 tokens`. */
export function contextLabel(tokens: number): string {
  return `${tokens.toLocaleString('en-US')} tokens`
}

/** A checkpoint's context as the UI words it, such as `1,024 tokens`. */
export function layaContext(checkpoint: LayaCheckpoint): string {
  return contextLabel(checkpoint.contextTokens)
}

export interface LayaLimit {
  topic: string
  /** Each fact names where it comes from, so a measurement is never read as the vendor's claim. */
  facts: readonly { text: string, source: string }[]
}

/** A limit's facts in one run, each followed by its source in brackets. */
export function layaLimitText(limit: LayaLimit): string {
  return limit.facts.map(f => `${f.text} (${f.source})`).join(' ')
}

const MODEL_CARD = 'Laya\'s model card'
const MEASURED_ROUTER = 'measured 2026-10-13 on 40 written test prompts, not live traffic'
const MEASURED_PROMPT = 'measured 2026-10-13 on one written test prompt'

/** The limits a classifier choice takes on, for the Model Router's hint; the user guide's "Laya's limits" states them too, with the server's. */
export const LAYA_LIMITS: readonly LayaLimit[] = [
  {
    topic: 'Context',
    facts: [
      { text: 'english reads 512 tokens per question, multilingual and typed-decisions 1,024. Text past that is cut, not refused.', source: MODEL_CARD },
      { text: 'The router\'s class question and its options take about 170 of them: english read 343 of the 619 tokens in a 3,100-character prompt, and the 1,024-token checkpoints read all of it.', source: MEASURED_PROMPT },
    ],
  },
  {
    topic: 'Accuracy',
    facts: [
      { text: 'Its makers call it “a fast base to specialise, not a zero-shot decision engine”.', source: MODEL_CARD },
      { text: 'english chose the right class for 33 of 40 prompts, the same as the keyword rules; typed-decisions for 31; multilingual for 23, wrong at probabilities up to 0.96.', source: MEASURED_ROUTER },
    ],
  },
  {
    topic: 'Confidence',
    facts: [
      { text: 'Over-confident as shipped.', source: MODEL_CARD },
      { text: 'At a minimum confidence of 0.50, multilingual answered 34 of the 40 prompts and was right on 20 of them.', source: MEASURED_ROUTER },
    ],
  },
  {
    topic: 'Language',
    facts: [{ text: 'english is English only.', source: MODEL_CARD }],
  },
]
