import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { clearNuxtData } from '#app'
import SettingsSoftwareFactoryPanel from '~/components/settings/SettingsSoftwareFactoryPanel.vue'

/**
 * The Software Factory panel's Setup section (JCLAW-1393): prerequisites, write-only credential
 * fields, the expanded/collapsed rule, and the installer job's polled output.
 */
interface Setup {
  installed: boolean
  prerequisites: { id: string, label: string, state: string, fix: string }[]
  hasModelCredential: boolean
  hasJira: boolean
  hasGithub: boolean
  installJobId: string | null
  message: string | null
}

const ALL_OK = [
  { id: 'macos', label: 'macOS', state: 'ok', fix: '' },
  { id: 'docker', label: 'Docker Desktop running', state: 'ok', fix: '' },
  { id: 'node', label: 'Node 24 or newer', state: 'ok', fix: '' },
  { id: 'checkout', label: 'JClaw checkout', state: 'ok', fix: '' },
]

let setup: Setup
let credentialPosts: Record<string, unknown>[] = []
let jobPolls = 0
let jobState = 'running'
let jobGone = false
let setupGets = 0

registerEndpoint('/api/factory/setup', {
  method: 'GET',
  handler: () => {
    setupGets++
    return setup
  },
})
registerEndpoint('/api/factory/setup/credentials', {
  method: 'POST',
  handler: async (event) => {
    const { readBody } = await import('h3')
    credentialPosts.push(await readBody(event))
    return { ...setup, message: 'Credentials saved.' }
  },
})
registerEndpoint('/api/factory/setup/install', {
  method: 'POST',
  handler: () => ({ id: 'job-1', state: 'running', elapsedMillis: 0, exitCode: null, timedOut: false, truncated: false, output: '' }),
})
registerEndpoint('/api/factory/setup/install/job-1', {
  method: 'GET',
  handler: async () => {
    jobPolls++
    if (jobGone) {
      const { createError } = await import('h3')
      throw createError({ statusCode: 404, data: { error: 'not_found', message: 'no such install job' } })
    }
    return jobState === 'running'
      ? { id: 'job-1', state: 'running', elapsedMillis: 1500, exitCode: null, timedOut: false, truncated: false, output: 'building image\n' }
      : { id: 'job-1', state: 'succeeded', elapsedMillis: 3000, exitCode: 0, timedOut: false, truncated: false, output: 'building image\nloaded com.jclaw.factory\n' }
  },
})

describe('Settings — Software Factory setup', () => {
  beforeEach(() => {
    clearNuxtData()
    credentialPosts = []
    jobPolls = 0
    jobState = 'running'
    jobGone = false
    setupGets = 0
    setup = {
      installed: false,
      prerequisites: [...ALL_OK.slice(0, 1), { id: 'docker', label: 'Docker Desktop running', state: 'missing', fix: 'Start Docker Desktop.' }, ...ALL_OK.slice(2)],
      hasModelCredential: false,
      hasJira: false,
      hasGithub: false,
      installJobId: null,
      message: null,
    }
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('renders the prerequisites with their state and fix', async () => {
    const component = await mountSuspended(SettingsSoftwareFactoryPanel)
    await flushPromises()
    const docker = component.find('[data-testid="factory-prereq-docker"]')
    expect(docker.text()).toContain('missing')
    expect(docker.text()).toContain('Start Docker Desktop.')
    expect(component.find('[data-testid="factory-prereq-macos"]').text()).toContain('ok')
  })

  it('collapses behind a Setup button once installed with everything in place', async () => {
    setup = { ...setup, installed: true, prerequisites: ALL_OK, hasModelCredential: true, hasJira: true }
    const component = await mountSuspended(SettingsSoftwareFactoryPanel)
    await flushPromises()
    expect(component.find('[data-testid="factory-setup-body"]').exists()).toBe(false)
    await component.find('[data-testid="factory-setup-toggle"]').trigger('click')
    expect(component.find('[data-testid="factory-setup-body"]').exists()).toBe(true)
  })

  it('stays expanded while a credential is missing', async () => {
    setup = { ...setup, installed: true, prerequisites: ALL_OK, hasModelCredential: true, hasJira: false }
    const component = await mountSuspended(SettingsSoftwareFactoryPanel)
    await flushPromises()
    expect(component.find('[data-testid="factory-setup-body"]').exists()).toBe(true)
    expect(component.find('[data-testid="factory-setup-toggle"]').exists()).toBe(false)
  })

  it('never shows a stored credential: saved fields open as empty password inputs', async () => {
    setup = { ...setup, hasModelCredential: true, hasJira: true, hasGithub: true }
    const component = await mountSuspended(SettingsSoftwareFactoryPanel)
    await flushPromises()
    for (const id of ['factory-model-credential', 'factory-jira-token', 'factory-github-token']) {
      expect(component.find(`#${id}`).exists(), id).toBe(false)
      await component.find(`[data-testid="${id}"] button[aria-label^="Edit"]`).trigger('click')
      const input = component.find(`#${id}`)
      expect(input.attributes('type'), id).toBe('password')
      expect((input.element as HTMLInputElement).value, id).toBe('')
    }
    const url = component.find('[data-testid="factory-jira-url"]')
    expect((url.element as HTMLInputElement).value).toBe('')
  })

  it('posts only the filled fields and clears them', async () => {
    const component = await mountSuspended(SettingsSoftwareFactoryPanel)
    await flushPromises()
    await component.find('[data-testid="factory-model-kind"]').setValue('anthropicApiKey')
    await component.find('#factory-model-credential').setValue('sk-test')
    await component.find('#factory-github-token').setValue('gh-test')
    await component.find('[data-testid="factory-credentials"]').trigger('submit')
    await vi.waitFor(() => expect(component.text()).toContain('Credentials saved.'))
    expect(credentialPosts).toEqual([{ anthropicApiKey: 'sk-test', githubToken: 'gh-test' }])
    expect((component.find('#factory-model-credential').element as HTMLInputElement).value).toBe('')
  })

  it('runs the installer and polls its output until it succeeds', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
    const component = await mountSuspended(SettingsSoftwareFactoryPanel)
    await flushPromises()
    expect(component.find('[data-testid="factory-install-note"]').text()).toContain('a story it is building is interrupted')
    const run = component.find('[data-testid="factory-install-run"]')
    await run.trigger('click')
    await flushPromises()
    expect(component.find('[data-testid="factory-install-state"]').text()).toContain('running')
    expect(run.attributes('disabled')).toBeDefined()

    await vi.advanceTimersByTimeAsync(1600)
    await flushPromises()
    expect(jobPolls).toBeGreaterThan(0)
    expect(component.find('[data-testid="factory-install-output"]').text()).toContain('building image')

    const getsWhileRunning = setupGets
    jobState = 'succeeded'
    await vi.advanceTimersByTimeAsync(1600)
    await flushPromises()
    expect(component.find('[data-testid="factory-install-state"]').text()).toContain('succeeded')
    expect(component.find('[data-testid="factory-install-output"]').text()).toContain('loaded com.jclaw.factory')
    await vi.waitFor(() => expect(setupGets).toBeGreaterThan(getsWhileRunning))
    expect(component.find('[data-testid="factory-install-run"]').attributes('disabled')).toBeUndefined()
    const polls = jobPolls
    await vi.advanceTimersByTimeAsync(3200)
    expect(jobPolls).toBe(polls)
  })

  it('resumes a running install on mount', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
    setup = { ...setup, installJobId: 'job-1' }
    const component = await mountSuspended(SettingsSoftwareFactoryPanel)
    await vi.waitFor(() =>
      expect(component.find('[data-testid="factory-install-output"]').text()).toContain('building image'))
    jobState = 'succeeded'
    await vi.advanceTimersByTimeAsync(1600)
    await flushPromises()
    expect(component.find('[data-testid="factory-install-state"]').text()).toContain('succeeded')
  })

  it('drops a job the server no longer knows and stops polling', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
    const component = await mountSuspended(SettingsSoftwareFactoryPanel)
    await flushPromises()
    await component.find('[data-testid="factory-install-run"]').trigger('click')
    await flushPromises()
    expect(component.find('[data-testid="factory-install-state"]').exists()).toBe(true)
    const getsWhileRunning = setupGets

    jobGone = true
    await vi.advanceTimersByTimeAsync(1600)
    await flushPromises()
    await vi.waitFor(() => expect(component.find('[data-testid="factory-install-state"]').exists()).toBe(false))
    expect(component.find('[data-testid="factory-install-run"]').attributes('disabled')).toBeUndefined()
    await vi.waitFor(() => expect(setupGets).toBeGreaterThan(getsWhileRunning))
    const polls = jobPolls
    await vi.advanceTimersByTimeAsync(3200)
    expect(jobPolls).toBe(polls)
  })
})
