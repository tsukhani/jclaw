import { beforeAll, describe, it, expect, vi } from 'vitest'
import SmilesDrawer from 'smiles-drawer'
import {
  explicitHydrogenSmiles, hydrogenCounts, lewisElectrons, molecularFormula,
  type Molecule, type MoleculeAtom,
} from '~/utils/lewis/electrons'
import { ensureLewisLoaded, lewisDrawing, renderLewis, type LayoutGraph, type LewisDrawing } from '~/utils/lewis'

const plain = (element: string, extra: Partial<MoleculeAtom> = {}): MoleculeAtom =>
  ({ element, charge: 0, bracket: false, hcount: 0, aromatic: false, ...extra })
const bracket = (element: string, charge = 0, hcount = 0): MoleculeAtom =>
  ({ element, charge, bracket: true, hcount, aromatic: false })

// Per element, sorted: label, charge, lone pairs and radicals, so layouts that differ only in order compare equal.
function summary(drawing: LewisDrawing): string[] {
  return drawing.atoms.map(a => `${a.label}${a.charge || ''}:${a.pairs}p${a.radicals}r`).sort()
}

function bondOrders(drawing: LewisDrawing): number[] {
  return drawing.bonds.map(b => b.order).sort()
}

describe('hydrogenCounts', () => {
  it('fills an unbracketed atom to its lowest normal valence at or above its bond sum', () => {
    // CCO, S(=O)(=O) with one more single bond, and pentavalent P
    const ethanol: Molecule = { atoms: [plain('C'), plain('C'), plain('O')], bonds: [{ a: 0, b: 1, order: 1 }, { a: 1, b: 2, order: 1 }] }
    expect(hydrogenCounts(ethanol)).toEqual([3, 2, 1])
    const sulfur: Molecule = {
      atoms: [plain('S'), plain('O'), plain('O'), plain('O')],
      bonds: [{ a: 0, b: 1, order: 2 }, { a: 0, b: 2, order: 2 }, { a: 0, b: 3, order: 1 }],
    }
    expect(hydrogenCounts(sulfur)[0]).toBe(1)
    const phosphorus: Molecule = { atoms: [plain('P'), plain('O')], bonds: [{ a: 0, b: 1, order: 2 }] }
    expect(hydrogenCounts(phosphorus)[0]).toBe(1)
  })

  it('takes a bracket atom at its word', () => {
    expect(hydrogenCounts({ atoms: [bracket('O', 0, 2)], bonds: [] })).toEqual([2])
    expect(hydrogenCounts({ atoms: [bracket('N')], bonds: [] })).toEqual([0])
  })
})

describe('lewisElectrons', () => {
  it('counts nonbonding electrons as valence minus charge minus bonds and hydrogens', () => {
    // [NH4+], [OH-] and [N]=O
    expect(lewisElectrons({ atoms: [bracket('N', 1, 4)], bonds: [] })).toEqual([{ pairs: 0, radicals: 0 }])
    expect(lewisElectrons({ atoms: [bracket('O', -1, 1)], bonds: [] })).toEqual([{ pairs: 3, radicals: 0 }])
    expect(lewisElectrons({ atoms: [bracket('N'), plain('O')], bonds: [{ a: 0, b: 1, order: 2 }] }))
      .toEqual([{ pairs: 1, radicals: 1 }, { pairs: 2, radicals: 0 }])
  })

  it('refuses an atom left with negative nonbonding electrons, naming it', () => {
    const molecule: Molecule = {
      atoms: [bracket('N', 1, 1), bracket('H'), bracket('H'), bracket('H'), bracket('H')],
      bonds: [1, 2, 3, 4].map(b => ({ a: 0, b, order: 1 })),
    }
    expect(() => lewisElectrons(molecule)).toThrow(/^N \(atom 1\) would have -1 nonbonding electrons/)
  })

  it('refuses aromatic atoms and asks for the Kekulé form', () => {
    expect(() => lewisElectrons({ atoms: [plain('C', { aromatic: true })], bonds: [] })).toThrow(/Kekulé form/)
  })

  it('refuses an element it has no valence count for', () => {
    expect(() => lewisElectrons({ atoms: [bracket('Fe', 3)], bonds: [] })).toThrow(/^Fe has no valence-electron count/)
  })
})

describe('molecularFormula', () => {
  it('writes Hill order with the net charge last', () => {
    const ethanol: Molecule = { atoms: [plain('C'), plain('C'), plain('O')], bonds: [{ a: 0, b: 1, order: 1 }, { a: 1, b: 2, order: 1 }] }
    expect(molecularFormula(ethanol)).toBe('C2H6O')
    expect(molecularFormula({ atoms: [bracket('N', 1, 4)], bonds: [] })).toBe('H4N +')
    const sulfate: Molecule = {
      atoms: [bracket('O', -1), plain('S'), plain('O'), plain('O'), bracket('O', -1)],
      bonds: [{ a: 0, b: 1, order: 1 }, { a: 1, b: 2, order: 2 }, { a: 1, b: 3, order: 2 }, { a: 1, b: 4, order: 1 }],
    }
    expect(molecularFormula(sulfate)).toBe('O4S 2−')
  })
})

describe('explicitHydrogenSmiles', () => {
  it('writes every atom bracketed and every hydrogen as its own [H]', () => {
    const ethanol: Molecule = { atoms: [plain('C'), plain('C'), plain('O')], bonds: [{ a: 0, b: 1, order: 1 }, { a: 1, b: 2, order: 1 }] }
    expect(explicitHydrogenSmiles(ethanol)).toBe('[C]([H])([H])([H])[C]([H])([H])[O][H]')
    expect(explicitHydrogenSmiles({ atoms: [bracket('N', 1, 4)], bonds: [] })).toBe('[N+]([H])([H])([H])[H]')
    expect(explicitHydrogenSmiles({ atoms: [bracket('O', -2)], bonds: [] })).toBe('[O-2]')
  })

  it('closes rings with digits and keeps disconnected components apart', () => {
    const cyclopropene: Molecule = {
      atoms: [plain('C'), plain('C'), plain('C')],
      bonds: [{ a: 0, b: 1, order: 2 }, { a: 1, b: 2, order: 1 }, { a: 2, b: 0, order: 1 }],
    }
    expect(explicitHydrogenSmiles(cyclopropene)).toBe('[C]1([H])=[C]([H])[C]1([H])[H]')
    const salt: Molecule = { atoms: [bracket('Na', 1), bracket('Cl', -1)], bonds: [] }
    expect(explicitHydrogenSmiles(salt)).toBe('[Na+].[Cl-]')
  })
})

describe('Lewis drawing through SmilesDrawer', () => {
  beforeAll(async () => {
    // jsdom has no canvas; SmilesDrawer then estimates label widths instead of logging each miss.
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(null)
    await ensureLewisLoaded()
  })

  // The SmilesDrawer graph fields utils/lewis/index.ts reads: a release that moves them fails here first.
  it('reads the layout graph fields the adapter depends on', () => {
    const graphOf = (smiles: string) => {
      const drawer = new SmilesDrawer.SvgDrawer({})
      drawer.draw(SmilesDrawer.Parser.parse(smiles), null)
      return drawer.preprocessor.graph as unknown as LayoutGraph
    }
    const ammonium = graphOf('[NH4+]').vertices[0]!
    expect(ammonium.value).toMatchObject({ element: 'N', isPartOfAromaticRing: false, bracket: { hcount: 4, charge: 1, chirality: null } })
    expect(typeof ammonium.position.x).toBe('number')
    expect(typeof ammonium.position.y).toBe('number')
    expect(graphOf('CO').vertices[0]!.value.bracket).toBeNull()
    const chiral = graphOf('[C@H](F)(Cl)Br')
    expect(chiral.vertices[0]!.value.bracket).toMatchObject({ chirality: '@', hcount: 1 })
    // Its hydrogen is already a vertex, which is why the adapter counts no implicit hydrogen on a chiral atom.
    expect(chiral.vertices).toHaveLength(5)
    expect(chiral.vertices.some(v => v.value.element === 'H')).toBe(true)
    expect(graphOf('O=[O+][O-]').edges[0]).toMatchObject({ sourceId: 0, targetId: 1, weight: 2 })
    expect(graphOf('[Na+].[Cl-]').edges[0]).toMatchObject({ weight: 0 })
    expect(graphOf('c1ccccc1').vertices[0]!.value.isPartOfAromaticRing).toBe(true)
  })

  it.each([
    ['water', '[H]O[H]', ['H:0p0r', 'H:0p0r', 'O:2p0r']],
    ['ammonia', '[H]N([H])[H]', ['H:0p0r', 'H:0p0r', 'H:0p0r', 'N:1p0r']],
    ['carbon dioxide', 'O=C=O', ['C:0p0r', 'O:2p0r', 'O:2p0r']],
    ['hydrogen cyanide', '[H]C#N', ['C:0p0r', 'H:0p0r', 'N:1p0r']],
    ['ammonium', '[H][N+]([H])([H])[H]', ['H:0p0r', 'H:0p0r', 'H:0p0r', 'H:0p0r', 'N1:0p0r']],
    ['hydroxide', '[H][O-]', ['H:0p0r', 'O-1:3p0r']],
    ['ozone', 'O=[O+][O-]', ['O-1:3p0r', 'O1:1p0r', 'O:2p0r']],
    ['nitric oxide', '[N]=O', ['N:1p1r', 'O:2p0r']],
    ['boron trifluoride', 'FB(F)F', ['B:0p0r', 'F:3p0r', 'F:3p0r', 'F:3p0r']],
    ['sulfate', '[O-]S(=O)(=O)[O-]', ['O-1:3p0r', 'O-1:3p0r', 'O:2p0r', 'O:2p0r', 'S:0p0r']],
  ])('draws %s with the textbook lone pairs and radicals', (_, smiles, expected) => {
    expect(summary(lewisDrawing(smiles))).toEqual(expected)
  })

  it.each([
    ['CCO', '[H]C([H])([H])C([H])([H])O[H]'],
    ['[OH2]', '[H]O[H]'],
    ['[NH4+]', '[H][N+]([H])([H])[H]'],
    ['C[C@H](N)C(=O)O', '[H]C([H])([H])C([H])(N([H])[H])C(=O)O[H]'],
  ])('draws implicit hydrogens in %s as the explicit form %s', (implicit, explicit) => {
    const [a, b] = [lewisDrawing(implicit), lewisDrawing(explicit)]
    expect(summary(a)).toEqual(summary(b))
    expect(bondOrders(a)).toEqual(bondOrders(b))
    expect(a.atoms.every(atom => atom.label.length > 0)).toBe(true)
  })

  it('names the formula and the SMILES as written in the aria-label', () => {
    expect(lewisDrawing('CCO').ariaLabel).toBe('Lewis structure of C2H6O, SMILES CCO')
  })

  it.each([
    ['[H]O(', /^Invalid SMILES: .*parenthes/],
    ['[NH+]([H])([H])([H])[H]', /^N \(atom 1\) would have -1 nonbonding electrons/],
    ['c1ccccc1', /Kekulé form/],
    ['C1:C:C:C:C:C1', /Kekulé form/],
    ['C'.repeat(201), /^Too large to draw as a Lewis structure: the SMILES is 201 characters/],
    // C20H42: 20 atoms as written, 62 once its hydrogens count.
    ['C'.repeat(20), /^Too large to draw as a Lewis structure: 62 atoms counting hydrogens/],
  ])('refuses %s with a one-line reason', (smiles, reason) => {
    const result = renderLewis(smiles)
    expect(result).not.toHaveProperty('svg')
    expect('error' in result && result.error).toMatch(reason)
  })

  it('draws a valid structure to an SVG and memoizes it by SMILES', () => {
    const first = renderLewis('[H]O[H]')
    expect('svg' in first && first.svg).toMatch(/^<svg /)
    expect(renderLewis('[H]O[H]')).toBe(first)
  })
})
