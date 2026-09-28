/**
 * The chat's ```structure fence (JCLAW-1321): what a fence asks to draw, decided without loading 3Dmol.
 * The body is either structure text or one workspace-relative path to a structure file.
 */

export const STRUCTURE_FORMATS = ['cif', 'pdb', 'sdf', 'mol', 'xyz', 'mol2'] as const
export type StructureFormat = typeof STRUCTURE_FORMATS[number]

export type StructureSource
  = | { kind: 'inline', format: StructureFormat, text: string, caption: string }
    | { kind: 'file', format: StructureFormat, path: string, caption: string }

export type StructureSpec = StructureSource | { error: string }

export const MAX_INLINE_CHARS = 500_000

const EXTENSIONS: Record<string, StructureFormat> = {
  cif: 'cif', mmcif: 'cif', pdb: 'pdb', ent: 'pdb', sdf: 'sdf', mol: 'mol', xyz: 'xyz', mol2: 'mol2',
}

function isFormat(word: string): word is StructureFormat {
  return (STRUCTURE_FORMATS as readonly string[]).includes(word)
}

/** Marked keeps the whole info string in `lang`: "structure cif Sodium formate" names the format, then the caption. */
export function parseInfo(info: string): { format: StructureFormat | null, caption: string } {
  const words = info.trim().split(/\s+/).slice(1)
  const first = words[0]?.toLowerCase() ?? ''
  return isFormat(first)
    ? { format: first, caption: words.slice(1).join(' ') }
    : { format: null, caption: words.join(' ') }
}

export function formatFromPath(path: string): StructureFormat | null {
  const ext = /\.([a-z0-9]+)$/i.exec(path)?.[1]?.toLowerCase()
  return ext ? EXTENSIONS[ext] ?? null : null
}

export function sniffFormat(text: string): StructureFormat | null {
  if (/^data_/m.test(text) || /^_cell_length_a\s/m.test(text)) return 'cif'
  if (/^@<TRIPOS>MOLECULE/m.test(text)) return 'mol2'
  if (/^M {2}END/m.test(text) || /\bV[23]000\b/.test(text)) return 'sdf'
  if (/^(?:ATOM {2}|HETATM|CRYST1)/m.test(text)) return 'pdb'
  const lines = text.split('\n')
  if (/^\s*\d+\s*$/.test(lines[0] ?? '') && /^\s*[A-Z][a-z]?\s+-?\d/.test(lines[2] ?? '')) return 'xyz'
  return null
}

/** The server's workspace guard is the real boundary; this keeps the request inside the workspace and the error readable. */
export function workspacePathError(path: string): string | null {
  if (/^[a-z][a-z0-9+.-]*:/i.test(path)) return `${path} is a URL; a structure block takes a path in the agent's workspace.`
  if (path.startsWith('/') || path.includes('\\')) return `${path} is not a relative workspace path.`
  if (path.split('/').includes('..')) return `${path} leaves the workspace.`
  return null
}

export function parseStructureFence(info: string, body: string): StructureSpec {
  const { format: named, caption } = parseInfo(info)
  const text = body.trim()
  if (!text) return { error: 'The structure block is empty.' }
  const pathFormat = text.includes('\n') ? null : formatFromPath(text)
  if (pathFormat) {
    const error = workspacePathError(text)
    return error ? { error } : { kind: 'file', format: named ?? pathFormat, path: text, caption }
  }
  if (text.length > MAX_INLINE_CHARS) {
    return { error: `Too large to draw inline: ${text.length} characters, over the limit of ${MAX_INLINE_CHARS}. Save it to the workspace and give its path.` }
  }
  const format = named ?? sniffFormat(text)
  if (!format) return { error: 'Could not tell the structure format. Name it after the hint: structure cif, pdb, sdf, xyz or mol2.' }
  return { kind: 'inline', format, text, caption }
}

export function workspaceFileUrl(agentId: number, path: string): string {
  return `/api/agents/${agentId}/files/${path.split('/').filter(Boolean).map(encodeURIComponent).join('/')}`
}
