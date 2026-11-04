import { mkdtempSync, readFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { describe, it, expect } from 'vitest'
import { bundledPackages, packageOf } from '../tools/bundled-packages'

/**
 * JCLAW-1593 — the client build records which npm packages reach the browser, and the release
 * build's THIRD-PARTY-NOTICES.md lists exactly those. A path read wrongly drops a package from the
 * notices or credits the wrong one.
 */
const ROOT = '/repo/frontend/node_modules'

describe('packageOf', () => {
  it('names the package folder of a module in pnpm\'s layout', () => {
    expect(packageOf(`${ROOT}/.pnpm/katex@0.19.1/node_modules/katex/dist/katex.mjs`))
      .toEqual({ root: `${ROOT}/.pnpm/katex@0.19.1/node_modules/katex` })
  })

  it('keeps both parts of a scoped package name', () => {
    expect(packageOf(`${ROOT}/.pnpm/@vue+runtime-core@3.6.0/node_modules/@vue/runtime-core/dist/runtime-core.esm-bundler.js`))
      .toEqual({ root: `${ROOT}/.pnpm/@vue+runtime-core@3.6.0/node_modules/@vue/runtime-core` })
  })

  it('takes the innermost package when one is nested in another', () => {
    expect(packageOf(`${ROOT}/a/node_modules/b/index.js`)).toEqual({ root: `${ROOT}/a/node_modules/b` })
  })

  it('drops the query a module id carries', () => {
    expect(packageOf(`${ROOT}/driver.js/dist/driver.css?inline`)).toEqual({ root: `${ROOT}/driver.js` })
  })

  it('names the package behind a bundler helper module', () => {
    expect(packageOf('\0vite/preload-helper.js')).toEqual({ name: 'vite' })
    expect(packageOf('\0rolldown/runtime.js')).toEqual({ name: 'rolldown' })
    expect(packageOf('\0plugin-vue:export-helper')).toEqual({ name: '@vitejs/plugin-vue' })
  })

  it('ignores an unknown virtual module, the app\'s own source and a bare pnpm store folder', () => {
    expect(packageOf('\0virtual:nuxt-app')).toBeUndefined()
    expect(packageOf('/repo/frontend/pages/chat.vue')).toBeUndefined()
    expect(packageOf(`${ROOT}/.pnpm/katex@0.19.1`)).toBeUndefined()
    expect(packageOf(`${ROOT}/katex`)).toBeUndefined()
  })
})

describe('bundledPackages', () => {
  function runPlugin(bundle: Parameters<ReturnType<typeof bundledPackages>['generateBundle']>[1], environment?: string) {
    const outFile = join(mkdtempSync(join(tmpdir(), 'bundled-')), 'out.json')
    const plugin = bundledPackages(outFile)
    plugin.configResolved({ root: '/repo/frontend' })
    plugin.generateBundle.call({ environment: environment ? { name: environment } : undefined }, {}, bundle)
    plugin.closeBundle()
    return outFile
  }

  it('records chunk modules, asset sources relative to Vite\'s root, and helpers', () => {
    const outFile = runPlugin({
      'entry.js': { type: 'chunk', moduleIds: [`${ROOT}/marked/lib/marked.esm.js`, '\0vite/preload-helper.js', '/repo/frontend/app.vue'] },
      'inter.woff2': { type: 'asset', originalFileNames: ['node_modules/@fontsource-variable/inter/files/inter-latin.woff2'] },
    }, 'client')
    expect(JSON.parse(readFileSync(outFile, 'utf8'))).toEqual({
      roots: ['/repo/frontend/node_modules/@fontsource-variable/inter', `${ROOT}/marked`],
      helpers: ['vite'],
    })
  })

  it('sorts by code unit, so every machine and locale writes the same file', () => {
    const outFile = runPlugin({
      'entry.js': {
        type: 'chunk',
        moduleIds: [`${ROOT}/a/index.js`, `${ROOT}/JSONStream/index.js`, `${ROOT}/@scope/x/index.js`, '\0vite/preload-helper.js', '\0plugin-vue:export-helper', '\0rolldown/runtime.js'],
      },
    }, 'client')
    expect(JSON.parse(readFileSync(outFile, 'utf8'))).toEqual({
      roots: [`${ROOT}/@scope/x`, `${ROOT}/JSONStream`, `${ROOT}/a`],
      helpers: ['@vitejs/plugin-vue', 'rolldown', 'vite'],
    })
  })

  it('records nothing from a server build', () => {
    const outFile = runPlugin({ 'server.js': { type: 'chunk', moduleIds: [`${ROOT}/h3/dist/index.mjs`] } }, 'ssr')
    expect(() => readFileSync(outFile, 'utf8')).toThrow()
  })
})
