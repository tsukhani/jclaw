// The eight tasks. Each is a list of turns sent in one fresh conversation; `timed` marks the turns whose times count
// (a follow-up times only its second turn). Each grader takes the reply text and the run's ground truth. The passage
// is the opening 3,009 words of Conan Doyle's "A Scandal in Bohemia" (public domain, Project Gutenberg #1661).
import fs from 'node:fs'

const passage = fs.readFileSync(new URL('./passage.txt', import.meta.url), 'utf8')
const has = (text, ...needles) => needles.every(n => new RegExp(n, 'i').test(text))

function jsonBody(text) {
  const fenced = text.match(/```(?:json)?\s*([\s\S]*?)```/)
  const raw = (fenced ? fenced[1] : text).trim()
  try { return JSON.parse(raw) } catch { return null }
}

const versionIn = (text, v) => v && new RegExp(`(^|[^\\d.])${v.replaceAll('.', '\\.')}($|[^\\d])`).test(text)

export const WEB_TASKS = new Set(['search1', 'search3'])

export const TASKS = [
  {
    id: 'question',
    turns: [{ text: 'In what year did the Apollo 11 mission land on the Moon? Answer in one line.', timed: true,
      grade: t => has(t, '1969') }],
  },
  {
    id: 'followup',
    turns: [
      { text: 'In what year did the Apollo 11 mission land on the Moon? Answer in one line.', timed: false,
        grade: t => has(t, '1969') },
      { text: 'Who was the second person to walk on the Moon on that mission? Answer in one line.', timed: true,
        grade: t => has(t, 'Aldrin') },
    ],
  },
  {
    id: 'summary',
    turns: [{ text: `Summarize the following story excerpt in exactly five bullet points.\n\n${passage}`, timed: true,
      grade: t => (t.match(/^\s*(?:[-*•]|\d+[.)])\s+/gm) ?? []).length === 5 && has(t, 'Irene Adler', 'photo', 'King') }],
  },
  {
    id: 'reasoning',
    turns: [{
      text: 'A train leaves at 09:40. It travels 210 km at a steady 84 km/h, waits 25 minutes at a station, then '
        + 'travels 96 km at a steady 64 km/h. At what time does it arrive? End your reply with the time as HH:MM.',
      timed: true, grade: t => /14[:.]05\D*$/.test(t.trim()) || has(t, '14:05'),
    }],
  },
  {
    id: 'json',
    turns: [{
      text: 'Reply with only a JSON object, no other text, with exactly these keys: "city" (string), "country" '
        + '(string), "population_millions" (number) and "landmarks" (an array of exactly 3 strings), describing Paris.',
      timed: true,
      grade: t => {
        const o = jsonBody(t)
        return !!o && typeof o === 'object' && !Array.isArray(o)
          && JSON.stringify(Object.keys(o).sort()) === '["city","country","landmarks","population_millions"]'
          && /paris/i.test(o.city) && /france/i.test(o.country) && typeof o.population_millions === 'number'
          && Array.isArray(o.landmarks) && o.landmarks.length === 3 && o.landmarks.every(x => typeof x === 'string')
      },
    }],
  },
  {
    id: 'fiveturn',
    turns: [
      { text: 'I will give you four numbers, one per message. Remember each one. The first number is 17. Reply with just "ok".', timed: true, grade: t => has(t, 'ok') },
      { text: 'The second number is 42. Reply with just "ok".', timed: true, grade: t => has(t, 'ok') },
      { text: 'The third number is 8. Reply with just "ok".', timed: true, grade: t => has(t, 'ok') },
      { text: 'The fourth number is 23. What is the sum of all four numbers? Reply with just the number.', timed: true, grade: t => /\b90\b/.test(t) },
      { text: 'Multiply that sum by the second number I gave you. Reply with just the number.', timed: true, grade: t => /\b3,?780\b/.test(t) },
    ],
  },
  {
    id: 'search1',
    web: true,
    turns: [{
      text: 'Search the web: what is the latest stable version of the Linux kernel listed on kernel.org? Reply with just the version number.',
      timed: true, grade: (t, truth) => versionIn(t, truth.kernel),
    }],
  },
  {
    id: 'search3',
    web: true,
    turns: [{
      text: 'Search the web for three independent facts: the latest stable Linux kernel version on kernel.org, the '
        + 'latest stable Python 3 release on python.org, and the latest Node.js LTS version on nodejs.org. Reply with three '
        + 'lines, each "name: version".',
      timed: true, grade: (t, truth) => versionIn(t, truth.kernel) && versionIn(t, truth.python) && versionIn(t, truth.nodeLts),
    }],
  },
]

// Ground truth for the web tasks, read from each project's own release feed when the run starts.
export async function groundTruth() {
  const get = async (url) => (await fetch(url, { signal: AbortSignal.timeout(20000) })).json()
  const kernel = (await get('https://www.kernel.org/releases.json')).latest_stable?.version
  const node = await get('https://nodejs.org/dist/index.json')
  const nodeLts = node.find(r => r.lts)?.version?.replace(/^v/, '')
  const py = await get('https://www.python.org/api/v2/downloads/release/?is_published=true&pre_release=false&version=3')
  const python = py.map(r => r.name.replace(/^Python\s+/, '')).filter(v => /^3\.\d+\.\d+$/.test(v))
    .sort((a, b) => b.localeCompare(a, undefined, { numeric: true }))[0]
  return { kernel, python, nodeLts, at: new Date().toISOString() }
}
