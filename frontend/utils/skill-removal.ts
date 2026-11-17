import type { SkillRemovalPreview, SkillRemovalStop, SkillRemovalValue } from '~/types/api'

/** `a`, `a and b`, `a, b and c`. */
function listed(names: string[]): string {
  return names.length < 2 ? names.join('') : `${names.slice(0, -1).join(', ')} and ${names.at(-1)}`
}

interface Held extends SkillRemovalValue {
  kind: 'secret' | 'setting'
}

function moveLine(value: Held): string {
  const others = value.usedBy.filter(skill => skill !== value.movesTo)
  const alsoUse = others.length ? `, which ${listed(others)} also ${others.length === 1 ? 'uses' : 'use'}` : ''
  return `The ${value.kind} ${value.name} moves to ${value.movesTo}${alsoUse}.`
}

/**
 * JF-356: the confirmation a delete of an agent's skill carries when the skill holds values or saved state. Each value
 * another skill declares moves to it, so the skills that read it by name keep working; the rest are deleted.
 */
export function removalWarning(skill: string, preview: SkillRemovalPreview): string {
  const values: Held[] = [
    ...preview.secrets.map(value => ({ ...value, kind: 'secret' as const })),
    ...preview.settings.map(value => ({ ...value, kind: 'setting' as const })),
  ]
  const moving = values.filter(value => value.movesTo)
  const deleted = values.filter(value => !value.movesTo)
  const parts = [`Delete '${skill}' from this agent?`]
  if (moving.length) {
    parts.push([`${moving.length === 1 ? 'This value moves' : 'These values move'} to another skill that uses `
      + `${moving.length === 1 ? 'it' : 'them'}, so nothing that needs ${moving.length === 1 ? 'it' : 'them'} stops:`,
    ...moving.map(moveLine)].join('\n'))
  }
  if (deleted.length) {
    const unrecoverable = deleted.some(value => value.kind === 'secret') ? ' A secret\'s value cannot be recovered.' : ''
    parts.push([`${deleted.length === 1 ? 'This value is' : 'These values are'} deleted, as no other skill uses `
      + `${deleted.length === 1 ? 'it' : 'them'}.${unrecoverable}`,
    ...deleted.map(value => `The ${value.kind} ${value.name}.`)].join('\n'))
  }
  if (preview.credentialFiles.length) {
    const one = preview.credentialFiles.length === 1
    parts.push(`${one ? 'This file' : 'These files'} in its credentials folder, which may hold a key, `
      + `${one ? 'is' : 'are'} deleted too: ${listed(preview.credentialFiles)}.`)
  }
  if (preview.savedState) parts.push('What the skill saved, such as a sign-in session or cookies, is deleted too.')
  if (preview.keptFiles) {
    parts.push(`The ${preview.keptFiles === 1 ? 'file' : `${preview.keptFiles} files`} kept from its earlier updates `
      + `${preview.keptFiles === 1 ? 'is' : 'are'} deleted too.`)
  }
  return parts.join('\n\n')
}

/** JF-356: the warning a switch-off carries when other switched-on skills need a value the skill holds. Nothing is deleted. */
export function switchOffWarning(skill: string, stops: SkillRemovalStop[]): string {
  const one = stops.length === 1
  return [
    [`Switching '${skill}' off stops ${one ? 'a skill that needs' : `${stops.length} skills that need`} a value it holds:`,
      ...stops.map(stop => `${stop.skill} needs ${listed(stop.names)}.`)].join('\n'),
    `Nothing is deleted. Switch '${skill}' on again and ${one ? 'it runs' : 'they run'} again.`,
  ].join('\n\n')
}
