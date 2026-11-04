import { mkdirSync, writeFileSync } from 'node:fs'
import { dirname, isAbsolute, join, resolve } from 'node:path'
/** A bundle output, as much of Rolldown's chunk and asset shapes as this plugin reads. */
interface BundleOutput {
  type: 'chunk' | 'asset'
  moduleIds?: readonly string[]
  originalFileNames?: readonly string[]
}

/** The Vite plugin hooks this plugin implements. Declared here because `vite` reaches the app only through Nuxt. */
export interface BundledPackagesPlugin {
  name: string
  apply: 'build'
  configResolved(config: { root: string }): void
  generateBundle(this: { environment?: { name: string } }, options: unknown, bundle: Record<string, BundleOutput>): void
  closeBundle(): void
}

/** Where the client build records its packages for gradle/third-party-notices.gradle.kts. */
export const BUNDLED_PACKAGES_FILE = 'node_modules/.cache/jclaw/bundled-packages.json'

// Helpers the bundler injects as virtual modules, so no node_modules path names their package.
const VIRTUAL_HELPERS: Array<[RegExp, string]> = [
  [/^vite\//, 'vite'],
  [/^rolldown\//, 'rolldown'],
  [/^plugin-vue:/, '@vitejs/plugin-vue'],
]

export interface PackageRef {
  root?: string
  name?: string
}

/** The npm package a module id or asset source path belongs to: its folder, or for a bundler helper its name. */
export function packageOf(id: string): PackageRef | undefined {
  const virtual = id.startsWith('\0')
  const path = id.replace(/^\0/, '').split('?')[0]!.replaceAll('\\', '/')
  if (virtual) {
    const helper = VIRTUAL_HELPERS.find(([prefix]) => prefix.test(path))
    return helper ? { name: helper[1] } : undefined
  }
  const at = path.lastIndexOf('/node_modules/')
  if (at < 0) return undefined
  const rest = path.slice(at + '/node_modules/'.length).split('/')
  const nameParts = rest[0]?.startsWith('@') ? 2 : 1
  if (rest.length <= nameParts || rest[0] === '.pnpm') return undefined
  return { root: path.slice(0, at) + '/node_modules/' + rest.slice(0, nameParts).join('/') }
}

/**
 * Records the npm package behind every module and asset the client build emits. pnpm's dependency
 * tree would also list Nuxt's build tooling, which never reaches the browser, and the notices must
 * cover what the shipped JavaScript, CSS and fonts actually contain.
 */
export function bundledPackages(outFile = join(process.cwd(), BUNDLED_PACKAGES_FILE)): BundledPackagesPlugin {
  const roots = new Set<string>()
  const names = new Set<string>()
  let viteRoot = process.cwd()
  return {
    name: 'jclaw:bundled-packages',
    apply: 'build',
    configResolved(config) {
      viteRoot = config.root
    },
    generateBundle(_options, bundle) {
      if (this.environment && this.environment.name !== 'client') return
      for (const output of Object.values(bundle)) {
        const ids = (output.type === 'chunk' ? output.moduleIds : output.originalFileNames) ?? []
        for (const id of ids) {
          // An asset's source path is relative to Vite's root; a module id is already absolute or virtual.
          const ref = packageOf(id.startsWith('\0') || isAbsolute(id) ? id : resolve(viteRoot, id))
          if (ref?.root) roots.add(ref.root)
          if (ref?.name) names.add(ref.name)
        }
      }
    },
    closeBundle() {
      if (roots.size === 0) return
      mkdirSync(dirname(outFile), { recursive: true })
      writeFileSync(outFile, JSON.stringify({ roots: [...roots].sort(), helpers: [...names].sort() }, null, 2) + '\n')
    },
  }
}
