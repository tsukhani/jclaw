import type { Page } from '@playwright/test'
import { test, expect, gotoPage, blockApiWrites } from './helpers'

/**
 * UAT — the Tasks page Trust action (JCLAW-1021).
 *
 * Read-only against the live schedule, like UAT-6: no task is created or changed. blockApiWrites
 * answers every write, and the task list is rewritten inside the browser so one real task reads as
 * having no recorded origin. Once the trust POST is recorded, the rewrite reports the operator
 * origin, standing in for the server having stored it.
 */

type TaskRow = { id: number, name: string, originChannel: string | null }

/** A task whose name is unique in the list, so its row toggle addresses exactly that task. */
async function uniquelyNamedTask(page: Page): Promise<TaskRow | undefined> {
  const res = await page.request.get('/api/tasks?excludePayloadType=reminder&limit=50')
  expect(res.ok(), await res.text()).toBe(true)
  const rows = await res.json() as TaskRow[]
  return rows.find(row => rows.filter(other => other.name === row.name).length === 1)
}

/** Answer GET /api/tasks with the real rows, `origin()` substituted for the chosen task's. */
async function stubOrigin(page: Page, taskId: number, origin: () => string | null) {
  await page.route(url => url.pathname === '/api/tasks', async (route) => {
    if (route.request().method() !== 'GET') return route.fallback()
    const response = await route.fetch()
    const rows = await response.json() as TaskRow[]
    for (const row of rows) {
      if (row.id === taskId) row.originChannel = origin()
    }
    return route.fulfill({ response, json: rows })
  })
}

async function expandTask(page: Page, task: TaskRow) {
  await gotoPage(page, '/tasks')
  await page.getByRole('button', { name: `Toggle details for ${task.name}`, exact: true }).click()
}

test.describe('UAT tasks trust', () => {
  test('an unrecorded origin offers Trust, and confirming it records the operator origin', async ({ page }) => {
    const task = await uniquelyNamedTask(page)
    test.skip(!task, 'the instance has no uniquely named task to stand in for an untrusted one')
    const blocked = await blockApiWrites(page)
    const trustPost = `POST /api/tasks/${task!.id}/trust`
    await stubOrigin(page, task!.id, () => (blocked().includes(trustPost) ? 'web' : null))

    await expandTask(page, task!)
    const pill = page.getByTestId('task-origin-pill')
    const trust = page.getByTestId('task-origin-trust')
    await expect(pill).toHaveText('unrecorded')
    await expect(trust).toBeVisible()

    await trust.click()
    const dialog = page.getByRole('dialog')
    await expect(dialog).toContainText('Trust this task?')
    await expect(dialog).toContainText(task!.name)
    await dialog.getByRole('button', { name: 'Trust' }).click()

    await expect.poll(blocked).toContain(trustPost)
    await expect(pill).toHaveText('web')
    await expect(trust).toBeHidden()
  })

  test('cancelling the confirmation sends nothing and leaves the origin unrecorded', async ({ page }) => {
    const task = await uniquelyNamedTask(page)
    test.skip(!task, 'the instance has no uniquely named task to stand in for an untrusted one')
    const blocked = await blockApiWrites(page)
    await stubOrigin(page, task!.id, () => null)

    await expandTask(page, task!)
    await page.getByTestId('task-origin-trust').click()
    const dialog = page.getByRole('dialog')
    await dialog.getByRole('button', { name: 'Cancel' }).click()

    await expect(dialog).toBeHidden()
    expect(blocked().filter(write => write.endsWith('/trust'))).toEqual([])
    await expect(page.getByTestId('task-origin-pill')).toHaveText('unrecorded')
  })

  test('a task with the operator origin offers no Trust', async ({ page }) => {
    const task = await uniquelyNamedTask(page)
    test.skip(!task, 'the instance has no uniquely named task to stand in for a trusted one')
    await blockApiWrites(page)
    await stubOrigin(page, task!.id, () => 'web')

    await expandTask(page, task!)
    await expect(page.getByTestId('task-origin-pill')).toHaveText('web')
    await expect(page.getByTestId('task-origin-trust')).toBeHidden()
  })
})
