import { describe, it, expect, beforeEach } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { readBody } from 'h3'
import { clearNuxtData } from '#app'
import Settings from '~/pages/settings.vue'

/**
 * The Chat settings panel's fallbacks must match the backend's own defaults: the panel showed
 * 10 tool rounds where AgentRunner.DEFAULT_MAX_TOOL_ROUNDS seeds 100 (JCLAW-1173), so an
 * unseeded instance displayed — and would have saved — a tenth of the real budget.
 */

/** Mirrors AgentRunner.DEFAULT_MAX_TOOL_ROUNDS, which DefaultConfigJob seeds. */
const BACKEND_MAX_TOOL_ROUNDS = 100

function baseEndpoints(entries: Array<{ key: string, value: string }> = []) {
  registerEndpoint('/api/agents', () => [])
  registerEndpoint('/api/channels', () => [])
  registerEndpoint('/api/ocr/status', () => ({ providers: [] }))
  registerEndpoint('/api/providers', () => [])
  registerEndpoint('/api/config', () => ({ entries }))
  registerEndpoint('/api/config', {
    method: 'POST',
    handler: async (event) => {
      await readBody(event)
      return { status: 'ok' }
    },
  })
}

async function mountChat() {
  const component = await mountSuspended(Settings)
  ;(component.vm as unknown as { activeSectionId: string }).activeSectionId = 'chat'
  await flushPromises()
  await flushPromises()
  return component
}

describe('Settings page — Chat', () => {
  beforeEach(() => {
    clearNuxtData()
  })

  it('falls back to the backend default for max tool rounds when the key is unset', async () => {
    baseEndpoints()
    const component = await mountChat()
    expect(component.text()).toContain(`${BACKEND_MAX_TOOL_ROUNDS} rounds`)
  })

  it('shows the stored value when the key is set', async () => {
    baseEndpoints([{ key: 'chat.maxToolRounds', value: '25' }])
    const component = await mountChat()
    expect(component.text()).toContain('25 rounds')
    expect(component.text()).not.toContain(`${BACKEND_MAX_TOOL_ROUNDS} rounds`)
  })

  it('lets the editor enter the default: the input ceiling is above it', async () => {
    baseEndpoints()
    const component = await mountChat()
    const edit = component.findAll('button[title="Edit"]').at(0)
    await edit!.trigger('click')
    await flushPromises()
    const input = component.find('input[aria-label="Max tool rounds"]')
    expect(Number(input.attributes('max'))).toBeGreaterThanOrEqual(BACKEND_MAX_TOOL_ROUNDS)
  })
})
