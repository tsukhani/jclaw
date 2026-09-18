import { describe, it } from 'vitest'
import { RuleTester } from 'eslint'
import rule from '../eslint/no-bare-write-fetch.mjs'

const tester = new RuleTester({ languageOptions: { ecmaVersion: 2022, sourceType: 'module' } })
const options = [{ allowFiles: ['composables/useApiMutation.ts'] }]

describe('jclaw/no-bare-write-fetch', () => {
  it('accepts reads, wrapped writes and the wrapper files; rejects bare writes', () => {
    tester.run('no-bare-write-fetch', rule, {
      valid: [
        { code: `await $fetch('/api/agents')`, options },
        { code: `await $fetch('/api/agents', { query: { limit: 1 } })`, options },
        { code: `await attempt(() => $fetch('/api/config', { method: 'POST', body }))`, options },
        { code: `await attempt(async () => { await Promise.all([$fetch('/a', { method: 'DELETE' }), $fetch('/b', { method: 'PUT' })]) })`, options },
        { code: `await save.attempt(() => $fetch.raw('/api/x', { method: 'PATCH' }))`, options },
        { code: `await mutate('/api/agents/1', { method: 'DELETE' })`, options },
        { code: `await $fetch('/api/x', { method: 'POST' })`, options, filename: '/repo/frontend/composables/useApiMutation.ts' },
      ],
      invalid: [
        { code: `await $fetch('/api/config', { method: 'POST', body })`, options, errors: [{ messageId: 'bareWrite', data: { method: 'POST' } }] },
        { code: `await $fetch('/api/x', { method: 'delete' })`, options, errors: [{ messageId: 'bareWrite', data: { method: 'DELETE' } }] },
        { code: `await $fetch.raw('/api/x', { method: \`PUT\` })`, options, errors: [{ messageId: 'bareWrite', data: { method: 'PUT' } }] },
        { code: `try { await $fetch('/api/x', { method: 'PATCH' }) } catch (e) { console.error(e) }`, options, errors: [{ messageId: 'bareWrite' }] },
        { code: `await $fetch('/api/x', { method: 'POST' })`, options, filename: '/repo/frontend/composables/useAgentModel.ts', errors: [{ messageId: 'bareWrite' }] },
        { code: `await $fetch('/api/x', { method: starred ? 'DELETE' : 'PUT' })`, options, errors: [{ messageId: 'bareWrite', data: { method: 'DELETE' } }] },
        { code: `await $fetch('/api/x', { method: dry ? 'GET' : 'POST' })`, options, errors: [{ messageId: 'bareWrite', data: { method: 'POST' } }] },
      ],
    })
  })
})
