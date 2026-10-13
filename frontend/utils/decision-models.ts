/** The names in `decision.ollama.models` or `decision.laya.models`, or [] when it is unset or not a JSON array of strings. */
export function parseDecisionModels(raw: string): string[] {
  if (!raw.trim()) return []
  try {
    const parsed = JSON.parse(raw) as unknown
    return Array.isArray(parsed) ? parsed.filter((m): m is string => typeof m === 'string' && m.trim() !== '') : []
  }
  catch {
    return []
  }
}

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

/** The checkpoints ticked in `decision.laya.models`, in the table's order; a stored name that is no checkpoint is left out. */
export function tickedLayaCheckpoints(raw: string): LayaCheckpoint[] {
  const ticked = parseDecisionModels(raw)
  return LAYA_CHECKPOINTS.filter(c => ticked.includes(c.name))
}

/** A checkpoint's context as the UI words it, such as `1,024 tokens`. */
export function layaContext(checkpoint: LayaCheckpoint): string {
  return `${checkpoint.contextTokens.toLocaleString('en-US')} tokens`
}

export interface LayaLimit {
  topic: string
  /** A limit of running the server, which choosing a checkpoint as the classifier does not change. */
  server?: boolean
  /** Each fact names where it comes from, so a measurement is never read as the vendor's claim. */
  facts: readonly { text: string, source: string }[]
}

/** A limit's facts in one run, each followed by its source in brackets. */
export function layaLimitText(limit: LayaLimit): string {
  return limit.facts.map(f => `${f.text} (${f.source})`).join(' ')
}

const MODEL_CARD = 'Laya\'s model card'
const SERVER_DOCS = 'laya-serve\'s documentation'
const MEASURED_ROUTER = 'measured 2026-10-13 on 40 written test prompts, not live traffic'
const MEASURED_PROMPT = 'measured 2026-10-13 on one written test prompt'
const MEASURED_CPU = 'english on CPU, measured 2026-10-13'
const MEASURED_MEMORY = 'english, measured 2026-10-13 and 2026-10-14'

/** One copy for the Laya card and the Model Router's classifier hint, so the two cannot disagree. */
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
  {
    topic: 'Server',
    server: true,
    facts: [{ text: 'laya-serve listens on every interface with no key unless LAYA_HOST and LAYA_API_KEY are set.', source: SERVER_DOCS }],
  },
  {
    topic: 'Memory',
    server: true,
    facts: [
      { text: 'A loaded checkpoint held about 2.1 GB on CPU and 3.2 GB on an Apple GPU.', source: MEASURED_MEMORY },
      { text: 'By default laya-serve loads all three checkpoints at start and keeps two loaded. LAYA_MODELS names the ones to load at start, and LAYA_MAX_LOADED caps how many stay loaded.', source: SERVER_DOCS },
    ],
  },
  {
    topic: 'Loading',
    server: true,
    facts: [
      { text: 'A checkpoint not in memory is loaded on its first request, and downloaded first if it is not on disk.', source: MODEL_CARD },
      { text: 'Loading english from disk took 2.4 s, so a first request can outlast a short classifier timeout; the keyword rules answer that turn.', source: MEASURED_CPU },
    ],
  },
]
