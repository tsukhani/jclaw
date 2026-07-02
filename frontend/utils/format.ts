/** Format a byte count as a human-readable size string (B, KB, MB, GB). */
export function formatSize(bytes: number): string {
  const { value, unit } = splitSize(bytes)
  return `${value} ${unit}`
}

/**
 * Split a byte count into a display value and its adaptive unit, for UIs
 * that render the number and the unit separately (e.g. a stat card whose
 * label names the unit). The unit steps up so the value stays small —
 * 5 GB is `{value: "5.0", unit: "GB"}`, never `{value: "5120", unit: "MB"}`.
 */
export function splitSize(bytes: number): { value: string, unit: string } {
  if (bytes < 1024) return { value: `${bytes}`, unit: 'B' }
  if (bytes < 1024 * 1024) return { value: (bytes / 1024).toFixed(1), unit: 'KB' }
  if (bytes < 1024 * 1024 * 1024) return { value: (bytes / (1024 * 1024)).toFixed(1), unit: 'MB' }
  return { value: (bytes / (1024 * 1024 * 1024)).toFixed(1), unit: 'GB' }
}
