import type { McpElicitation, McpElicitationField, McpElicitationOption } from '~/types/api'

/**
 * An MCP server's form (elicitation, form mode) as the chat shows it. The server checks each answer against the
 * schema again (McpElicitations.invalid); these checks mirror it so the operator sees a mistake before sending.
 */
export type McpFieldKind = 'text' | 'number' | 'integer' | 'boolean' | 'choice' | 'choices'

export interface McpFieldOption {
  value: string
  label: string
}

export interface McpFormField {
  key: string
  label: string
  description: string | null
  kind: McpFieldKind
  /** The input a text field uses, from its format. */
  inputType: 'text' | 'email' | 'url' | 'date' | 'datetime-local'
  required: boolean
  options: McpFieldOption[]
  schema: McpElicitationField
}

/** What a control holds: text (number inputs may hand back a number), a checkbox, or the chosen values. */
export type McpFieldValue = string | number | boolean | string[]

const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

const INPUT_TYPES: Record<string, McpFormField['inputType']> = {
  'email': 'email',
  'uri': 'url',
  'date': 'date',
  'date-time': 'datetime-local',
}

function options(values: unknown[] | undefined, titled: McpElicitationOption[] | undefined): McpFieldOption[] {
  if (Array.isArray(values)) return values.filter(v => typeof v === 'string').map(v => ({ value: v, label: v }))
  if (!Array.isArray(titled)) return []
  return titled.flatMap(o => typeof o.const === 'string'
    ? [{ value: o.const, label: typeof o.title === 'string' && o.title ? o.title : o.const }]
    : [])
}

function kindOf(field: McpElicitationField): McpFieldKind {
  if (field.type === 'number' || field.type === 'integer' || field.type === 'boolean') return field.type
  if (field.type === 'array') return 'choices'
  return field.enum || field.oneOf ? 'choice' : 'text'
}

function fieldOptions(kind: McpFieldKind, field: McpElicitationField): McpFieldOption[] {
  if (kind === 'choices') return options(field.items?.enum, field.items?.anyOf)
  if (kind === 'choice') return options(field.enum, field.oneOf)
  return []
}

export function formFields(elicitation: McpElicitation): McpFormField[] {
  const schema = elicitation.requestedSchema
  const required = new Set(Array.isArray(schema.required) ? schema.required : [])
  return Object.entries(schema.properties ?? {}).map(([key, field]) => {
    const kind = kindOf(field)
    return {
      key,
      label: typeof field.title === 'string' && field.title ? field.title : key,
      description: typeof field.description === 'string' && field.description ? field.description : null,
      kind,
      inputType: (field.format && INPUT_TYPES[field.format]) || 'text',
      required: required.has(key),
      options: fieldOptions(kind, field),
      schema: field,
    }
  })
}

/** A datetime-local input's value, {@code YYYY-MM-DDTHH:mm:ss} in the browser's zone, for an ISO instant. */
function localDateTime(iso: string): string {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return ''
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
    + `T${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

function initialValue(f: McpFormField): McpFieldValue {
  const d = f.schema.default
  switch (f.kind) {
    case 'boolean':
      return d === true
    case 'number':
    case 'integer':
      return typeof d === 'number' ? String(d) : ''
    case 'choices':
      return Array.isArray(d) ? d.filter(v => f.options.some(o => o.value === v)) as string[] : []
    case 'choice':
      return typeof d === 'string' && f.options.some(o => o.value === d) ? d : ''
    default:
      if (typeof d !== 'string') return ''
      return f.inputType === 'datetime-local' ? localDateTime(d) : d
  }
}

/** Each field's starting value: the schema's default, which the operator can review and change. */
export function initialValues(fields: McpFormField[]): Record<string, McpFieldValue> {
  const values: Record<string, McpFieldValue> = {}
  for (const f of fields) values[f.key] = initialValue(f)
  return values
}

function isBlank(v: McpFieldValue | undefined): boolean {
  return v === undefined || v === ''
}

function count(n: number, one: string, many: string): string {
  return `${n} ${n === 1 ? one : many}`
}

function textProblem(f: McpFormField, text: string): string | null {
  const length = [...text].length
  if (f.schema.minLength != null && length < f.schema.minLength) {
    return `Must be at least ${count(f.schema.minLength, 'character', 'characters')}.`
  }
  if (f.schema.maxLength != null && length > f.schema.maxLength) {
    return `Must be at most ${count(f.schema.maxLength, 'character', 'characters')}.`
  }
  switch (f.inputType) {
    case 'email':
      return EMAIL.test(text) ? null : 'Must be an email address.'
    case 'url':
      try {
        new URL(text)
        return null
      }
      catch {
        return 'Must be a URI.'
      }
    case 'date':
      return /^\d{4}-\d{2}-\d{2}$/.test(text) && !Number.isNaN(new Date(text).getTime()) ? null : 'Must be a date.'
    case 'datetime-local':
      return Number.isNaN(new Date(text).getTime()) ? 'Must be a date and time.' : null
    default:
      return null
  }
}

function numberProblem(f: McpFormField, value: McpFieldValue): string | null {
  const n = typeof value === 'number' ? value : Number(value)
  if (Number.isNaN(n)) return 'Must be a number.'
  if (f.kind === 'integer' && !Number.isInteger(n)) return 'Must be a whole number.'
  if (f.schema.minimum != null && n < f.schema.minimum) return `Must be at least ${f.schema.minimum}.`
  if (f.schema.maximum != null && n > f.schema.maximum) return `Must be at most ${f.schema.maximum}.`
  return null
}

function choicesProblem(f: McpFormField, value: McpFieldValue | undefined): string | null {
  const chosen = Array.isArray(value) ? value : []
  if (f.schema.minItems != null && chosen.length < f.schema.minItems) return `Choose at least ${f.schema.minItems}.`
  if (f.schema.maxItems != null && chosen.length > f.schema.maxItems) return `Choose at most ${f.schema.maxItems}.`
  return null
}

function fieldProblem(f: McpFormField, v: McpFieldValue | undefined): string | null {
  if (f.kind === 'choices') return choicesProblem(f, v)
  if (f.kind === 'boolean') return null
  if (isBlank(v)) return f.required ? 'Required.' : null
  if (f.kind === 'number' || f.kind === 'integer') return numberProblem(f, v as McpFieldValue)
  if (f.kind === 'text') return textProblem(f, String(v))
  return null
}

/** The problem with each field the operator must fix before sending, by key; empty when the form can go. */
export function validate(fields: McpFormField[], values: Record<string, McpFieldValue>): Record<string, string> {
  const problems: Record<string, string> = {}
  for (const f of fields) {
    const problem = fieldProblem(f, values[f.key])
    if (problem) problems[f.key] = problem
  }
  return problems
}

/**
 * The {@code content} an accept sends: every filled field, typed as its schema says. A blank optional field is left
 * out, and so is an optional yes-or-no field with no default that the operator left as it started ({@code initial}).
 */
export function answerContent(fields: McpFormField[], values: Record<string, McpFieldValue>,
  initial: Record<string, McpFieldValue>): Record<string, unknown> {
  const content: Record<string, unknown> = {}
  for (const f of fields) {
    const answer = answerOf(f, values[f.key], initial[f.key])
    if (answer !== undefined) content[f.key] = answer
  }
  return content
}

/** One field's value as {@link answerContent} sends it, or undefined to leave the field out. */
function answerOf(f: McpFormField, v: McpFieldValue | undefined, initial: McpFieldValue | undefined): unknown {
  if (f.kind === 'boolean') {
    return f.required || typeof f.schema.default === 'boolean' || v !== initial ? v === true : undefined
  }
  if (f.kind === 'choices') {
    const chosen = Array.isArray(v) ? v : []
    return chosen.length || f.required ? chosen : undefined
  }
  if (isBlank(v)) return undefined
  if (f.kind === 'number' || f.kind === 'integer') return typeof v === 'number' ? v : Number(v)
  if (f.inputType === 'datetime-local') return new Date(String(v)).toISOString()
  return String(v)
}
