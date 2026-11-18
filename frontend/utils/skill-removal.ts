import type { SkillRemovalPreview, SkillRemovalStop, SkillRemovalValue } from '~/types/api'

/** `a`, `a and b`, `a, b and c`. */
function listed(names: string[]): string {
  return names.length < 2 ? names.join('') : `${names.slice(0, -1).join(', ')} and ${names.at(-1)}`
}

interface Held extends SkillRemovalValue {
  kind: 'secret' | 'setting'
}

function moveLine(value: Held): string {
  const moves = `The ${value.kind} ${value.name} moves to ${value.movesTo}`
  const others = value.usedBy.filter(skill => skill !== value.movesTo)
  if (!others.length) return `${moves}.`
  const verb = others.length === 1 ? 'uses' : 'use'
  return `${moves}, which ${listed(others)} also ${verb}.`
}

function movingParagraph(moving: Held[]): string {
  const one = moving.length === 1
  const subject = one ? 'This value moves' : 'These values move'
  const them = one ? 'it' : 'them'
  return [`${subject} to another skill that uses ${them}, so nothing that needs ${them} stops:`,
    ...moving.map(moveLine)].join('\n')
}

function deletedParagraph(deleted: Held[]): string {
  const one = deleted.length === 1
  const subject = one ? 'This value is' : 'These values are'
  const unrecoverable = deleted.some(value => value.kind === 'secret') ? ' A secret\'s value cannot be recovered.' : ''
  return [`${subject} deleted, as no other skill uses ${one ? 'it' : 'them'}.${unrecoverable}`,
    ...deleted.map(value => `The ${value.kind} ${value.name}.`)].join('\n')
}

function credentialFilesParagraph(files: string[]): string {
  const one = files.length === 1
  return `${one ? 'This file' : 'These files'} in its credentials folder, which may hold a key, `
    + `${one ? 'is' : 'are'} deleted too: ${listed(files)}.`
}

function keptFilesParagraph(count: number): string {
  const files = count === 1 ? 'file' : `${count} files`
  return `The ${files} kept from its earlier updates ${count === 1 ? 'is' : 'are'} deleted too.`
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
  if (moving.length) parts.push(movingParagraph(moving))
  if (deleted.length) parts.push(deletedParagraph(deleted))
  if (preview.credentialFiles.length) parts.push(credentialFilesParagraph(preview.credentialFiles))
  if (preview.savedState) parts.push('What the skill saved, such as a sign-in session or cookies, is deleted too.')
  if (preview.keptFiles) parts.push(keptFilesParagraph(preview.keptFiles))
  return parts.join('\n\n')
}

/** JF-356: the warning a switch-off carries when other switched-on skills need a value the skill holds. Nothing is deleted. */
export function switchOffWarning(skill: string, stops: SkillRemovalStop[]): string {
  const one = stops.length === 1
  const stopping = one ? 'a skill that needs' : `${stops.length} skills that need`
  return [
    [`Switching '${skill}' off stops ${stopping} a value it holds:`,
      ...stops.map(stop => `${stop.skill} needs ${listed(stop.names)}.`)].join('\n'),
    `Nothing is deleted. Switch '${skill}' on again and ${one ? 'it runs' : 'they run'} again.`,
  ].join('\n\n')
}
