// The JF-195 ticket's three examples, verbatim.
export const CHART = {
  kind: 'chart',
  chartType: 'line',
  title: 'Sprint velocity',
  caption: 'Committed against delivered, by sprint',
  x: { label: 'Sprint' },
  y: { label: 'Points', unit: 'pts' },
  series: [
    { name: 'Committed', points: [[1, 34], [2, 38], [3, 41]] },
    { name: 'Delivered', points: [[1, 30], [2, 37], [3, 40]] },
  ],
  marks: [{ x: 3, label: 'release' }],
}

export const DIAGRAM = {
  kind: 'diagram',
  layout: 'flow',
  title: 'How a request becomes a reply',
  nodes: [
    { id: 'in', label: 'Inbound message', detail: 'Saved before it is acknowledged' },
    { id: 'run', label: 'Agent run' },
    { id: 'out', label: 'Outbound reply' },
  ],
  edges: [
    { from: 'in', to: 'run', label: 'turn' },
    { from: 'run', to: 'out' },
  ],
  steps: [
    { title: 'Receive', body: 'The message is persisted first.', focus: ['in'] },
    { title: 'Run', body: 'The agent takes one turn.', focus: ['run'] },
    { title: 'Deliver', body: 'The reply is delivered exactly once.', focus: ['out'] },
  ],
}

export const EXPLORE = {
  kind: 'explore',
  title: 'Compound interest',
  x: { label: 'Years' },
  y: { label: 'Balance', unit: 'RM' },
  params: [
    { id: 'rate', label: 'Annual rate', unit: '%', min: 1, max: 10, step: 1, value: 5 },
  ],
  frames: [
    { params: { rate: 1 }, points: [[0, 1000], [10, 1105], [20, 1220]] },
    { params: { rate: 10 }, points: [[0, 1000], [10, 2594], [20, 6727]] },
  ],
}
