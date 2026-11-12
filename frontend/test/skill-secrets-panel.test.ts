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
})
