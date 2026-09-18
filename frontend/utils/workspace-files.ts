/** How a workspace row is drawn: folders, files an editor would open as text, and everything else. */
export type WorkspaceEntryStyle = 'dir' | 'text' | 'binary'

const TEXT_EXTENSIONS = new Set([
  'md', 'markdown', 'txt', 'rst', 'adoc', 'tex',
  'json', 'jsonl', 'yaml', 'yml', 'toml', 'ini', 'cfg', 'conf', 'properties', 'env', 'xml', 'plist',
  'csv', 'tsv', 'log', 'sql',
  'html', 'htm', 'css', 'scss', 'less', 'svg',
  'js', 'mjs', 'cjs', 'ts', 'tsx', 'jsx', 'vue',
  'py', 'java', 'kt', 'groovy', 'rb', 'go', 'rs', 'c', 'h', 'cpp', 'hpp', 'cs', 'php', 'swift',
  'sh', 'bash', 'zsh', 'ps1', 'bat',
  'ipynb', 'lock', 'gitignore', 'editorconfig', 'npmrc',
])

const TEXT_NAMES = new Set(['makefile', 'dockerfile', 'license', 'readme', 'changelog', '.env', '.gitignore', '.editorconfig', '.npmrc'])

/** Decided by name alone: the listing never opens a file, and the section never renders one. */
export function isTextFile(name: string): boolean {
  const lower = name.toLowerCase()
  if (TEXT_NAMES.has(lower)) return true
  const dot = lower.lastIndexOf('.')
  return dot > 0 && TEXT_EXTENSIONS.has(lower.slice(dot + 1))
}

export function workspaceEntryStyle(kind: 'file' | 'dir', name: string): WorkspaceEntryStyle {
  if (kind === 'dir') return 'dir'
  return isTextFile(name) ? 'text' : 'binary'
}
