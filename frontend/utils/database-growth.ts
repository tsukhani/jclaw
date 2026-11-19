// The parts of the data file in Settings → Database → Where the space goes (JF-525). Light mode keeps
// LatencyOverlayChart's first four tones; dark mode takes one step darker, because its dark tones sit above the
// 0.48–0.67 OKLCH lightness band the dataviz palette validator holds a categorical series to. Both sets pass that
// validator's colorblind separation and 3:1 contrast on the elevated surface, and the greys clear 3:1 on it too.
export const DB_TABLE_COLORS = [
  'light-dark(oklch(59.6% 0.145 163.225), #059669)',
  'light-dark(oklch(58.8% 0.158 241.966), #0284c7)',
  'light-dark(oklch(55.5% 0.163 48.998), #d97706)',
  'light-dark(oklch(54.1% 0.281 293.009), #8b5cf6)',
] as const
export const DB_OTHER_TABLES_COLOR = 'light-dark(#6b6b6b, #a3a3a3)'
export const DB_INDEXES_COLOR = 'light-dark(#8f8f8f, #6e6e6e)'
export const DB_IN_USE_COLOR = 'light-dark(#737373, #a3a3a3)'
// Free space is the file's empty part: a faint fill, outlined in the muted text color so it still clears 3:1.
export const DB_FREE_FILL = 'light-dark(hsl(0 0% 43% / 0.12), hsl(0 0% 56% / 0.14))'
