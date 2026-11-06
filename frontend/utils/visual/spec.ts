/**
 * The chat's ```visual fence (JF-195): one JSON spec for one interactive figure, validated here and drawn by
 * first-party components as hand-written SVG. Nothing in a spec executes; an explore figure carries precomputed
 * frames rather than a formula, so no expression evaluator is needed.
 */
import { ref } from 'vue'
import { z } from 'zod'
import type { ConfigResponse } from '~/types/api'

export const MAX_VISUAL_CHARS = 20_000
export const VISUAL_ENABLED_KEY = 'visual.enabled'
export const VISUAL_KINDS = ['chart', 'diagram', 'explore'] as const

/** Set from /api/config by the transcript pages; chat-markdown reads it reactively. */
export const visualEnabled = ref(true)

/** Absent means on, as ConfigService.getBoolean(key, true) reads it on the server. */
export function syncVisualEnabled(config: ConfigResponse | null | undefined): void {
  const entry = config?.entries?.find(e => e.key === VISUAL_ENABLED_KEY)
  visualEnabled.value = entry == null || entry.value.trim().toLowerCase() === 'true'
}

const id = z.string().regex(/^[a-z0-9-]{1,32}$/, 'Use 1 to 32 lowercase letters, digits and hyphens')
const axis = z.object({ label: z.string().max(60).default(''), unit: z.string().max(20).optional() })
const point = z.tuple([z.number(), z.number()])
const points = z.array(point).min(2).max(400)
const title = z.string().min(1).max(120)
const caption = z.string().max(300).optional()

const chartSchema = z.object({
  kind: z.literal('chart'),
  chartType: z.enum(['line', 'bar', 'scatter']).default('line'),
  title,
  caption,
  x: axis.default({ label: '' }),
  y: axis.default({ label: '' }),
  series: z.array(z.object({ name: z.string().min(1).max(60), points })).min(1).max(6),
  marks: z.array(z.object({ x: z.number(), label: z.string().max(40).optional() })).max(20).optional(),
})

const diagramSchema = z.object({
  kind: z.literal('diagram'),
  layout: z.enum(['flow', 'tree', 'cycle']).default('flow'),
  title,
  caption,
  nodes: z.array(z.object({ id, label: z.string().min(1).max(60), detail: z.string().max(300).optional() })).min(1).max(40),
  edges: z.array(z.object({ from: id, to: id, label: z.string().max(40).optional() })).max(80).default([]),
  steps: z.array(z.object({
    title: z.string().min(1).max(80),
    body: z.string().max(500).default(''),
    focus: z.array(id).max(40).default([]),
  })).min(1).max(20).optional(),
}).superRefine((spec, ctx) => {
  const declared = new Set<string>()
  for (const node of spec.nodes) {
    if (declared.has(node.id)) ctx.addIssue({ code: 'custom', message: `Node id "${node.id}" is declared twice.` })
    declared.add(node.id)
  }
  spec.edges.forEach((edge, i) => {
    const missing = [edge.from, edge.to].find(end => !declared.has(end))
    if (missing !== undefined) ctx.addIssue({ code: 'custom', message: `Edge ${i + 1} points to undeclared node "${missing}".` })
  })
  spec.steps?.forEach((step, i) => {
    const missing = step.focus.find(end => !declared.has(end))
    if (missing !== undefined) ctx.addIssue({ code: 'custom', message: `Step ${i + 1} focuses undeclared node "${missing}".` })
  })
})

const exploreSchema = z.object({
  kind: z.literal('explore'),
  title,
  caption,
  x: axis.default({ label: '' }),
  y: axis.default({ label: '' }),
  params: z.array(z.object({
    id,
    label: z.string().min(1).max(60),
    unit: z.string().max(20).optional(),
    min: z.number(),
    max: z.number(),
    step: z.number(),
    value: z.number(),
  })).min(1).max(3),
  frames: z.array(z.object({ params: z.record(z.string(), z.number()), points })).min(1).max(100),
}).superRefine((spec, ctx) => {
  for (const p of spec.params) {
    if (!(p.min < p.max)) ctx.addIssue({ code: 'custom', message: `Param "${p.id}" needs a min below its max.` })
    if (!(p.step > 0)) ctx.addIssue({ code: 'custom', message: `Param "${p.id}" needs a step above 0.` })
    if (p.value < p.min || p.value > p.max) {
      ctx.addIssue({ code: 'custom', message: `Param "${p.id}" starts at ${p.value}, outside its range ${p.min} to ${p.max}.` })
    }
  }
  const ids = spec.params.map(p => p.id)
  const firstXs = spec.frames[0]!.points.map(([x]) => x)
  spec.frames.forEach((frame, i) => {
    const keys = Object.keys(frame.params)
    if (keys.length !== ids.length || !ids.every(k => k in frame.params)) {
      ctx.addIssue({ code: 'custom', message: `Frame ${i + 1} must set exactly the params ${ids.join(', ')}.` })
    }
    const xs = frame.points.map(([x]) => x)
    if (xs.length !== firstXs.length || xs.some((x, j) => x !== firstXs[j])) {
      ctx.addIssue({ code: 'custom', message: `Frame ${i + 1} does not carry the same x values as frame 1.` })
    }
  })
})

export type ChartSpec = z.infer<typeof chartSchema>
export type DiagramSpec = z.infer<typeof diagramSchema>
export type ExploreSpec = z.infer<typeof exploreSchema>
export type ExploreParam = ExploreSpec['params'][number]
export type VisualSpec = ChartSpec | DiagramSpec | ExploreSpec
export type VisualResult = VisualSpec | { error: string }

/** What a figure remembers across unmount: the selected diagram step, or the explore sliders. */
export interface VisualState { step?: number | null, values?: Record<string, number> }

const SCHEMAS = { chart: chartSchema, diagram: diagramSchema, explore: exploreSchema } as const

function isKind(kind: unknown): kind is keyof typeof SCHEMAS {
  return typeof kind === 'string' && (VISUAL_KINDS as readonly string[]).includes(kind)
}

/** Never throws: a body that cannot be drawn returns the reason the reader sees under its code block. */
export function parseVisualSpec(body: string): VisualResult {
  if (body.length > MAX_VISUAL_CHARS) {
    return { error: `Too large to draw: ${body.length} characters, over the limit of ${MAX_VISUAL_CHARS}. Draw fewer series or fewer points.` }
  }
  let json: unknown
  try {
    json = JSON.parse(body)
  }
  catch (err) {
    return { error: `The visual block is not valid JSON: ${err instanceof Error ? err.message : String(err)}` }
  }
  const kind = json !== null && typeof json === 'object' && !Array.isArray(json) ? (json as { kind?: unknown }).kind : undefined
  if (!isKind(kind)) return { error: `Unknown visual kind "${String(kind)}". Use chart, diagram or explore.` }
  const result = SCHEMAS[kind].safeParse(json)
  if (result.success) return result.data
  const issue = result.error.issues[0]!
  return { error: issue.code === 'custom' ? issue.message : `${issue.path.map(String).join('.')}: ${issue.message}` }
}
