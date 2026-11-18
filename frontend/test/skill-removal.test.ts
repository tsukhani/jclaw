import { describe, it, expect } from 'vitest'
import { removalWarning, switchOffWarning } from '~/utils/skill-removal'

/** JF-356: the text of the confirmation before a skill that holds values is deleted or switched off. */
describe('removalWarning', () => {
  const none = { secrets: [], settings: [], savedState: false, credentialFiles: [], keptFiles: 0, asks: true, plan: 'p', stopsIfSwitchedOff: [] }

  it('names a value that moves and the other skills that use it', () => {
    expect(removalWarning('radarr', {
      ...none,
      secrets: [{ name: 'RADARR_API_KEY', movesTo: 'radarr-delete', usedBy: ['radarr-delete', 'radarr-library', 'sonarr'] }],
    })).toBe('Delete \'radarr\' from this agent?\n\n'
      + 'This value moves to another skill that uses it, so nothing that needs it stops:\n'
      + 'The secret RADARR_API_KEY moves to radarr-delete, which radarr-library and sonarr also use.')
  })

  it('says a deleted secret cannot be recovered, and a deleted setting alone does not', () => {
    expect(removalWarning('demo', { ...none, secrets: [{ name: 'API_KEY', movesTo: null, usedBy: [] }], settings: [{ name: 'SITE_URL', movesTo: null, usedBy: [] }] }))
      .toBe('Delete \'demo\' from this agent?\n\n'
        + 'These values are deleted, as no other skill uses them. A secret\'s value cannot be recovered.\n'
        + 'The secret API_KEY.\nThe setting SITE_URL.')
    expect(removalWarning('demo', { ...none, settings: [{ name: 'SITE_URL', movesTo: null, usedBy: [] }] }))
      .toBe('Delete \'demo\' from this agent?\n\nThis value is deleted, as no other skill uses it.\nThe setting SITE_URL.')
  })

  it('names each file with content in the credentials folder', () => {
    expect(removalWarning('demo', { ...none, credentialFiles: ['credentials/api-key.txt'] }))
      .toBe('Delete \'demo\' from this agent?\n\n'
        + 'This file in its credentials folder, which may hold a key, is deleted too: credentials/api-key.txt.')
    expect(removalWarning('demo', { ...none, credentialFiles: ['credentials/a.txt', 'credentials/b.txt', 'credentials/c'] }))
      .toBe('Delete \'demo\' from this agent?\n\n'
        + 'These files in its credentials folder, which may hold a key, are deleted too: credentials/a.txt,'
        + ' credentials/b.txt and credentials/c.')
  })

  it('names saved state alone, and one kept file', () => {
    expect(removalWarning('demo', { ...none, savedState: true, keptFiles: 1 }))
      .toBe('Delete \'demo\' from this agent?\n\n'
        + 'What the skill saved, such as a sign-in session or cookies, is deleted too.\n\n'
        + 'The file kept from its earlier updates is deleted too.')
  })

  it('names several moving values, one another skill also uses and one no other skill uses', () => {
    expect(removalWarning('radarr', {
      ...none,
      secrets: [{ name: 'RADARR_API_KEY', movesTo: 'radarr-delete', usedBy: ['radarr-delete', 'sonarr'] }],
      settings: [{ name: 'TRANSMISSION_URL', movesTo: 'sonarr', usedBy: ['sonarr'] }],
    })).toBe('Delete \'radarr\' from this agent?\n\n'
      + 'These values move to another skill that uses them, so nothing that needs them stops:\n'
      + 'The secret RADARR_API_KEY moves to radarr-delete, which sonarr also uses.\n'
      + 'The setting TRANSMISSION_URL moves to sonarr.')
  })

  it('counts several kept files', () => {
    expect(removalWarning('demo', { ...none, keptFiles: 2 }))
      .toBe('Delete \'demo\' from this agent?\n\nThe 2 files kept from its earlier updates are deleted too.')
  })
})

describe('switchOffWarning', () => {
  it('names the one skill that stops and what it needs', () => {
    expect(switchOffWarning('sonarr', [{ skill: 'sonarr-recommend', names: ['SONARR_API_KEY'] }]))
      .toBe('Switching \'sonarr\' off stops a skill that needs a value it holds:\n'
        + 'sonarr-recommend needs SONARR_API_KEY.\n\n'
        + 'Nothing is deleted. Switch \'sonarr\' on again and it runs again.')
  })

  it('names each of several skills that stop', () => {
    expect(switchOffWarning('radarr', [
      { skill: 'radarr-library', names: ['RADARR_API_KEY'] },
      { skill: 'sonarr', names: ['RADARR_API_KEY', 'TRANSMISSION_URL'] },
    ])).toBe('Switching \'radarr\' off stops 2 skills that need a value it holds:\n'
      + 'radarr-library needs RADARR_API_KEY.\n'
      + 'sonarr needs RADARR_API_KEY and TRANSMISSION_URL.\n\n'
      + 'Nothing is deleted. Switch \'radarr\' on again and they run again.')
  })
})
