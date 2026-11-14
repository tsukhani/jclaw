import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises, type VueWrapper } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { setResponseStatus } from 'h3'
import { clearNuxtData } from '#app'
import SkillSecretsPanel from '~/components/SkillSecretsPanel.vue'
import ConfirmDialog from '~/components/ConfirmDialog.vue'

/**
 * JF-323: the Secrets panel of an agent's skill. It shows names, hosts and when each was set, never a value, sends
 * exactly what the operator typed, and forgets a typed value once it is sent.
 */

interface Secret { name: string, hosts: string[], setAt: string }

function harness(agentId: number, skillName = 'demo') {
  return defineComponent({
    setup() {
      return () => h('div', [h(SkillSecretsPanel, { agentId, skillName }), h(ConfirmDialog)])
    },
  })
}

function dialogButton(text: string) {
  return Array.from(document.body.querySelectorAll<HTMLButtonElement>('[role="dialog"] button'))
    .find(b => (b.textContent ?? '').trim() === text)
}

async function settle() {
  await flushPromises()
  await flushPromises()
}

describe('SkillSecretsPanel', () => {
  beforeEach(() => {
    clearNuxtData()
  })

  afterEach(() => {
    document.body.querySelectorAll('[role="dialog"]').forEach(el => el.remove())
  })

  it('lists each secret by name, hosts and when it was set', async () => {
    registerEndpoint('/api/agents/31/skills/demo/secrets', () => ({
      secrets: [{ name: 'NAS_PASSWORD', hosts: ['nas.example.com', 'backup.example.com'], setAt: '2026-11-12T08:00:00Z' }],
    }))
    const wrapper = await mountSuspended(SkillSecretsPanel, { props: { agentId: 31, skillName: 'demo' } })
    await settle()

    const row = wrapper.find('[data-testid="secret-NAS_PASSWORD"]')
    expect(row.text()).toContain('NAS_PASSWORD')
    expect(row.text()).toContain(`Sent only to nas.example.com, backup.example.com · set ${new Date('2026-11-12T08:00:00Z').toLocaleString()}`)
  })

  it('says None when the skill has no secret', async () => {
    registerEndpoint('/api/agents/32/skills/demo/secrets', () => ({ secrets: [] }))
    const wrapper = await mountSuspended(SkillSecretsPanel, { props: { agentId: 32, skillName: 'demo' } })
    await settle()
    expect(wrapper.text()).toContain('None.')
  })

  it('adds a secret with its value and hosts, forgets the value, and re-reads the list', async () => {
    const stored: Secret[] = []
    let sent: Record<string, unknown> | null = null
    registerEndpoint('/api/agents/33/skills/demo/secrets', () => ({ secrets: stored }))
    registerEndpoint('/api/agents/33/skills/demo/secrets/API_KEY', {
      method: 'PUT',
      handler: async (event) => {
        const { readBody } = await import('h3')
        sent = await readBody(event) as Record<string, unknown>
        stored.push({ name: 'API_KEY', hosts: ['api.example.com', 'b.example.com:8443'], setAt: '2026-11-12T09:00:00Z' })
        return stored[0]
      },
    })
    const wrapper = await mountSuspended(SkillSecretsPanel, { props: { agentId: 33, skillName: 'demo' } })
    await settle()

    await wrapper.find('[data-testid="secret-add"]').trigger('click')
    await wrapper.find('input[name="secret-name"]').setValue('API_KEY')
    await wrapper.find('input[name="secret-value"]').setValue('fake-typed-value')
    await wrapper.find('input[name="secret-hosts"]').setValue('api.example.com, b.example.com:8443')
    await wrapper.find('[data-testid="secret-form"]').trigger('submit')
    await settle()

    await vi.waitFor(() => expect(wrapper.find('[data-testid="secret-API_KEY"]').exists()).toBe(true))
    expect(sent).toEqual({ value: 'fake-typed-value', hosts: ['api.example.com', 'b.example.com:8443'] })
    expect(wrapper.find('[data-testid="secret-form"]').exists()).toBe(false)
    expect(wrapper.html()).not.toContain('fake-typed-value')
  })

  it('replacing with the value left empty sends the hosts alone, so the stored value stays', async () => {
    let sent: Record<string, unknown> | null = null
    registerEndpoint('/api/agents/34/skills/demo/secrets', () => ({
      secrets: [{ name: 'API_KEY', hosts: ['api.example.com'], setAt: '2026-11-12T09:00:00Z' }],
    }))
    registerEndpoint('/api/agents/34/skills/demo/secrets/API_KEY', {
      method: 'PUT',
      handler: async (event) => {
        const { readBody } = await import('h3')
        sent = await readBody(event) as Record<string, unknown>
        return { name: 'API_KEY', hosts: ['api.example.com', 'new.example.com'], setAt: '2026-11-12T10:00:00Z' }
      },
    })
    const wrapper = await mountSuspended(SkillSecretsPanel, { props: { agentId: 34, skillName: 'demo' } })
    await settle()

    await wrapper.find('button[aria-label="Replace API_KEY"]').trigger('click')
    expect((wrapper.find('input[name="secret-hosts"]').element as HTMLInputElement).value).toBe('api.example.com')
    expect(wrapper.find('input[name="secret-name"]').attributes('readonly')).toBeDefined()
    await wrapper.find('input[name="secret-hosts"]').setValue('api.example.com new.example.com')
    await wrapper.find('[data-testid="secret-form"]').trigger('submit')
    await settle()

    expect(sent).toEqual({ hosts: ['api.example.com', 'new.example.com'] })
  })

  it('shows a refused write in the server\'s words, keeps the form open and forgets the value', async () => {
    registerEndpoint('/api/agents/35/skills/demo/secrets', () => ({ secrets: [] }))
    registerEndpoint('/api/agents/35/skills/demo/secrets/API_KEY', {
      method: 'PUT',
      handler: (event) => {
        setResponseStatus(event, 400)
        return {
          type: 'error',
          code: 'invalid_request',
          message: '\'https://api.example.com\' is not a host: give the name alone, such as api.example.com or'
            + ' api.example.com:8443, with no https://, path or *.',
        }
      },
    })
    const wrapper = await mountSuspended(SkillSecretsPanel, { props: { agentId: 35, skillName: 'demo' } })
    await settle()

    await wrapper.find('[data-testid="secret-add"]').trigger('click')
    await wrapper.find('input[name="secret-name"]').setValue('API_KEY')
    await wrapper.find('input[name="secret-value"]').setValue('fake-typed-value')
    await wrapper.find('input[name="secret-hosts"]').setValue('https://api.example.com')
    await wrapper.find('[data-testid="secret-form"]').trigger('submit')
    await settle()

    expect(wrapper.find('[data-testid="secret-form"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('\'https://api.example.com\' is not a host')
    expect((wrapper.find('input[name="secret-value"]').element as HTMLInputElement).value).toBe('')
  })

  it('cancelling the confirmation deletes nothing', async () => {
    let deleted = false
    registerEndpoint('/api/agents/36/skills/demo/secrets', () => ({
      secrets: [{ name: 'API_KEY', hosts: ['api.example.com'], setAt: '2026-11-12T09:00:00Z' }],
    }))
    registerEndpoint('/api/agents/36/skills/demo/secrets/API_KEY', {
      method: 'DELETE',
      handler: () => {
        deleted = true
        return { deleted: true }
      },
    })
    const wrapper = await mountSuspended(harness(36))
    await settle()

    await wrapper.find('button[aria-label="Delete API_KEY"]').trigger('click')
    await settle()
    expect(document.body.textContent).toContain('Delete API_KEY? The skill can no longer use it, and its value cannot be recovered.')
    dialogButton('Cancel')!.click()
    await settle()

    expect(deleted).toBe(false)
    expect(wrapper.find('[data-testid="secret-API_KEY"]').exists()).toBe(true)
  })

  it('deletes a secret once confirmed and re-reads the list', async () => {
    let deleted = false
    registerEndpoint('/api/agents/37/skills/demo/secrets', () => ({
      secrets: deleted ? [] : [{ name: 'API_KEY', hosts: ['api.example.com'], setAt: '2026-11-12T09:00:00Z' }],
    }))
    registerEndpoint('/api/agents/37/skills/demo/secrets/API_KEY', {
      method: 'DELETE',
      handler: () => {
        deleted = true
        return { deleted: true }
      },
    })
    const wrapper = await mountSuspended(harness(37))
    await settle()

    await wrapper.find('button[aria-label="Delete API_KEY"]').trigger('click')
    await settle()
    dialogButton('Delete')!.click()

    await vi.waitFor(() => expect(wrapper.find('[data-testid="secret-API_KEY"]').exists()).toBe(false))
    expect(deleted).toBe(true)
    expect(wrapper.text()).toContain('None.')
  })

  // ===== JF-348: what the skill declares in its SKILL.md =====

  const declared = {
    secrets: [{ name: 'NAS_PASS', hosts: ['nas.example.com:5001'], setAt: '2026-11-12T08:00:00Z' }],
    expected: [
      { name: 'API_KEY', description: 'The service\'s API key', exampleHosts: ['api.example.com'], optional: false, set: false, setIn: null },
      { name: 'NAS_PASS', description: 'The password itself', exampleHosts: ['nas.example.com:5001'], optional: false, set: true, setIn: 'demo' },
      { name: 'PROXY_PASS', description: 'Optional. The proxy\'s password', exampleHosts: [], optional: true, set: false, setIn: null },
      { name: 'SHARED_PASS', description: 'Shared with another skill', exampleHosts: [], optional: false, set: true, setIn: 'other' },
    ],
    missing: ['API_KEY'],
  }

  it('lists each declared secret with its state and says the skill is not runnable', async () => {
    registerEndpoint('/api/agents/41/skills/demo/secrets', () => declared)
    const wrapper = await mountSuspended(SkillSecretsPanel, { props: { agentId: 41, skillName: 'demo' } })
    await settle()

    expect(wrapper.find('[data-testid="secrets-not-runnable"]').text().replace(/\s+/g, ' '))
      .toBe('Not runnable: this needs a value before the agent can use this skill: API_KEY')
    expect(wrapper.find('[data-testid="declared-API_KEY"]').text()).toContain('Needs a value')
    expect(wrapper.find('[data-testid="declared-API_KEY"]').text()).toContain('The service\'s API key')
    expect(wrapper.find('[data-testid="declared-PROXY_PASS"]').text()).toContain('Optional, not set')
    expect(wrapper.find('[data-testid="declared-SHARED_PASS"]').text()).toContain('Set in other')
    expect(wrapper.find('button[aria-label="Add SHARED_PASS"]').exists()).toBe(false)
    // the one this skill holds is a stored row, with its description, not a declared-only row
    expect(wrapper.find('[data-testid="declared-NAS_PASS"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="secret-NAS_PASS"]').text()).toContain('The password itself')
  })

  it('marks a secret held by a switched-off skill as still missing, with no Add', async () => {
    registerEndpoint('/api/agents/44/skills/demo/secrets', () => ({
      secrets: [],
      expected: [
        { name: 'SHARED_PASS', description: 'Shared with another skill', exampleHosts: [], optional: false, set: false, setIn: 'other' },
      ],
      missing: ['SHARED_PASS'],
    }))
    const wrapper = await mountSuspended(SkillSecretsPanel, { props: { agentId: 44, skillName: 'demo' } })
    await settle()

    expect(wrapper.find('[data-testid="declared-SHARED_PASS"]').text()).toContain('Set in other, which is switched off')
    expect(wrapper.find('button[aria-label="Add SHARED_PASS"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="secrets-not-runnable"]').exists()).toBe(true)
  })

  it('shows the caution only for a secret the skill declares', async () => {
    registerEndpoint('/api/agents/45/skills/demo/secrets', () => declared)
    const wrapper = await mountSuspended(SkillSecretsPanel, { props: { agentId: 45, skillName: 'demo' } })
    await settle()

    await wrapper.find('[data-testid="secret-add"]').trigger('click')
    expect(wrapper.find('[data-testid="secret-form"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="secret-form-caution"]').exists()).toBe(false)
  })

  it('shows no notice when nothing required is missing', async () => {
    registerEndpoint('/api/agents/42/skills/demo/secrets', () => ({ ...declared, missing: [] }))
    const wrapper = await mountSuspended(SkillSecretsPanel, { props: { agentId: 42, skillName: 'demo' } })
    await settle()
    expect(wrapper.find('[data-testid="secrets-not-runnable"]').exists()).toBe(false)
  })

  it('adding a declared secret fixes its name, offers its example host, and tells the page', async () => {
    let sent: Record<string, unknown> | null = null
    registerEndpoint('/api/agents/43/skills/demo/secrets', () => declared)
    registerEndpoint('/api/agents/43/skills/demo/secrets/API_KEY', {
      method: 'PUT',
      handler: async (event) => {
        const { readBody } = await import('h3')
        sent = await readBody(event) as Record<string, unknown>
        return { name: 'API_KEY', hosts: ['api.internal.example'], setAt: '2026-11-12T09:00:00Z' }
      },
    })
    const wrapper = await mountSuspended(SkillSecretsPanel, { props: { agentId: 43, skillName: 'demo' } })
    await settle()

    await wrapper.find('button[aria-label="Add API_KEY"]').trigger('click')
    const name = wrapper.find('input[name="secret-name"]')
    expect((name.element as HTMLInputElement).value).toBe('API_KEY')
    expect(name.attributes('readonly')).toBeDefined()
    expect(wrapper.find('input[name="secret-hosts"]').attributes('placeholder')).toBe('for example api.example.com')
    expect(wrapper.find('[data-testid="secret-form-hint"]').text()).toBe('The service\'s API key')
    expect(wrapper.find('[data-testid="secret-form-caution"]').text().replace(/\s+/g, ' ')).toBe(
      'The description and the example host come from the skill\'s own files, which the agent can change. '
      + 'Enter the host of the real service: the secret is sent to every host you type here.')

    await wrapper.find('input[name="secret-value"]').setValue('fake-typed-value')
    await wrapper.find('input[name="secret-hosts"]').setValue('api.internal.example')
    await wrapper.find('[data-testid="secret-form"]').trigger('submit')
    await vi.waitFor(() => expect(wrapper.emitted('changed')).toHaveLength(1))
    expect(sent).toEqual({ value: 'fake-typed-value', hosts: ['api.internal.example'] })
  })

  // ===== JF-357: settings, values that are not secret =====

  const withSettings = {
    secrets: [],
    expected: [],
    settings: [{ name: 'SITE_URL', value: 'https://site.example.org/app', setAt: '2026-11-13T00:00:00Z' }],
    expectedSettings: [
      { name: 'SITE_URL', description: 'The site\'s address', example: 'https://example.com', optional: false, set: true, setIn: 'demo' },
      { name: 'SITE_USER', description: 'The sign-in user', example: 'admin', optional: false, set: false, setIn: null },
      { name: 'SITE_REGION', description: 'Optional. The region', example: 'eu', optional: true, set: false, setIn: null },
    ],
    missing: ['SITE_USER'],
  }

  it('lists each setting with its value, or what it still needs', async () => {
    registerEndpoint('/api/agents/51/skills/demo/secrets', () => withSettings)
    const wrapper = await mountSuspended(SkillSecretsPanel, { props: { agentId: 51, skillName: 'demo' } })
    await settle()

    expect(wrapper.find('[data-testid="setting-SITE_URL"]').text()).toContain('https://site.example.org/app')
    expect(wrapper.find('[data-testid="setting-SITE_USER"]').text()).toContain('Needs a value')
    expect(wrapper.find('[data-testid="setting-SITE_REGION"]').text()).toContain('Optional, not set')
    expect(wrapper.find('[data-testid="secrets-not-runnable"]').text().replace(/\s+/g, ' '))
      .toBe('Not runnable: this needs a value before the agent can use this skill: SITE_USER')
  })

  it('editing a setting sends its value and tells the page', async () => {
    let sent: Record<string, unknown> | null = null
    registerEndpoint('/api/agents/52/skills/demo/secrets', () => withSettings)
    registerEndpoint('/api/agents/52/skills/demo/settings/SITE_USER', {
      method: 'PUT',
      handler: async (event) => {
        const { readBody } = await import('h3')
        sent = await readBody(event) as Record<string, unknown>
        return { name: 'SITE_USER', value: 'operator', setAt: '2026-11-13T01:00:00Z' }
      },
    })
    const wrapper = await mountSuspended(SkillSecretsPanel, { props: { agentId: 52, skillName: 'demo' } })
    await settle()

    await wrapper.find('button[aria-label="Add SITE_USER"]').trigger('click')
    const name = wrapper.find('input[name="setting-name"]')
    expect((name.element as HTMLInputElement).value).toBe('SITE_USER')
    expect(name.attributes('readonly')).toBeDefined()
    expect(wrapper.find('input[name="setting-value"]').attributes('placeholder')).toBe('for example admin')
    expect(wrapper.find('input[name="setting-value"]').attributes('type')).toBe('text')
    expect(wrapper.find('[data-testid="setting-form-caution"]').text().replace(/\s+/g, ' '))
      .toBe('Shown in clear and given to the skill\'s commands as it is. Never type a password, key or token here: add it under Secrets.')

    await wrapper.find('input[name="setting-value"]').setValue('operator')
    await wrapper.find('[data-testid="setting-form"]').trigger('submit')
    await vi.waitFor(() => expect(wrapper.emitted('changed')).toHaveLength(1))
    expect(sent).toEqual({ value: 'operator' })
  })
  // ===== JF-352: files an update kept from the skill's old copy =====

  const keptFile = { id: 'k1', path: 'credentials/gamma-api-key.txt', size: 20, keptAt: '2026-11-13T10:00:00Z' }
  const keptLabel = `Delete kept file credentials/gamma-api-key.txt, kept ${new Date('2026-11-13T10:00:00Z').toLocaleString()}`
  const keptNote = (name: string) => `If the file has plain assignments such as NAME=value, JClaw takes the value from the`
    + ` line for ${name}. If it has none, the whole file is the value, without spaces or line breaks at either end. JClaw`
    + ' deletes the kept file once each name in it that this skill asks for has a value.'
  const keptSettingNote = (name: string) => `JClaw takes the value from the file's plain assignment for ${name}, a line such`
    + ' as NAME=value. JClaw deletes the kept file once each name in it that this skill asks for has a value.'

  it('lists each kept file by path, size and when it was kept, and nothing of what it holds', async () => {
    registerEndpoint('/api/agents/61/skills/demo/secrets', () => ({ secrets: [] }))
    registerEndpoint('/api/agents/61/skills/demo/kept', () => ({ kept: [keptFile] }))
    const wrapper = await mountSuspended(SkillSecretsPanel, { props: { agentId: 61, skillName: 'demo' } })
    await settle()

    const row = wrapper.find('[data-testid="kept-credentials/gamma-api-key.txt"]')
    expect(row.findAll('.min-w-0 > div').map(d => d.text())).toEqual(
      ['credentials/gamma-api-key.txt', `20 B · kept ${new Date('2026-11-13T10:00:00Z').toLocaleString()}`])
    expect(row.find(`button[aria-label="${keptLabel}"]`).exists()).toBe(true)
  })

  it('names each Delete by its kept time, so two updates that kept one path read apart', async () => {
    const later = { id: 'k9', path: keptFile.path, size: 21, keptAt: '2026-11-13T12:00:00Z' }
    registerEndpoint('/api/agents/68/skills/demo/secrets', () => ({ secrets: [] }))
    registerEndpoint('/api/agents/68/skills/demo/kept', () => ({ kept: [later, keptFile] }))
    const wrapper = await mountSuspended(SkillSecretsPanel, { props: { agentId: 68, skillName: 'demo' } })
    await settle()

    expect(wrapper.findAll('[data-testid="skill-kept"] button').map(b => b.attributes('aria-label'))).toEqual([
      `Delete kept file credentials/gamma-api-key.txt, kept ${new Date('2026-11-13T12:00:00Z').toLocaleString()}`,
      keptLabel,
    ])
  })

  it('shows no kept files and no Value from choice when nothing is kept', async () => {
    registerEndpoint('/api/agents/62/skills/demo/secrets', () => ({ secrets: [] }))
    registerEndpoint('/api/agents/62/skills/demo/kept', () => ({ kept: [] }))
    const wrapper = await mountSuspended(SkillSecretsPanel, { props: { agentId: 62, skillName: 'demo' } })
    await settle()

    expect(wrapper.find('[data-testid="skill-kept"]').exists()).toBe(false)
    await wrapper.find('[data-testid="secret-add"]').trigger('click')
    expect(wrapper.find('select[name="secret-source"]').exists()).toBe(false)
    expect(wrapper.find('input[name="secret-value"]').exists()).toBe(true)
  })

  it('cancelling the confirmation deletes no kept file', async () => {
    let deleted = false
    registerEndpoint('/api/agents/63/skills/demo/secrets', () => ({ secrets: [] }))
    registerEndpoint('/api/agents/63/skills/demo/kept', () => ({ kept: [keptFile] }))
    registerEndpoint('/api/agents/63/skills/demo/kept/k1', {
      method: 'DELETE',
      handler: () => {
        deleted = true
        return { deleted: true }
      },
    })
    const wrapper = await mountSuspended(harness(63))
    await settle()

    await wrapper.find(`button[aria-label="${keptLabel}"]`).trigger('click')
    await settle()
    expect(document.body.textContent).toContain('Delete the kept copy of credentials/gamma-api-key.txt? The skill\'s own folder is not changed.')
    dialogButton('Cancel')!.click()
    await settle()

    expect(deleted).toBe(false)
    expect(wrapper.find('[data-testid="kept-credentials/gamma-api-key.txt"]').exists()).toBe(true)
  })

  it('deletes a kept file once confirmed and re-reads the list', async () => {
    let deleted = false
    registerEndpoint('/api/agents/64/skills/demo/secrets', () => ({ secrets: [] }))
    registerEndpoint('/api/agents/64/skills/demo/kept', () => ({ kept: deleted ? [] : [keptFile] }))
    registerEndpoint('/api/agents/64/skills/demo/kept/k1', {
      method: 'DELETE',
      handler: () => {
        deleted = true
        return { deleted: true }
      },
    })
    const wrapper = await mountSuspended(harness(64))
    await settle()

    await wrapper.find(`button[aria-label="${keptLabel}"]`).trigger('click')
    await settle()
    dialogButton('Delete')!.click()

    await vi.waitFor(() => expect(wrapper.find('[data-testid="skill-kept"]').exists()).toBe(false))
    expect(deleted).toBe(true)
  })

  it('a kept delete that finds the file already gone still re-reads the list, and says so', async () => {
    let gone = false
    registerEndpoint('/api/agents/69/skills/demo/secrets', () => ({ secrets: [] }))
    registerEndpoint('/api/agents/69/skills/demo/kept', () => ({ kept: gone ? [] : [keptFile] }))
    registerEndpoint('/api/agents/69/skills/demo/kept/k1', {
      method: 'DELETE',
      handler: (event) => {
        gone = true
        setResponseStatus(event, 404)
        return { type: 'error', code: 'not_found', message: 'No kept file \'k1\' on skill \'demo\'.' }
      },
    })
    const wrapper = await mountSuspended(harness(69))
    await settle()

    await wrapper.find(`button[aria-label="${keptLabel}"]`).trigger('click')
    await settle()
    dialogButton('Delete')!.click()

    await vi.waitFor(() => expect(wrapper.find('[data-testid="kept-credentials/gamma-api-key.txt"]').exists()).toBe(false))
    expect(wrapper.find('[data-testid="skill-kept"]').text()).toContain('No kept file \'k1\' on skill \'demo\'.')
  })

  it('a secret can take its value from a kept file: the request names the file and carries no value', async () => {
    let sent: Record<string, unknown> | null = null
    let imported = false
    registerEndpoint('/api/agents/65/skills/demo/secrets', () => ({
      secrets: imported ? [{ name: 'GAMMA_API_KEY', hosts: ['public-api.gamma.app'], setAt: '2026-11-13T11:00:00Z' }] : [],
    }))
    registerEndpoint('/api/agents/65/skills/demo/kept', () => ({ kept: imported ? [] : [keptFile] }))
    registerEndpoint('/api/agents/65/skills/demo/secrets/GAMMA_API_KEY', {
      method: 'PUT',
      handler: async (event) => {
        const { readBody } = await import('h3')
        sent = await readBody(event) as Record<string, unknown>
        imported = true
        return { name: 'GAMMA_API_KEY', hosts: ['public-api.gamma.app'], setAt: '2026-11-13T11:00:00Z' }
      },
    })
    const wrapper = await mountSuspended(SkillSecretsPanel, { props: { agentId: 65, skillName: 'demo' } })
    await settle()

    await wrapper.find('[data-testid="secret-add"]').trigger('click')
    await wrapper.find('input[name="secret-name"]').setValue('GAMMA_API_KEY')
    await wrapper.find('select[name="secret-source"]').setValue('k1')
    expect(wrapper.find('input[name="secret-value"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="secret-form-kept-note"]').text().replace(/\s+/g, ' ')).toBe(keptNote('GAMMA_API_KEY'))
    await wrapper.find('input[name="secret-hosts"]').setValue('public-api.gamma.app')
    await wrapper.find('[data-testid="secret-form"]').trigger('submit')

    await vi.waitFor(() => expect(wrapper.find('[data-testid="secret-GAMMA_API_KEY"]').exists()).toBe(true))
    expect(sent).toEqual({ fromKept: 'k1', hosts: ['public-api.gamma.app'] })
    expect(wrapper.find('[data-testid="skill-kept"]').exists()).toBe(false)
    expect(wrapper.emitted('changed')).toHaveLength(1)
  })

  it('choosing to type the value again sends the typed value, not the kept file', async () => {
    let sent: Record<string, unknown> | null = null
    registerEndpoint('/api/agents/66/skills/demo/secrets', () => ({ secrets: [] }))
    registerEndpoint('/api/agents/66/skills/demo/kept', () => ({ kept: [keptFile] }))
    registerEndpoint('/api/agents/66/skills/demo/secrets/API_KEY', {
      method: 'PUT',
      handler: async (event) => {
        const { readBody } = await import('h3')
        sent = await readBody(event) as Record<string, unknown>
        return { name: 'API_KEY', hosts: ['api.example.com'], setAt: '2026-11-13T11:00:00Z' }
      },
    })
    const wrapper = await mountSuspended(SkillSecretsPanel, { props: { agentId: 66, skillName: 'demo' } })
    await settle()

    await wrapper.find('[data-testid="secret-add"]').trigger('click')
    await wrapper.find('input[name="secret-name"]').setValue('API_KEY')
    await wrapper.find('select[name="secret-source"]').setValue('k1')
    await wrapper.find('select[name="secret-source"]').setValue('')
    await wrapper.find('input[name="secret-value"]').setValue('fake-typed-value')
    await wrapper.find('input[name="secret-hosts"]').setValue('api.example.com')
    await wrapper.find('[data-testid="secret-form"]').trigger('submit')

    await vi.waitFor(() => expect(sent).not.toBeNull())
    expect(sent).toEqual({ value: 'fake-typed-value', hosts: ['api.example.com'] })
  })

  it('a setting can take its value from a kept file\'s line: the request names the file and carries no value', async () => {
    let sent: Record<string, unknown> | null = null
    registerEndpoint('/api/agents/67/skills/demo/secrets', () => withSettings)
    registerEndpoint('/api/agents/67/skills/demo/kept', () => ({
      kept: [{ id: 'k2', path: 'config.env', size: 31, keptAt: '2026-11-13T10:00:00Z' }],
    }))
    registerEndpoint('/api/agents/67/skills/demo/settings/SITE_USER', {
      method: 'PUT',
      handler: async (event) => {
        const { readBody } = await import('h3')
        sent = await readBody(event) as Record<string, unknown>
        return { name: 'SITE_USER', value: 'operator', setAt: '2026-11-13T11:00:00Z' }
      },
    })
    const wrapper = await mountSuspended(SkillSecretsPanel, { props: { agentId: 67, skillName: 'demo' } })
    await settle()

    await wrapper.find('button[aria-label="Add SITE_USER"]').trigger('click')
    await wrapper.find('select[name="setting-source"]').setValue('k2')
    expect(wrapper.find('input[name="setting-value"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="setting-form-kept-note"]').text().replace(/\s+/g, ' ')).toBe(keptSettingNote('SITE_USER'))
    await wrapper.find('[data-testid="setting-form"]').trigger('submit')

    await vi.waitFor(() => expect(wrapper.emitted('changed')).toHaveLength(1))
    expect(sent).toEqual({ fromKept: 'k2' })
  })

  // ===== JF-374: a renewed secret and its capture rule =====

  const rule = {
    method: 'POST', path: '/v1/sessions/current', from: 'json', field: 'session',
    credential: 'BACKOFFICE_PASSWORD', credentialIn: 'json password',
  }
  const inputValue = (wrapper: VueWrapper, name: string) =>
    (wrapper.find(`[name="${name}"]`).element as HTMLInputElement | HTMLSelectElement).value

  it('shows a renewed secret\'s rule and when the proxy last renewed it, or that it has no value yet', async () => {
    registerEndpoint('/api/agents/71/skills/demo/secrets', () => ({
      secrets: [
        { name: 'A_SESSION', hosts: ['api.example.com'], setAt: '2026-11-13T08:00:00Z', hasValue: true, capture: rule, renewedAt: '2026-11-13T09:00:00Z' },
        { name: 'B_SESSION', hosts: ['api.example.com'], setAt: '2026-11-13T08:00:00Z', hasValue: false, capture: { ...rule, from: 'header', field: 'X-Session', credential: 'B_SESSION', credentialIn: 'header api-key' }, renewedAt: null },
        { name: 'C_KEY', hosts: ['api.example.com'], setAt: '2026-11-13T08:00:00Z', hasValue: true, capture: null, renewedAt: null },
      ],
      expected: [
        { name: 'D_SESSION', description: 'The session key', exampleHosts: [], optional: false, set: false, setIn: null, renewed: true, suggested: rule },
      ],
      missing: [],
    }))
    const wrapper = await mountSuspended(SkillSecretsPanel, { props: { agentId: 71, skillName: 'demo' } })
    await settle()

    expect(wrapper.find('[data-testid="secret-A_SESSION-renewal"]').text().replace(/\s+/g, ' ')).toBe(
      'Taken from the answer to POST /v1/sessions/current, JSON field session, signed in with BACKOFFICE_PASSWORD in'
      + ` JSON field password · renewed ${new Date('2026-11-13T09:00:00Z').toLocaleString()}`)
    expect(wrapper.find('[data-testid="secret-B_SESSION-renewal"]').text().replace(/\s+/g, ' ')).toBe(
      'Taken from the answer to POST /v1/sessions/current, header X-Session, signed in with B_SESSION in header api-key'
      + ' · no value yet: JClaw stores one at the skill\'s next sign-in')
    expect(wrapper.find('[data-testid="secret-C_KEY-renewal"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="declared-D_SESSION"]').text()).toContain('Renewed at sign-in, not set')
  })

  it('adding a declared renewed secret starts with Renewed ticked and its suggested rule filled, flagged', async () => {
    let sent: Record<string, unknown> | null = null
    registerEndpoint('/api/agents/72/skills/demo/secrets', () => ({
      secrets: [{ name: 'BACKOFFICE_PASSWORD', hosts: ['api.example.com'], setAt: '2026-11-13T08:00:00Z', hasValue: true, capture: null, renewedAt: null }],
      expected: [
        { name: 'SESSION_KEY', description: 'The session key', exampleHosts: ['api.example.com'], optional: false, set: false, setIn: null, renewed: true, suggested: rule },
      ],
      missing: [],
    }))
    registerEndpoint('/api/agents/72/skills/demo/secrets/SESSION_KEY', {
      method: 'PUT',
      handler: async (event) => {
        const { readBody } = await import('h3')
        sent = await readBody(event) as Record<string, unknown>
        return { name: 'SESSION_KEY', hosts: ['api.internal.example'], setAt: '2026-11-13T09:00:00Z', hasValue: false, capture: rule, renewedAt: null }
      },
    })
    const wrapper = await mountSuspended(SkillSecretsPanel, { props: { agentId: 72, skillName: 'demo' } })
    await settle()

    await wrapper.find('button[aria-label="Add SESSION_KEY"]').trigger('click')
    expect((wrapper.find('input[name="secret-renew"]').element as HTMLInputElement).checked).toBe(true)
    expect(['secret-capture-method', 'secret-capture-path', 'secret-capture-from', 'secret-capture-field',
      'secret-capture-credential', 'secret-capture-credential-in', 'secret-capture-credential-field']
      .map(name => inputValue(wrapper, name)))
      .toEqual(['POST', '/v1/sessions/current', 'json', 'session', 'BACKOFFICE_PASSWORD', 'json', 'password'])
    expect(wrapper.find('[data-testid="secret-form-rule-caution"]').text().replace(/\s+/g, ' ')).toBe(
      'This rule comes from the skill\'s own files, which the agent can change. Check the method, the path, the'
      + ' field and the secret its sign-in sends against the service before you save.')
    expect(wrapper.find('input[name="secret-value"]').attributes('placeholder'))
      .toBe('Leave empty: JClaw takes it from the next sign-in')

    await wrapper.find('input[name="secret-hosts"]').setValue('api.internal.example')
    await wrapper.find('[data-testid="secret-form"]').trigger('submit')
    await vi.waitFor(() => expect(wrapper.emitted('changed')).toHaveLength(1))
    expect(sent).toEqual({ hosts: ['api.internal.example'], capture: rule })
  })

  it('a suggested rule\'s caution stays while any rule field still holds the suggestion, and goes once none does', async () => {
    registerEndpoint('/api/agents/81/skills/demo/secrets', () => ({
      secrets: [],
      expected: [
        { name: 'SESSION_KEY', description: '', exampleHosts: [], optional: false, set: false, setIn: null, renewed: true, suggested: { ...rule, credentialIn: 'header X-Password' } },
      ],
      missing: [],
    }))
    const wrapper = await mountSuspended(SkillSecretsPanel, { props: { agentId: 81, skillName: 'demo' } })
    await settle()

    await wrapper.find('button[aria-label="Add SESSION_KEY"]').trigger('click')
    const shown: string[] = []
    for (const [name, value] of [['secret-capture-method', 'PUT'], ['secret-capture-path', '/v2/sessions'],
      ['secret-capture-from', 'header'], ['secret-capture-field', 'token'], ['secret-capture-credential', 'SESSION_KEY'],
      ['secret-capture-credential-in', 'form'], ['secret-capture-credential-field', 'pass']] as const) {
      await wrapper.find(`[name="${name}"]`).setValue(value)
      if (wrapper.find('[data-testid="secret-form-rule-caution"]').exists()) shown.push(name)
    }
    expect(shown).toEqual(['secret-capture-method', 'secret-capture-path', 'secret-capture-from', 'secret-capture-field',
      'secret-capture-credential', 'secret-capture-credential-in'])
  })

  it('a suggested rule\'s caution goes once every typed field differs, though both choices still match', async () => {
    registerEndpoint('/api/agents/85/skills/demo/secrets', () => ({
      secrets: [],
      expected: [
        { name: 'SESSION_KEY', description: '', exampleHosts: [], optional: false, set: false, setIn: null, renewed: true, suggested: rule },
      ],
      missing: [],
    }))
    const wrapper = await mountSuspended(SkillSecretsPanel, { props: { agentId: 85, skillName: 'demo' } })
    await settle()

    await wrapper.find('button[aria-label="Add SESSION_KEY"]').trigger('click')
    for (const [name, value] of [['secret-capture-method', 'PUT'], ['secret-capture-path', '/v2/sessions'],
      ['secret-capture-field', 'token'], ['secret-capture-credential', 'SESSION_KEY'],
      ['secret-capture-credential-field', 'pass']] as const) {
      await wrapper.find(`[name="${name}"]`).setValue(value)
    }
    expect([inputValue(wrapper, 'secret-capture-from'), inputValue(wrapper, 'secret-capture-credential-in')])
      .toEqual(['json', 'json'])
    expect(wrapper.find('[data-testid="secret-form-rule-caution"]').exists()).toBe(false)
  })

  it('after Replace, ticking Renewed fills the whole rule from the suggestion and flags it', async () => {
    registerEndpoint('/api/agents/86/skills/demo/secrets', () => ({
      secrets: [{ name: 'SESSION_KEY', hosts: ['api.example.com'], setAt: '2026-11-13T08:00:00Z', hasValue: true, capture: null, renewedAt: null }],
      expected: [
        { name: 'SESSION_KEY', description: '', exampleHosts: [], optional: false, set: true, setIn: 'demo', setInState: 'offered', renewed: true, suggested: rule },
      ],
      missing: [],
    }))
    const wrapper = await mountSuspended(SkillSecretsPanel, { props: { agentId: 86, skillName: 'demo' } })
    await settle()

    await wrapper.find('button[aria-label="Replace SESSION_KEY"]').trigger('click')
    expect(wrapper.find('[data-testid="secret-form-rule"]').exists()).toBe(false)
    await wrapper.find('input[name="secret-renew"]').setValue(true)
    expect(['secret-capture-method', 'secret-capture-path', 'secret-capture-from', 'secret-capture-field',
      'secret-capture-credential', 'secret-capture-credential-in', 'secret-capture-credential-field']
      .map(name => inputValue(wrapper, name)))
      .toEqual(['POST', '/v1/sessions/current', 'json', 'session', 'BACKOFFICE_PASSWORD', 'json', 'password'])
    expect(wrapper.find('[data-testid="secret-form-rule-caution"]').exists()).toBe(true)
  })

  it('sends the secret the sign-in sends and the place it sends it, and hides the field name for Basic auth', async () => {
    const sent: Record<string, unknown>[] = []
    registerEndpoint('/api/agents/82/skills/demo/secrets', () => ({
      secrets: [{ name: 'BACKOFFICE_PASSWORD', hosts: ['api.example.com'], setAt: '2026-11-13T08:00:00Z', hasValue: true, capture: null, renewedAt: null }],
      expected: [],
      missing: [],
    }))
    registerEndpoint('/api/agents/82/skills/demo/secrets/SESSION_KEY', {
      method: 'PUT',
      handler: async (event) => {
        const { readBody } = await import('h3')
        sent.push(await readBody(event) as Record<string, unknown>)
        return { name: 'SESSION_KEY', hosts: ['api.example.com'], setAt: '2026-11-13T09:00:00Z', hasValue: false, capture: rule, renewedAt: null }
      },
    })
    const wrapper = await mountSuspended(SkillSecretsPanel, { props: { agentId: 82, skillName: 'demo' } })
    await settle()

    const fieldShown: boolean[] = []
    for (const [place, field] of [['json', ' password '], ['form', 'password'], ['header', 'X-Password'], ['basic', '']]) {
      await wrapper.find('[data-testid="secret-add"]').trigger('click')
      await wrapper.find('input[name="secret-name"]').setValue('SESSION_KEY')
      await wrapper.find('input[name="secret-hosts"]').setValue('api.example.com')
      await wrapper.find('input[name="secret-renew"]').setValue(true)
      expect(wrapper.findAll('[data-testid="secret-capture-credential-choices"] option').map(o => o.attributes('value')))
        .toEqual(['BACKOFFICE_PASSWORD', 'SESSION_KEY'])
      await wrapper.find('input[name="secret-capture-method"]').setValue('POST')
      await wrapper.find('input[name="secret-capture-path"]').setValue('/v1/sessions')
      await wrapper.find('input[name="secret-capture-field"]').setValue('session')
      await wrapper.find('input[name="secret-capture-credential"]').setValue(' BACKOFFICE_PASSWORD ')
      await wrapper.find('select[name="secret-capture-credential-in"]').setValue(place)
      fieldShown.push(wrapper.find('input[name="secret-capture-credential-field"]').exists())
      if (field) await wrapper.find('input[name="secret-capture-credential-field"]').setValue(field)
      await wrapper.find('[data-testid="secret-form"]').trigger('submit')
      await vi.waitFor(() => expect(wrapper.find('[data-testid="secret-form"]').exists()).toBe(false))
    }
    expect(fieldShown).toEqual([true, true, true, false])
    expect(sent.map(b => (b.capture as Record<string, string>).credentialIn))
      .toEqual(['json password', 'form password', 'header X-Password', 'basic'])
    expect(sent[0]).toEqual({
      hosts: ['api.example.com'],
      capture: { method: 'POST', path: '/v1/sessions', from: 'json', field: 'session', credential: 'BACKOFFICE_PASSWORD', credentialIn: 'json password' },
    })
  })

  it('the rule\'s help says what makes a sign-in the skill\'s, that a JSON field is top-level, and that no cookie is taken', async () => {
    registerEndpoint('/api/agents/83/skills/demo/secrets', () => ({ secrets: [], expected: [], missing: [] }))
    const wrapper = await mountSuspended(SkillSecretsPanel, { props: { agentId: 83, skillName: 'demo' } })
    await settle()

    await wrapper.find('[data-testid="secret-add"]').trigger('click')
    await wrapper.find('input[name="secret-renew"]').setValue(true)
    expect(wrapper.findAll('[data-testid="secret-form-rule-help"]').map(p => p.text().replace(/\s+/g, ' '))).toEqual([
      'When a success or a redirect from one of the hosts above answers this method and path, and the sign-in sent the'
      + ' secret named below by its stand-in, once, in the place named, JClaw stores the value it finds in the answer,'
      + ' and the command gets its stand-in instead. Such an answer that does not hold the value is withheld, and so is'
      + ' one to a sign-in that sent the secret more than once or anywhere else. A sign-in that never sent it passes as'
      + ' before.',
      'A JSON field, in the answer or in the sign-in, is one at the top level, not inside another object. A form field'
      + ' may name a nested one, such as user[password]. A cookie cannot be taken.',
    ])
  })

  it('a rule taken from a stored secret is not flagged, and unticking Renewed sends its removal', async () => {
    let sent: Record<string, unknown> | null = null
    registerEndpoint('/api/agents/73/skills/demo/secrets', () => ({
      secrets: [{ name: 'SESSION_KEY', hosts: ['api.example.com'], setAt: '2026-11-13T08:00:00Z', hasValue: true, capture: { ...rule, from: 'header', field: 'X-Session', credential: 'SESSION_KEY', credentialIn: 'basic' }, renewedAt: null }],
      expected: [
        { name: 'SESSION_KEY', description: 'The session key', exampleHosts: [], optional: false, set: true, setIn: 'demo', renewed: true, suggested: rule },
      ],
      missing: [],
    }))
    registerEndpoint('/api/agents/73/skills/demo/secrets/SESSION_KEY', {
      method: 'PUT',
      handler: async (event) => {
        const { readBody } = await import('h3')
        sent = await readBody(event) as Record<string, unknown>
        return { name: 'SESSION_KEY', hosts: ['api.example.com'], setAt: '2026-11-13T09:00:00Z', hasValue: true, capture: null, renewedAt: null }
      },
    })
    const wrapper = await mountSuspended(SkillSecretsPanel, { props: { agentId: 73, skillName: 'demo' } })
    await settle()

    await wrapper.find('button[aria-label="Replace SESSION_KEY"]').trigger('click')
    expect(['secret-capture-from', 'secret-capture-field', 'secret-capture-credential', 'secret-capture-credential-in']
      .map(name => inputValue(wrapper, name))).toEqual(['header', 'X-Session', 'SESSION_KEY', 'basic'])
    expect(wrapper.find('input[name="secret-capture-credential-field"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="secret-form-rule-caution"]').exists()).toBe(false)

    await wrapper.find('input[name="secret-renew"]').setValue(false)
    expect(wrapper.find('[data-testid="secret-form-rule"]').exists()).toBe(false)
    await wrapper.find('[data-testid="secret-form"]').trigger('submit')
    await vi.waitFor(() => expect(wrapper.emitted('changed')).toHaveLength(1))
    expect(sent).toEqual({ hosts: ['api.example.com'], capture: null })
  })

  it('Replace on a secret without a rule leaves Renewed unticked, though its line is renewed', async () => {
    let sent: Record<string, unknown> | null = null
    registerEndpoint('/api/agents/74/skills/demo/secrets', () => ({
      secrets: [{ name: 'SESSION_KEY', hosts: ['api.example.com'], setAt: '2026-11-13T08:00:00Z', hasValue: true, capture: null, renewedAt: null }],
      expected: [
        { name: 'SESSION_KEY', description: 'The session key', exampleHosts: [], optional: false, set: true, setIn: 'demo', renewed: true, suggested: rule },
      ],
      missing: [],
    }))
    registerEndpoint('/api/agents/74/skills/demo/secrets/SESSION_KEY', {
      method: 'PUT',
      handler: async (event) => {
        const { readBody } = await import('h3')
        sent = await readBody(event) as Record<string, unknown>
        return { name: 'SESSION_KEY', hosts: ['api.example.com'], setAt: '2026-11-13T09:00:00Z', hasValue: true, capture: null, renewedAt: null }
      },
    })
    const wrapper = await mountSuspended(SkillSecretsPanel, { props: { agentId: 74, skillName: 'demo' } })
    await settle()

    await wrapper.find('button[aria-label="Replace SESSION_KEY"]').trigger('click')
    expect((wrapper.find('input[name="secret-renew"]').element as HTMLInputElement).checked).toBe(false)
    expect(wrapper.find('[data-testid="secret-form-rule"]').exists()).toBe(false)
    await wrapper.find('[data-testid="secret-form"]').trigger('submit')
    await vi.waitFor(() => expect(wrapper.emitted('changed')).toHaveLength(1))
    expect(sent).toEqual({ hosts: ['api.example.com'] })
  })

  it('a renewed secret set in a switched-off skill is not marked as needed', async () => {
    registerEndpoint('/api/agents/75/skills/demo/secrets', () => ({
      secrets: [],
      expected: [
        { name: 'SESSION_KEY', description: '', exampleHosts: [], optional: false, set: false, setIn: 'other', renewed: true, suggested: null },
        { name: 'API_KEY', description: '', exampleHosts: [], optional: false, set: false, setIn: 'other', renewed: false, suggested: null },
      ],
      missing: ['API_KEY'],
    }))
    const wrapper = await mountSuspended(SkillSecretsPanel, { props: { agentId: 75, skillName: 'demo' } })
    await settle()

    const badge = (name: string) => wrapper.find(`[data-testid="declared-${name}"] span.border`)
    expect(badge('SESSION_KEY').text()).toBe('Set in other, which is switched off')
    expect(badge('SESSION_KEY').classes()).not.toContain('text-amber-800')
    expect(badge('API_KEY').classes()).toContain('text-amber-800')
  })

  it('shows a suggestion the server could not read as text, and fills nothing from it', async () => {
    registerEndpoint('/api/agents/76/skills/demo/secrets', () => ({
      secrets: [],
      expected: [
        { name: 'SESSION_KEY', description: '', exampleHosts: [], optional: false, set: false, setIn: null, renewed: true, suggested: null, unreadableSuggestion: 'POST /v1/sessions xml session' },
      ],
      missing: [],
    }))
    const wrapper = await mountSuspended(SkillSecretsPanel, { props: { agentId: 76, skillName: 'demo' } })
    await settle()

    await wrapper.find('button[aria-label="Add SESSION_KEY"]').trigger('click')
    expect((wrapper.find('input[name="secret-renew"]').element as HTMLInputElement).checked).toBe(true)
    expect(wrapper.find('[data-testid="secret-form-rule-unreadable"]').text().replace(/\s+/g, ' ')).toBe(
      'The skill\'s files suggest a rule JClaw cannot read: POST /v1/sessions xml session. Fill in the method, the path,'
      + ' the field and the secret its sign-in sends yourself.')
    expect((wrapper.find('input[name="secret-capture-path"]').element as HTMLInputElement).value).toBe('')
    expect(wrapper.find('[data-testid="secret-form-rule-caution"]').exists()).toBe(false)
  })

  it('shows a refused rule in the server\'s words and keeps the form open', async () => {
    registerEndpoint('/api/agents/77/skills/demo/secrets', () => ({ secrets: [], expected: [], missing: [] }))
    registerEndpoint('/api/agents/77/skills/demo/secrets/SESSION_KEY', {
      method: 'PUT',
      handler: (event) => {
        setResponseStatus(event, 400)
        return {
          type: 'error',
          code: 'invalid_request',
          message: '\'Set-Cookie\' is a cookie header: a rule cannot take a cookie, which the skill keeps in its own cookie jar.',
        }
      },
    })
    const wrapper = await mountSuspended(SkillSecretsPanel, { props: { agentId: 77, skillName: 'demo' } })
    await settle()

    await wrapper.find('[data-testid="secret-add"]').trigger('click')
    await wrapper.find('input[name="secret-name"]').setValue('SESSION_KEY')
    await wrapper.find('input[name="secret-hosts"]').setValue('api.example.com')
    await wrapper.find('input[name="secret-renew"]').setValue(true)
    await wrapper.find('input[name="secret-capture-method"]').setValue('POST')
    await wrapper.find('input[name="secret-capture-path"]').setValue('/v1/sessions')
    await wrapper.find('select[name="secret-capture-from"]').setValue('header')
    await wrapper.find('input[name="secret-capture-field"]').setValue('Set-Cookie')
    await wrapper.find('[data-testid="secret-form"]').trigger('submit')
    await settle()

    expect(wrapper.find('[data-testid="secret-form"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('\'Set-Cookie\' is a cookie header: a rule cannot take a cookie')
    expect(wrapper.emitted('changed')).toBeUndefined()
  })

  it('names a value another skill\'s sign-in has yet to fill as filled by it, with no Add the server would refuse', async () => {
    registerEndpoint('/api/agents/78/skills/demo/secrets', () => ({
      secrets: [],
      expected: [
        { name: 'SESSION_KEY', description: 'The session key', exampleHosts: [], optional: false, set: false, setIn: 'backoffice', filledBySignIn: true, renewed: false, suggested: null },
      ],
      missing: ['SESSION_KEY'],
    }))
    const wrapper = await mountSuspended(SkillSecretsPanel, { props: { agentId: 78, skillName: 'demo' } })
    await settle()

    const badge = wrapper.find('[data-testid="declared-SESSION_KEY"] span.border')
    expect(badge.text()).toBe('Filled by backoffice\'s sign-in')
    expect(badge.classes()).toContain('text-amber-800')
    expect(wrapper.find('button[aria-label="Add SESSION_KEY"]').exists()).toBe(false)
  })

  it('names whether the skill holding a declared secret is switched off, not offered, not installed, or yet to sign in', async () => {
    const row = (name: string, setIn: string, filledBySignIn: boolean, setInState: string | null) =>
      ({ name, description: '', exampleHosts: [], optional: false, set: false, setIn, filledBySignIn, setInState, renewed: false, suggested: null })
    registerEndpoint('/api/agents/84/skills/demo/secrets', () => ({
      secrets: [],
      expected: [
        row('A_PASS', 'nas', false, 'switchedOff'),
        row('B_PASS', 'nas', false, 'notOffered'),
        row('C_SESSION', 'backoffice', true, 'offered'),
        row('D_SESSION', 'backoffice', true, 'switchedOff'),
        row('E_SESSION', 'backoffice', true, 'notOffered'),
        { ...row('F_SESSION', 'demo', true, 'offered'), set: true },
        row('G_PASS', 'gone', false, 'notInstalled'),
      ],
      missing: ['A_PASS', 'B_PASS', 'C_SESSION', 'D_SESSION', 'E_SESSION', 'G_PASS'],
    }))
    const wrapper = await mountSuspended(SkillSecretsPanel, { props: { agentId: 84, skillName: 'demo' } })
    await settle()

    expect(['A_PASS', 'B_PASS', 'C_SESSION', 'D_SESSION', 'E_SESSION', 'F_SESSION', 'G_PASS']
      .map(name => wrapper.find(`[data-testid="declared-${name}"] span.border`).text())).toEqual([
      'Set in nas, which is switched off',
      'Set in nas, which is not offered right now',
      'Filled by backoffice\'s sign-in',
      'Filled by backoffice\'s sign-in, which is switched off',
      'Filled by backoffice\'s sign-in, which is not offered right now',
      'Renewed at sign-in, not set',
      'Set in gone, which is no longer installed',
    ])
    expect(wrapper.findAll('button').filter(b => (b.attributes('aria-label') ?? '').startsWith('Add '))).toEqual([])
  })

  it('says a declared renewed secret without a rule lets its sign-in\'s key reach commands, until a rule is saved', async () => {
    const note = 'No capture rule yet: until you save one, the key this skill\'s sign-in returns reaches its commands as'
      + ' plain text.'
    registerEndpoint('/api/agents/79/skills/demo/secrets', () => ({
      secrets: [
        { name: 'A_SESSION', hosts: ['api.example.com'], setAt: '2026-11-13T08:00:00Z', hasValue: true, capture: null, renewedAt: null },
        { name: 'B_SESSION', hosts: ['api.example.com'], setAt: '2026-11-13T08:00:00Z', hasValue: false, capture: rule, renewedAt: null },
        { name: 'C_KEY', hosts: ['api.example.com'], setAt: '2026-11-13T08:00:00Z', hasValue: true, capture: null, renewedAt: null },
      ],
      expected: [
        { name: 'A_SESSION', description: '', exampleHosts: [], optional: false, set: true, setIn: 'demo', renewed: true, suggested: rule },
        { name: 'B_SESSION', description: '', exampleHosts: [], optional: false, set: true, setIn: 'demo', renewed: true, suggested: rule },
        { name: 'C_KEY', description: '', exampleHosts: [], optional: false, set: true, setIn: 'demo', renewed: false, suggested: null },
        { name: 'D_SESSION', description: '', exampleHosts: [], optional: false, set: false, setIn: null, renewed: true, suggested: rule },
        { name: 'E_SESSION', description: '', exampleHosts: [], optional: false, set: true, setIn: 'other', renewed: true, suggested: rule },
      ],
      missing: [],
    }))
    const wrapper = await mountSuspended(SkillSecretsPanel, { props: { agentId: 79, skillName: 'demo' } })
    await settle()

    const noted = wrapper.findAll('[data-testid$="-no-rule"]').map(n => n.attributes('data-testid'))
    expect(noted).toEqual(['declared-D_SESSION-no-rule', 'secret-A_SESSION-no-rule'])
    expect(wrapper.find('[data-testid="secret-A_SESSION-no-rule"]').text().replace(/\s+/g, ' ')).toBe(note)
    expect(wrapper.find('[data-testid="declared-D_SESSION-no-rule"]').text().replace(/\s+/g, ' ')).toBe(note)
  })

  it('Replace on a renewed secret with no value yet does not say a value is kept', async () => {
    registerEndpoint('/api/agents/80/skills/demo/secrets', () => ({
      secrets: [
        { name: 'A_SESSION', hosts: ['api.example.com'], setAt: '2026-11-13T08:00:00Z', hasValue: false, capture: rule, renewedAt: null },
        { name: 'B_SESSION', hosts: ['api.example.com'], setAt: '2026-11-13T08:00:00Z', hasValue: true, capture: rule, renewedAt: null },
      ],
      expected: [],
      missing: [],
    }))
    const wrapper = await mountSuspended(SkillSecretsPanel, { props: { agentId: 80, skillName: 'demo' } })
    await settle()

    await wrapper.find('button[aria-label="Replace A_SESSION"]').trigger('click')
    expect(wrapper.find('input[name="secret-value"]').attributes('placeholder'))
      .toBe('Leave empty: JClaw takes it from the next sign-in')
    await wrapper.find('input[name="secret-renew"]').setValue(false)
    expect(wrapper.find('input[name="secret-value"]').attributes('placeholder')).toBe('')

    await wrapper.find('button[aria-label="Replace B_SESSION"]').trigger('click')
    expect(wrapper.find('input[name="secret-value"]').attributes('placeholder')).toBe('Leave empty to keep the current value')
  })
})
