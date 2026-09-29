import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { readBody } from 'h3'
import { clearNuxtData } from '#app'
import SecretField from '~/components/SecretField.vue'
import Settings from '~/pages/settings.vue'

// JCLAW-1330: every saved secret shows as dots and a pencil, and its editor never holds the stored value.
describe('SecretField', () => {
  function row(props: { saved: boolean, [prop: string]: unknown }) {
    return mount(SecretField, { props: { label: 'API key', modelValue: '', ...props } })
  }

  it('shows dots for a saved secret and (not set) for none, never a value', () => {
    expect(row({ saved: true }).text()).toBe('••••••••')
    expect(row({ saved: false }).text()).toBe('(not set)')
  })

  it('opens an empty editor even when the model still holds something, such as a mask', async () => {
    const field = row({ saved: true, modelValue: 'sk-a****' })
    await field.find('button[aria-label="Edit API key"]').trigger('click')
    expect(field.emitted('update:modelValue')?.at(-1)).toEqual([''])
    expect(field.emitted('edit')).toHaveLength(1)
  })

  it('a blank save cancels instead of saving, and a typed one saves', async () => {
    const blank = row({ saved: true, editing: true, modelValue: '  ' })
    await blank.find('button[title="Save"]').trigger('click')
    expect(blank.emitted('save')).toBeUndefined()
    expect(blank.emitted('cancel')).toHaveLength(1)

    const typed = row({ saved: true, editing: true, modelValue: 'new-key' })
    await typed.find('button[title="Save"]').trigger('click')
    expect(typed.emitted('save')).toHaveLength(1)
  })

  it('offers Remove only for a saved secret that can be removed', () => {
    expect(row({ saved: true, editing: true, removable: true }).find('button[title="Remove"]').exists()).toBe(true)
    expect(row({ saved: false, editing: true, removable: true }).find('button[title="Remove"]').exists()).toBe(false)
    expect(row({ saved: true, editing: true }).find('button[title="Remove"]').exists()).toBe(false)
  })

  it('in a form, nothing saved is an input, and a saved secret is dots until the pencil', async () => {
    const unsaved = row({ form: true, saved: false, inputId: 'secret' })
    expect(unsaved.find('#secret').attributes('type')).toBe('password')
    expect(unsaved.find('button[title="Save"]').exists()).toBe(false)

    const saved = row({ form: true, saved: true, inputId: 'secret' })
    expect(saved.find('#secret').exists()).toBe(false)
    expect(saved.text()).toBe('••••••••')
    await saved.find('button[aria-label="Edit API key"]').trigger('click')
    expect(saved.find('#secret').exists()).toBe(true)
    await saved.find('button[aria-label="Keep the saved API key"]').trigger('click')
    expect(saved.find('#secret').exists()).toBe(false)
  })

  it('in a form, keeps a typed value visible when a saved secret appears, since Save would still send it', async () => {
    const field = row({ form: true, saved: false, inputId: 'secret', modelValue: 'typed' })
    await field.setProps({ saved: true })
    expect(field.find('#secret').exists()).toBe(true)
  })
})

describe('Settings — a saved key\'s editor never holds the key or its mask', () => {
  let posted: Array<{ key: string, value: string }>

  const ENTRIES = [
    { key: 'provider.openai.baseUrl', value: 'https://api.openai.com/v1' },
    { key: 'provider.openai.apiKey', value: 'sk-a****' },
    { key: 'provider.bfl.apiKey', value: 'bfl-****' },
    { key: 'imagegen.provider', value: 'bfl' },
    { key: 'search.exa.apiKey', value: 'exa-****' },
    { key: 'scanner.malwarebazaar.authKey', value: 'mb-a****' },
  ]

  beforeEach(() => {
    clearNuxtData()
    posted = []
    registerEndpoint('/api/agents', () => [])
    registerEndpoint('/api/channels', () => [])
    registerEndpoint('/api/providers', () => [])
    registerEndpoint('/api/ocr/status', () => ({ providers: [] }))
    registerEndpoint('/api/config', { method: 'GET', handler: () => ({ entries: ENTRIES.map(e => ({ ...e, updatedAt: '2026-09-29T00:00:00Z' })) }) })
    registerEndpoint('/api/config', {
      method: 'POST',
      handler: async (event) => {
        posted.push(await readBody(event))
        return { status: 'ok' }
      },
    })
  })

  async function mountSection(sectionId: string) {
    const component = await mountSuspended(Settings)
    ;(component.vm as unknown as { activeSectionId: string }).activeSectionId = sectionId
    await flushPromises()
    await flushPromises()
    return component
  }

  it.each([
    ['providers', 'provider.openai.apiKey', 'provider.openai.apiKey', 'sk-a'],
    ['search', 'search.exa.apiKey', 'Exa API key', 'exa-'],
    ['malware', 'scanner.malwarebazaar.authKey', 'MalwareBazaar (abuse.ch) authKey', 'mb-a'],
    ['image-generation', 'provider.bfl.apiKey', 'Black Forest Labs API key', 'bfl-'],
  ])('%s', async (sectionId, key, label, maskPrefix) => {
    const component = await mountSection(sectionId)
    expect(component.html()).not.toContain(maskPrefix)

    await component.find(`button[aria-label="Edit ${label}"]`).trigger('click')
    const input = component.find(`input[aria-label="${label}"]`)
    expect((input.element as HTMLInputElement).value).toBe('')
    await component.find(`button[aria-label="Save ${label}"]`).trigger('click')
    await flushPromises()
    expect(posted, 'a blank save keeps the stored key').toEqual([])
    expect(component.find(`input[aria-label="${label}"]`).exists()).toBe(false)

    await component.find(`button[aria-label="Edit ${label}"]`).trigger('click')
    await component.find(`input[aria-label="${label}"]`).setValue('replacement')
    await component.find(`button[aria-label="Save ${label}"]`).trigger('click')
    await vi.waitFor(() => expect(component.find(`input[aria-label="${label}"]`).exists()).toBe(false))
    expect(posted).toEqual([{ key, value: 'replacement' }])

    await component.find(`button[aria-label="Edit ${label}"]`).trigger('click')
    await component.find(`button[aria-label="Remove ${label}"]`).trigger('click')
    await flushPromises()
    expect(posted.at(-1)).toEqual({ key, value: '' })
  })
})
