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
 * Serial because the lifecycle steps share one fixture agent.
 */
test.describe.configure({ mode: 'serial' })

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
