/**
 * Lewis electron-dot drawings for the chat's ```lewis fence (JCLAW-1309), and the only module that touches
 * SmilesDrawer, which parses the SMILES and places the atoms. It loads on the first fence; rendering stays synchronous
 * because messages re-render wholesale through v-html, so the load bumps `lewisVersion` and every reader re-renders.
 */
import { ref } from 'vue'
import type SmilesDrawerNS from 'smiles-drawer'
import {
  KEKULE_FORM_REQUIRED, LewisError, explicitHydrogenSmiles, hydrogenCounts, lewisElectrons, molecularFormula, type Molecule,
} from './electrons'
import { lewisSvg, type DrawnAtom, type DrawnBond } from './svg'

/** The fields read off SmilesDrawer's internal layout graph; test/lewis-electrons.test.ts pins them. */
export interface LayoutGraph {
  vertices: {
    value: {
      element: string
      bracket: { hcount: number | null, charge: number | null, chirality: string | null } | null
      isPartOfAromaticRing: boolean
    }
    position: { x: number, y: number }
  }[]
  edges: { sourceId: number, targetId: number, weight: number }[]
}

export interface LewisDrawing {
  atoms: DrawnAtom[]
  bonds: DrawnBond[]
  ariaLabel: string
}

export type LewisResult = { svg: string } | { error: string }

// SmilesDrawer lays a bond out 30 units long; this draws it 48 px.
const PX_PER_UNIT = 1.6
const RESULT_CACHE_MAX = 200
// Layout runs synchronously on the main thread: 1000 atoms blocked it about 4.8 s and 4000 overflowed the stack.
const MAX_SMILES_LENGTH = 200
const MAX_ATOMS = 60

let smilesDrawer: typeof SmilesDrawerNS | null = null
let loading: Promise<void> | null = null
const results = new Map<string, LewisResult>()

export const lewisVersion = ref(0)

export function isLewisLoaded(): boolean {
  return smilesDrawer !== null
}

/** Imports SmilesDrawer once. A failed import is not retried, so its fences stay code blocks until a reload. */
export function ensureLewisLoaded(): Promise<void> {
  loading ??= import('smiles-drawer').then(
    (module) => {
      smilesDrawer = module.default
      lewisVersion.value++
    },
    (err: unknown) => console.warn('[lewis] SmilesDrawer failed to load', err),
  )
  return loading
}

function layout(lib: typeof SmilesDrawerNS, smiles: string): { molecule: Molecule, graph: LayoutGraph } {
  let tree
  try {
    tree = lib.Parser.parse(smiles)
  }
  catch (err) {
    throw new LewisError(`Invalid SMILES: ${err instanceof Error ? err.message : String(err)}`)
  }
  const drawer = new lib.SvgDrawer({})
  drawer.draw(tree, null)
  const graph = drawer.preprocessor.graph as unknown as LayoutGraph
  // An aromatic ':' bond comes back with no weight at all.
  if (graph.edges.some(e => !Number.isFinite(e.weight))) throw new LewisError(KEKULE_FORM_REQUIRED)
  const molecule: Molecule = {
    atoms: graph.vertices.map(({ value }) => ({
      element: value.element,
      charge: value.bracket?.charge ?? 0,
      bracket: value.bracket !== null,
      // A chiral bracket atom's hydrogens are already vertices of the graph.
      hcount: value.bracket?.chirality ? 0 : value.bracket?.hcount ?? 0,
      // SmilesDrawer flags only one-letter aromatic symbols; a two-letter one such as [se] keeps its lowercase.
      aromatic: value.isPartOfAromaticRing || /^[a-z]/.test(value.element),
    })),
    // Weight 0 is the '.' between disconnected components, not a bond.
    bonds: graph.edges.filter(e => e.weight > 0).map(e => ({ a: e.sourceId, b: e.targetId, order: e.weight })),
  }
  return { molecule, graph }
}

function loadedLibrary(): typeof SmilesDrawerNS {
  if (!smilesDrawer) throw new Error('Lewis drawing needs ensureLewisLoaded() to resolve first')
  return smilesDrawer
}

/** Throws a LewisError when the SMILES does not parse or describes no drawable Lewis structure. */
export function lewisDrawing(smiles: string): LewisDrawing {
  return drawingWith(loadedLibrary(), smiles)
}

function drawingWith(lib: typeof SmilesDrawerNS, smiles: string): LewisDrawing {
  if (smiles.length > MAX_SMILES_LENGTH) {
    throw new LewisError(`Too large to draw as a Lewis structure: the SMILES is ${smiles.length} characters, over the limit of ${MAX_SMILES_LENGTH}.`)
  }
  let laid = layout(lib, smiles)
  const hydrogens = hydrogenCounts(laid.molecule)
  const atomCount = laid.molecule.atoms.length + hydrogens.reduce((sum, h) => sum + h, 0)
  if (atomCount > MAX_ATOMS) {
    throw new LewisError(`Too large to draw as a Lewis structure: ${atomCount} atoms counting hydrogens, over the limit of ${MAX_ATOMS}.`)
  }
  // Refuses an impossible count before paying for a second layout.
  lewisElectrons(laid.molecule)
  if (hydrogens.some(h => h > 0)) {
    laid = layout(lib, explicitHydrogenSmiles(laid.molecule))
  }
  const { molecule, graph } = laid
  const electrons = lewisElectrons(molecule)
  return {
    atoms: molecule.atoms.map((atom, i) => ({
      x: graph.vertices[i]!.position.x * PX_PER_UNIT,
      y: graph.vertices[i]!.position.y * PX_PER_UNIT,
      label: atom.element,
      charge: atom.charge,
      ...electrons[i]!,
    })),
    bonds: molecule.bonds,
    ariaLabel: `Lewis structure of ${molecularFormula(molecule)}, SMILES ${smiles}`,
  }
}

/** Memoized by SMILES. Call only once isLewisLoaded() is true. */
export function renderLewis(smiles: string): LewisResult {
  const lib = loadedLibrary()
  const cached = results.get(smiles)
  if (cached) return cached
  let result: LewisResult
  try {
    const drawing = drawingWith(lib, smiles)
    result = { svg: lewisSvg(drawing.atoms, drawing.bonds, drawing.ariaLabel) }
  }
  catch (err) {
    // A throw here would abort the whole message's markdown render, so a layout crash becomes a message too.
    if (err instanceof LewisError) {
      result = { error: err.message }
    }
    else {
      console.warn('[lewis] layout failed for', smiles, err)
      result = { error: `Could not lay out this structure: ${String(err)}` }
    }
  }
  if (results.size >= RESULT_CACHE_MAX) results.delete(results.keys().next().value!)
  results.set(smiles, result)
  return result
}
