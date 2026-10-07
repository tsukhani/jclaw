import type { APIRequestContext, Page } from '@playwright/test'
import { test, expect, gotoPage, uniqueName, borrowModelConfig, E2E_PREFIX } from './helpers'

/**
 * UAT-4 — Agent lifecycle.
 *
 * The create/update/delete path runs through the API rather than the agent
 * editor UI: /agents is a ~2,900-line page whose form is a poor proxy for
 * "can the operator own an agent", and driving it would couple this spec to
 * every field re-layout. The UI half asserts the created agent surfaces in
 * the list, which is the operator-visible outcome that matters.
 *
 * The agent-type steps are the exception and drive the editor's one control:
 * the confirmation before a switch to Service exists nowhere else. They live
 * here rather than in a spec of their own because the last test fails on any
 * prefixed agent, and a second spec's fixture would be alive while it runs.
 *
 * Serial because the lifecycle steps share one fixture agent.
 */
test.describe.configure({ mode: 'serial' })

/** The two files only a personal agent's workspace holds. */
const OWNER_FILES = ['USER.md', 'BOOTSTRAP.md']
const SHARED_FILES = ['SOUL.md', 'IDENTITY.md', 'AGENT.md']

async function workspaceHas(request: APIRequestContext, id: number, file: string) {
  return (await request.get(`/api/agents/${id}/workspace/${file}`)).status() === 200
}

function ownerFiles(request: APIRequestContext, id: number) {
  return Promise.all(OWNER_FILES.map(file => workspaceHas(request, id, file)))
}

async function isServiceAgent(request: APIRequestContext, id: number) {
  return (await (await request.get(`/api/agents/${id}`)).json()).serviceAgent
}

function kindRadio(page: Page, kind: 'Personal' | 'Service') {
  return page.getByTestId('agent-kind').getByRole('radio', { name: new RegExp(`^${kind}`) })
}

/** A workspace editor tab. The tree's controls are named "Download USER.md" and the like, never the bare name. */
function fileTab(page: Page, file: string) {
  return page.getByRole('button', { name: file, exact: true })
}

/** The agent form's save button, which carries this title only while the form is dirty. */
function saveAgent(page: Page) {
  return page.getByTitle('Save', { exact: true })
}

test.describe('UAT-4 agent lifecycle', () => {
  let agentId: number | null = null
  let agentName: string

  test.afterAll(async ({ playwright }) => {
    // Belt-and-braces: if an assertion aborted the delete step, the fixture
    // would otherwise persist into the operator's real agent list.
    if (agentId === null) return
    const ctx = await playwright.request.newContext({
      baseURL: process.env.JCLAW_E2E_BASE_URL || 'http://localhost:3000',
      storageState: './tests/e2e/.auth/admin.json',
    })
    await ctx.delete(`/api/agents/${agentId}`)
    await ctx.dispose()
  })

  test('agents page lists the built-in main agent', async ({ page }) => {
    await gotoPage(page, '/agents')
    await expect(page.getByRole('heading', { name: 'Main Agent' })).toBeVisible()
    await expect(page.getByRole('button', { name: 'New Agent' })).toBeVisible()
  })

  test('the new-agent form offers both agent types and starts on Personal', async ({ page }) => {
    await gotoPage(page, '/agents')
    await page.getByRole('button', { name: 'New Agent' }).click()
    await expect(page.getByTestId('agent-kind').getByRole('radio')).toHaveCount(2)
    await expect(kindRadio(page, 'Personal')).toBeChecked()
    await expect(kindRadio(page, 'Service')).not.toBeChecked()
  })

  test('create an agent', async ({ request }) => {
    const { modelProvider, modelId } = await borrowModelConfig(request)
    agentName = uniqueName('agent')

    const res = await request.post('/api/agents', {
      data: { name: agentName, modelProvider, modelId, description: 'Created by the JClaw UAT suite.' },
    })
    expect(res.status(), await res.text()).toBe(200)

    const created = await res.json()
    expect(created.name).toBe(agentName)
    agentId = created.id
    expect(agentId).toBeTruthy()
  })

  test('reserved names are rejected with 409', async ({ request }) => {
    const { modelProvider, modelId } = await borrowModelConfig(request)
    const res = await request.post('/api/agents', { data: { name: 'main', modelProvider, modelId } })
    expect(res.status()).toBe(409)
  })

  test('duplicate name is rejected with 409, not a 500', async ({ request }) => {
    // Agent.name is unique at the DB level; without the pre-check this is an
    // unhandled constraint violation surfacing as an opaque error toast.
    const { modelProvider, modelId } = await borrowModelConfig(request)
    const res = await request.post('/api/agents', { data: { name: agentName, modelProvider, modelId } })
    expect(res.status()).toBe(409)
  })

  test('read the agent back by id', async ({ request }) => {
    const res = await request.get(`/api/agents/${agentId}`)
    expect(res.status()).toBe(200)
    expect((await res.json()).name).toBe(agentName)
  })

  test('created agent appears in the agents page', async ({ page }) => {
    await gotoPage(page, '/agents')
    await expect(page.getByText(agentName, { exact: false }).first()).toBeVisible({ timeout: 15_000 })
  })

  test('agent prompt breakdown and tools are addressable', async ({ request }) => {
    // These back the editor's Prompt and Tools tabs; a 500 here blanks them.
    // prompt-breakdown and prompt-text require channelType — the assembled
    // prompt differs per channel, so there is no meaningful default.
    for (const suffix of ['tools', 'skills', 'prompt-breakdown?channelType=web', 'prompt-text?channelType=web']) {
      const res = await request.get(`/api/agents/${agentId}/${suffix}`)
      expect(res.status(), `/api/agents/{id}/${suffix}`).toBe(200)
    }
  })

  test('update the agent description', async ({ request }) => {
    const { modelProvider, modelId } = await borrowModelConfig(request)
    const res = await request.put(`/api/agents/${agentId}`, {
      data: { name: agentName, modelProvider, modelId, description: 'Updated by UAT.' },
    })
    expect(res.status(), await res.text()).toBe(200)
    expect((await res.json()).description).toBe('Updated by UAT.')
  })

  test('a fallback on the agent\'s own provider is rejected with 400', async ({ request }) => {
    const { modelProvider, modelId } = await borrowModelConfig(request)
    const res = await request.put(`/api/agents/${agentId}`, {
      data: { name: agentName, modelProvider, modelId, fallbackProvider: modelProvider, fallbackModelId: modelId },
    })
    expect(res.status(), await res.text()).toBe(400)
  })

  test('a fallback provider and model round-trip, and the editor shows them', async ({ page, request }) => {
    // JCLAW-1190: the pair is optional, but when set it must name a second
    // configured provider with a registered model. Skip rather than fail on an
    // install with only one provider — that is a valid configuration, not drift.
    const { modelProvider, modelId } = await borrowModelConfig(request)
    const providers = await (await request.get('/api/providers')).json() as Array<{ name: string }>
    let fallback: { provider: string, modelId: string } | null = null
    for (const p of providers.filter(p => p.name !== modelProvider)) {
      const { models } = await (await request.get(`/api/providers/${p.name}/models`)).json() as { models: Array<{ id: string }> }
      if (models[0]) {
        fallback = { provider: p.name, modelId: models[0].id }
        break
      }
    }
    test.skip(fallback === null, 'no second provider with a registered model on this install')

    const res = await request.put(`/api/agents/${agentId}`, {
      data: { name: agentName, modelProvider, modelId, fallbackProvider: fallback!.provider, fallbackModelId: fallback!.modelId },
    })
    expect(res.status(), await res.text()).toBe(200)
    const saved = await res.json()
    expect(saved.fallbackProvider).toBe(fallback!.provider)
    expect(saved.fallbackModelId).toBe(fallback!.modelId)

    await gotoPage(page, `/agents/${agentName}`)
    await expect(page.getByLabel('Fallback Provider')).toHaveValue(fallback!.provider)
    await expect(page.getByLabel('Fallback Model')).toHaveValue(fallback!.modelId)
  })

  test('clearing either half of the fallback clears both', async ({ request }) => {
    const { modelProvider, modelId } = await borrowModelConfig(request)
    const res = await request.put(`/api/agents/${agentId}`, {
      data: { name: agentName, modelProvider, modelId, fallbackModelId: null },
    })
    expect(res.status(), await res.text()).toBe(200)
    const saved = await res.json()
    expect(saved.fallbackProvider).toBeNull()
    expect(saved.fallbackModelId).toBeNull()
  })

  test('an agent created without a type is personal, with both owner files and their tabs', async ({ page, request }) => {
    expect(await isServiceAgent(request, agentId!)).toBe(false)
    expect(await ownerFiles(request, agentId!)).toEqual([true, true])

    await gotoPage(page, `/agents/${agentName}`)
    await expect(kindRadio(page, 'Personal')).toBeChecked()
    for (const file of OWNER_FILES) {
      await expect(fileTab(page, file)).toBeVisible()
      await expect(page.getByTestId('workspace-manager').getByTestId(`ws-row-${file}`)).toBeVisible()
    }
  })

  test('changing to Service asks first, and Cancel changes nothing', async ({ page, request }) => {
    // Counted on the page: a read of the agent straight after Cancel could run ahead of a save sent in error.
    const saves: string[] = []
    page.on('request', (req) => {
      if (req.method() === 'PUT') saves.push(new URL(req.url()).pathname)
    })
    await gotoPage(page, `/agents/${agentName}`)
    await expect(kindRadio(page, 'Personal')).toBeChecked()
    await kindRadio(page, 'Service').check()
    await saveAgent(page).click()

    const dialog = page.getByRole('dialog')
    await expect(dialog).toContainText('USER.md and BOOTSTRAP.md will be deleted')
    await expect(dialog).toContainText('The memories it already holds are kept')
    await dialog.getByRole('button', { name: 'Cancel' }).click()
    await expect(dialog).toHaveCount(0)

    expect(saves).toEqual([])
    expect(await isServiceAgent(request, agentId!)).toBe(false)
    expect(await ownerFiles(request, agentId!)).toEqual([true, true])
  })

  test('confirming the change deletes both owner files and their tabs, and keeps the rest', async ({ page, request }) => {
    await gotoPage(page, `/agents/${agentName}`)
    await expect(kindRadio(page, 'Personal')).toBeChecked()
    await kindRadio(page, 'Service').check()
    await saveAgent(page).click()
    await page.getByRole('dialog').getByRole('button', { name: 'Change and delete' }).click()

    for (const file of OWNER_FILES) await expect(fileTab(page, file)).toHaveCount(0)
    await expect(fileTab(page, 'AGENT.md')).toBeVisible()
    // The files follow the type only once the save's transaction has committed.
    await expect.poll(() => ownerFiles(request, agentId!)).toEqual([false, false])
    expect(await isServiceAgent(request, agentId!)).toBe(true)
    for (const file of SHARED_FILES) expect(await workspaceHas(request, agentId!, file), file).toBe(true)

    // A reload reads the type back from the server rather than from the form that set it.
    await gotoPage(page, `/agents/${agentName}`)
    await expect(kindRadio(page, 'Service')).toBeChecked()
    const tree = page.getByTestId('workspace-manager')
    await expect(tree.getByTestId('ws-row-AGENT.md')).toBeVisible()
    for (const file of OWNER_FILES) await expect(tree.getByTestId(`ws-row-${file}`)).toHaveCount(0)
  })

  test('changing back to Personal asks nothing and restores both owner files', async ({ page, request }) => {
    await gotoPage(page, `/agents/${agentName}`)
    await expect(kindRadio(page, 'Service')).toBeChecked()
    await kindRadio(page, 'Personal').check()
    await saveAgent(page).click()

    // The tabs return only when the save has gone through, which a confirmation would have held up.
    for (const file of OWNER_FILES) await expect(fileTab(page, file)).toBeVisible()
    await expect(page.getByRole('dialog')).toHaveCount(0)
    await expect.poll(() => ownerFiles(request, agentId!)).toEqual([true, true])
    expect(await isServiceAgent(request, agentId!)).toBe(false)
  })

  test('an agent created as a service agent never gets the owner files', async ({ request }) => {
    const { modelProvider, modelId } = await borrowModelConfig(request)
    const res = await request.post('/api/agents', {
      data: {
        name: uniqueName('service'), modelProvider, modelId, serviceAgent: true,
        description: 'Service-agent fixture for the JClaw UAT suite.',
      },
    })
    expect(res.status(), await res.text()).toBe(200)
    const created = await res.json()
    try {
      expect(created.serviceAgent).toBe(true)
      expect(await ownerFiles(request, created.id)).toEqual([false, false])
      for (const file of SHARED_FILES) expect(await workspaceHas(request, created.id, file), file).toBe(true)
    }
    finally {
      await request.delete(`/api/agents/${created.id}`)
    }
  })

  test('delete the agent and confirm it is gone', async ({ request }) => {
    const del = await request.delete(`/api/agents/${agentId}`)
    expect(del.ok(), await del.text()).toBeTruthy()

    const after = await request.get(`/api/agents/${agentId}`)
    expect(after.status()).toBe(404)
    agentId = null
  })

  test('no UAT fixture agents are left behind', async ({ request }) => {
    const agents = await (await request.get('/api/agents')).json() as Array<{ name: string }>
    const leaked = agents.filter(a => a.name.startsWith(E2E_PREFIX))
    expect(leaked.map(a => a.name), 'UAT fixtures must not survive the run').toEqual([])
  })
})
