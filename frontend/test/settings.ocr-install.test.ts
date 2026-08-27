import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { clearNuxtData } from '#app'
import Settings from '~/pages/settings.vue'

/**
 * JCLAW-1108 — the "Install for me" button in Settings → OCR.
 *
 * The button cannot succeed everywhere: Linux package managers need root and
 * JClaw deliberately never invokes sudo from a process with no TTY. So the case
 * that matters most is the one where it is NOT offered — the operator must still
 * be handed the command rather than a button that could only fail.
 */
function provider(available: boolean) {
  return {
    name: 'tesseract',
    displayName: 'Tesseract OCR',
    available,
    version: available ? '5.5.0' : null,
    reason: available ? null : 'tesseract not found on PATH',
    enabled: true,
    configKey: 'ocr.tesseract.enabled',
    description: 'Apache Tika TesseractOCRParser.',
    installHint: 'Install tesseract on the host.',
  }
}

function baseEndpoints(opts: {
  available?: boolean
  plan?: { manager: string, command: string, runnable: boolean, reason: string | null }
  captureInstall?: () => void
} = {}) {
  registerEndpoint('/api/agents', () => [])
  registerEndpoint('/api/channels', () => [])
  registerEndpoint('/api/config', () => ({ entries: [] }))
  registerEndpoint('/api/providers', () => [])
  registerEndpoint('/api/ocr/status', () => ({ providers: [provider(opts.available ?? false)] }))
  if (opts.plan) registerEndpoint('/api/ocr/tesseract/install-plan', () => opts.plan)
  registerEndpoint('/api/ocr/tesseract/install', {
    method: 'POST',
    handler: () => {
      opts.captureInstall?.()
      return { status: 'RUNNING', manager: 'brew', command: 'brew install tesseract', output: null, hint: null }
    },
  })
  registerEndpoint('/api/ocr/tesseract/install-state', () => ({
    status: 'SUCCEEDED', manager: 'brew', command: 'brew install tesseract',
    output: 'done', hint: 'Restart JClaw to activate OCR',
  }))
}

async function openOcr() {
  const component = await mountSuspended(Settings)
  ;(component.vm as unknown as { activeSectionId: string }).activeSectionId = 'ocr'
  // The panel mounts on the section swap, then its onMounted $fetch for the install
  // plan resolves a tick later — the extra flushes settle that second request.
  await flushPromises()
  await flushPromises()
  await flushPromises()
  await flushPromises()
  return component
}

const RUNNABLE = { manager: 'brew', command: 'brew install tesseract', runnable: true, reason: null }
const NOT_RUNNABLE = {
  manager: 'apt-get',
  command: 'apt-get install -y tesseract-ocr',
  runnable: false,
  reason: 'This needs root and JClaw runs as your user. Run it yourself:  sudo apt-get install -y tesseract-ocr',
}

describe('Settings → OCR install button', () => {
  beforeEach(() => clearNuxtData())

  it('offers the button when the host can run the install itself', async () => {
    baseEndpoints({ plan: RUNNABLE })
    const c = await openOcr()
    expect(c.text()).toContain('brew install tesseract')
    expect(c.findAll('button').some(b => b.text().includes('Install for me'))).toBe(true)
  })

  it('shows the command but NO button when the install needs root', async () => {
    baseEndpoints({ plan: NOT_RUNNABLE })
    const c = await openOcr()
    // The command is the deliverable here — a button could only fail.
    expect(c.text()).toContain('apt-get install -y tesseract-ocr')
    expect(c.text()).toContain('sudo apt-get install')
    expect(c.findAll('button').some(b => b.text().includes('Install for me'))).toBe(false)
  })

  it('POSTs when the button is pressed', async () => {
    const captureInstall = vi.fn()
    baseEndpoints({ plan: RUNNABLE, captureInstall })
    const c = await openOcr()
    const btn = c.findAll('button').find(b => b.text().includes('Install for me'))
    await btn!.trigger('click')
    await flushPromises()
    expect(captureInstall).toHaveBeenCalled()
  })

  it('offers nothing when tesseract is already available', async () => {
    baseEndpoints({ available: true, plan: RUNNABLE })
    const c = await openOcr()
    expect(c.findAll('button').some(b => b.text().includes('Install for me'))).toBe(false)
  })
})
