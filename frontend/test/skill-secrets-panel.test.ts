import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
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
})
