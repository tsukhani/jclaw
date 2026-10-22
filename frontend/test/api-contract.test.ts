import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import openapiTS, { astToString, COMMENT_HEADER } from 'openapi-typescript'
import { describe, expect, it } from 'vitest'

// Vitest runs from frontend/; under the nuxt environment import.meta.url is not a file URL.
const TYPES = join(process.cwd(), 'types')

// types/api-contract.check.ts checks the hand-written types against these; stale ones would check nothing new.
describe('API contract types (JF-17)', () => {
  it('are generated from the current snapshot (pnpm gen:api-contract)', async () => {
    const ast = await openapiTS(JSON.parse(readFileSync(join(TYPES, 'api-contract.openapi.json'), 'utf8')))
    expect(COMMENT_HEADER + astToString(ast)).toBe(readFileSync(join(TYPES, 'api-contract.generated.ts'), 'utf8'))
  })
})
