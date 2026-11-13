import { describe, it, expect, afterEach, beforeEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { enableAutoUnmount, flushPromises, type VueWrapper } from '@vue/test-utils'
import Chat from '~/pages/chat.vue'
import type { AgentSkill } from '~/types/api'
import { fakeConversationStreams } from './conversation-stream'

/**
 * JF-327: the chat names the agent's skills it is not offered until a value they need is set, each with a link to that
 * skill's Secrets panel, or to the skill holding the value, built from the agent id and a skill folder.
 */

beforeEach(() => {
  fakeConversationStreams()
  clearNuxtData()
})
afterEach(() => {
  vi.unstubAllGlobals()
})
enableAutoUnmount(afterEach)

function setupChatApi(skillsOf: Record<number, () => AgentSkill[] | Promise<AgentSkill[]>>) {
  registerEndpoint('/api/agents', () => [
    { id: 3, name: 'helper', modelProvider: 'ollama-cloud', modelId: 'kimi-k2.5',
      enabled: true, isMain: true, thinkingMode: null, providerConfigured: true },
    { id: 4, name: 'other', modelProvider: 'ollama-cloud', modelId: 'kimi-k2.5',
      enabled: true, isMain: false, thinkingMode: null, providerConfigured: true },
  ])
  registerEndpoint('/api/config', () => ({
    entries: [
      { key: 'provider.ollama-cloud.baseUrl', value: 'https://ollama.com/v1' },
      { key: 'provider.ollama-cloud.apiKey', value: 'xxxx****' },
      { key: 'provider.ollama-cloud.models', value: '[{"id":"kimi-k2.5","name":"Kimi K2.5","supportsThinking":false}]' },
    ],
  }))
  registerEndpoint('/api/conversations', () => [])
  registerEndpoint('/api/subagent-runs', () => [])
  for (const [id, skills] of Object.entries(skillsOf)) registerEndpoint(`/api/agents/${id}/skills`, skills)
}

function skill(folder: string, o: Partial<AgentSkill> = {}): AgentSkill {
  return { name: folder, folderName: folder, enabled: true, needsValues: [], heldBySwitchedOff: {}, heldByNotOffered: {},
    withheld: false, ...o }
}

const text = (s: string) => s.replace(/\s+/g, ' ').trim()
const notice = (page: VueWrapper) => page.find('[data-testid="withheld-skills-notice"]')
const items = (page: VueWrapper) => notice(page).findAll('li').map(li => li.attributes('data-testid'))
// eslint-disable-next-line @typescript-eslint/no-explicit-any -- Reason: mountSuspended returns a proxy wrapper whose vm exposes the page's setup state.
const vmOf = (page: VueWrapper) => page.vm as any

describe('Chat page: skills withheld for a missing value', () => {
  it('names each skill the list marks withheld, what it needs, and links to its Secrets panel', async () => {
    setupChatApi({ 3: () => [
      skill('gamma-presenter', { needsValues: ['GAMMA_API_KEY'], withheld: true }),
      skill('plain'),
      // Lacks a tool as well: the agent is not told of it, and a value would not make it runnable.
      skill('notes', { needsValues: ['NOTES_KEY'], withheld: false }),
      skill('jira', { enabled: false, needsValues: ['JIRA_URL'], withheld: false }),
    ] })
    const page = await mountSuspended(Chat)
    await flushPromises()

    expect(items(page)).toEqual(['withheld-skill-gamma-presenter'])
    expect(text(page.find('[data-testid="withheld-skill-gamma-presenter"]').text())).toBe(
      'gamma-presenter isn\'t offered to this agent until it has GAMMA_API_KEY. Open Secrets panel of gamma-presenter')
    expect(page.find('[data-testid="withheld-skill-link-gamma-presenter"]').attributes('href'))
      .toBe('/skills?agent=3&skill=gamma-presenter')
  })

  it('links the skill that holds a value, switched off or on but not offered, instead of the one that needs it', async () => {
    setupChatApi({ 3: () => [
      skill('sonarr', { needsValues: ['SONARR_API_KEY', 'TRANSMISSION_URL', 'PROWLARR_URL'], withheld: true,
        heldBySwitchedOff: { TRANSMISSION_URL: 'radarr' }, heldByNotOffered: { PROWLARR_URL: 'prowlarr' } }),
      skill('lidarr', { needsValues: ['TRANSMISSION_URL'], withheld: true, heldBySwitchedOff: { TRANSMISSION_URL: 'radarr' } }),
    ] })
    const page = await mountSuspended(Chat)
    await flushPromises()

    expect(text(page.find('[data-testid="withheld-skill-sonarr"]').text())).toBe(
      'sonarr isn\'t offered to this agent until it has SONARR_API_KEY. Open Secrets panel of sonarr'
      + ' It needs TRANSMISSION_URL, set in radarr, which is switched off. Open radarr for sonarr'
      + ' It needs PROWLARR_URL, set in prowlarr, which is not offered right now (a tool it needs is off).'
      + ' Open prowlarr for sonarr')
    expect(page.find('[data-testid="withheld-skill-link-sonarr"]').attributes('href')).toBe('/skills?agent=3&skill=sonarr')
    expect(page.find('[data-testid="withheld-holder-link-sonarr-radarr"]').attributes('href'))
      .toBe('/skills?agent=3&skill=radarr')
    expect(page.find('[data-testid="withheld-holder-link-sonarr-prowlarr"]').attributes('href'))
      .toBe('/skills?agent=3&skill=prowlarr')

    expect(text(page.find('[data-testid="withheld-skill-lidarr"]').text())).toBe(
      'lidarr isn\'t offered to this agent. It needs TRANSMISSION_URL, set in radarr, which is switched off.'
      + ' Open radarr for lidarr')
    expect(page.find('[data-testid="withheld-skill-link-lidarr"]').exists()).toBe(false)
  })

  it('shows no notice when no skill is withheld for a value', async () => {
    setupChatApi({ 3: () => [skill('plain'), skill('notes', { needsValues: ['NOTES_KEY'] })] })
    const page = await mountSuspended(Chat)
    await flushPromises()

    expect(notice(page).exists()).toBe(false)
  })

  it('shows no notice on a subagent transcript, which is another agent\'s', async () => {
    setupChatApi({ 3: () => [skill('gamma-presenter', { needsValues: ['GAMMA_API_KEY'], withheld: true })] })
    registerEndpoint('/api/conversations/501', () => ({
      id: 501, agentId: 99, agentName: 'helper-subagent', channelType: 'subagent', peerId: null, messageCount: 0,
      createdAt: '2026-11-13T10:00:00Z', updatedAt: '2026-11-13T10:00:01Z',
    }))
    registerEndpoint('/api/conversations/501/messages', () => [])
    registerEndpoint('/api/mcp-servers/elicitations', () => [])
    const page = await mountSuspended(Chat)
    await flushPromises()
    expect(notice(page).exists()).toBe(true)

    expect(await vmOf(page).resolveAndLoadConversation(501)).toBe(true)
    await flushPromises()

    expect(page.find('[data-testid="subagent-transcript-banner"]').exists()).toBe(true)
    expect(notice(page).exists()).toBe(false)
  })

  it('fetches the new agent\'s list on a switch and shows nothing of the previous agent\'s meanwhile', async () => {
    let release: () => void = () => {}
    const fourFetched = vi.fn()
    setupChatApi({
      3: () => [skill('gamma-presenter', { needsValues: ['GAMMA_API_KEY'], withheld: true })],
      4: () => {
        fourFetched()
        return new Promise((resolve) => {
          release = () => resolve([skill('jira', { needsValues: ['JIRA_URL'], withheld: true })])
        })
      },
    })
    const page = await mountSuspended(Chat)
    await flushPromises()
    expect(items(page)).toEqual(['withheld-skill-gamma-presenter'])

    vmOf(page).selectedAgentId = 4
    await flushPromises()

    expect(fourFetched).toHaveBeenCalledTimes(1)
    expect(notice(page).exists()).toBe(false)

    release()
    await vi.waitFor(() => expect(items(page)).toEqual(['withheld-skill-jira']))
    expect(page.find('[data-testid="withheld-skill-link-jira"]').attributes('href')).toBe('/skills?agent=4&skill=jira')
  })

  it('refetches the list when a turn ends and when the window regains focus', async () => {
    let skills = [skill('gamma-presenter', { needsValues: ['GAMMA_API_KEY'], withheld: true })]
    const fetched = vi.fn()
    setupChatApi({ 3: () => {
      fetched()
      return skills
    } })
    const page = await mountSuspended(Chat)
    await flushPromises()
    expect(fetched).toHaveBeenCalledTimes(1)
    expect(items(page)).toEqual(['withheld-skill-gamma-presenter'])

    skills = [skill('gamma-presenter')]
    window.dispatchEvent(new Event('focus'))
    await vi.waitFor(() => expect(notice(page).exists()).toBe(false))
    expect(fetched).toHaveBeenCalledTimes(2)

    skills = [skill('jira', { needsValues: ['JIRA_URL'], withheld: true })]
    vmOf(page).streaming = true
    await flushPromises()
    vmOf(page).streaming = false
    await vi.waitFor(() => expect(items(page)).toEqual(['withheld-skill-jira']))
    expect(fetched).toHaveBeenCalledTimes(3)
  })
})
