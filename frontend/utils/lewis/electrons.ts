/**
 * Lewis-structure chemistry over a parsed molecule: hydrogen counts, lone pairs and radicals, the
 * molecular formula, and the rewrite of a molecule as SMILES with every hydrogen an explicit atom.
 * Pure; SmilesDrawer is adapted to these types in ./index.ts.
 */

export interface MoleculeAtom {
  element: string
  charge: number
  /** A bracket atom carries its hydrogens in `hcount`; an unbracketed one takes them from its normal valence. */
  bracket: boolean
  hcount: number
  aromatic: boolean
}

export interface MoleculeBond {
  a: number
  b: number
  order: number
}

export interface Molecule {
  atoms: MoleculeAtom[]
  bonds: MoleculeBond[]
}

export interface AtomElectrons {
  pairs: number
  radicals: number
}

export class LewisError extends Error {}

export const KEKULE_FORM_REQUIRED
  = 'Aromatic atoms and bonds (lowercase atoms, \':\' bonds) are not supported: write the ring in Kekulé form, such as C1=CC=CC=C1 for benzene.'

const VALENCE_ELECTRONS: Readonly<Record<string, number>> = {
  H: 1, He: 2,
  Li: 1, Be: 2, B: 3, C: 4, N: 5, O: 6, F: 7, Ne: 8,
  Na: 1, Mg: 2, Al: 3, Si: 4, P: 5, S: 6, Cl: 7, Ar: 8,
  K: 1, Ca: 2, Ga: 3, Ge: 4, As: 5, Se: 6, Br: 7, Kr: 8,
  Rb: 1, Sr: 2, In: 3, Sn: 4, Sb: 5, Te: 6, I: 7, Xe: 8,
  Cs: 1, Ba: 2, Tl: 3, Pb: 4, Bi: 5, Po: 6, At: 7, Rn: 8,
}

// OpenSMILES organic subset: the only elements that may be written without brackets.
const NORMAL_VALENCES: Readonly<Record<string, readonly number[]>> = {
  B: [3], C: [4], N: [3, 5], O: [2], P: [3, 5], S: [2, 4, 6], F: [1], Cl: [1], Br: [1], I: [1],
}

const BOND_SYMBOLS: Readonly<Record<number, string>> = { 1: '', 2: '=', 3: '#', 4: '$' }

function bondSums(molecule: Molecule): number[] {
  const sums = molecule.atoms.map(() => 0)
  for (const bond of molecule.bonds) {
    sums[bond.a]! += bond.order
    sums[bond.b]! += bond.order
  }
  return sums
}

/** Hydrogens each atom carries that are not atoms of the graph: none, once every hydrogen is explicit. */
export function hydrogenCounts(molecule: Molecule): number[] {
  const sums = bondSums(molecule)
  return molecule.atoms.map((atom, i) => {
    if (atom.bracket) return atom.hcount
    const sum = sums[i]!
    const valence = NORMAL_VALENCES[atom.element]?.find(v => v >= sum)
    return valence === undefined ? 0 : valence - sum
  })
}

/**
 * Nonbonding electrons per atom, n = valence − charge − (bond orders + hydrogens), as floor(n/2) pairs and n mod 2
 * radicals. Throws a LewisError for aromatic atoms, elements with no valence count, and a negative n.
 */
export function lewisElectrons(molecule: Molecule): AtomElectrons[] {
  if (molecule.atoms.some(atom => atom.aromatic)) {
    throw new LewisError(KEKULE_FORM_REQUIRED)
  }
  const sums = bondSums(molecule)
  const hydrogens = hydrogenCounts(molecule)
  return molecule.atoms.map((atom, i) => {
    const valence = VALENCE_ELECTRONS[atom.element]
    if (valence === undefined) {
      throw new LewisError(`${atom.element} has no valence-electron count here: only main-group elements are drawn.`)
    }
    const n = valence - atom.charge - sums[i]! - hydrogens[i]!
    if (n < 0) {
      throw new LewisError(`${atom.element} (atom ${i + 1}) would have ${n} nonbonding electrons: check its bonds, hydrogens and charge.`)
    }
    return { pairs: Math.floor(n / 2), radicals: n % 2 }
  })
}

/** '+', '2−' and so on; empty for a neutral atom. */
export function chargeLabel(charge: number): string {
  if (charge === 0) return ''
  const sign = charge > 0 ? '+' : '−'
  return Math.abs(charge) === 1 ? sign : `${Math.abs(charge)}${sign}`
}

/** Hill order (C, then H, then alphabetical; alphabetical throughout without carbon), net charge last. */
export function molecularFormula(molecule: Molecule): string {
  const hydrogens = hydrogenCounts(molecule)
  const counts = new Map<string, number>()
  const add = (element: string, n: number) => counts.set(element, (counts.get(element) ?? 0) + n)
  molecule.atoms.forEach((atom, i) => {
    add(atom.element, 1)
    if (hydrogens[i]! > 0) add('H', hydrogens[i]!)
  })
  const hill = counts.has('C') ? ['C', 'H'] : []
  const order = [...hill.filter(e => counts.has(e)), ...[...counts.keys()].filter(e => !hill.includes(e)).sort()]
  const formula = order.map((e) => {
    const n = counts.get(e)!
    return n > 1 ? `${e}${n}` : e
  }).join('')
  const charge = molecule.atoms.reduce((total, atom) => total + atom.charge, 0)
  return charge === 0 ? formula : `${formula} ${chargeLabel(charge)}`
}

function bracketAtom(atom: MoleculeAtom): string {
  if (atom.charge === 0) return `[${atom.element}]`
  const magnitude = Math.abs(atom.charge)
  return `[${atom.element}${atom.charge > 0 ? '+' : '-'}${magnitude === 1 ? '' : magnitude}]`
}

/**
 * Writes the molecule as SMILES in which every atom is a bracket atom and every hydrogen an explicit `[H]`, so a
 * second layout places each hydrogen as a bonded atom of its own. Stereo, isotopes and atom classes are dropped.
 */
export function explicitHydrogenSmiles(molecule: Molecule): string {
  const { atoms, bonds } = molecule
  const hydrogens = hydrogenCounts(molecule)
  const adjacency = atoms.map((): { bond: number, to: number }[] => [])
  bonds.forEach((bond, i) => {
    adjacency[bond.a]!.push({ bond: i, to: bond.b })
    adjacency[bond.b]!.push({ bond: i, to: bond.a })
  })

  // A depth-first spanning forest; each bond it leaves out becomes a ring closure.
  const seen = atoms.map(() => false)
  const children = atoms.map((): { bond: number, to: number }[] => [])
  const closures = atoms.map((): number[] => [])
  const classified = new Set<number>()
  const roots: number[] = []
  const walk = (atom: number) => {
    seen[atom] = true
    for (const edge of adjacency[atom]!) {
      if (classified.has(edge.bond)) continue
      classified.add(edge.bond)
      if (seen[edge.to]) {
        closures[atom]!.push(edge.bond)
        closures[edge.to]!.push(edge.bond)
      }
      else {
        children[atom]!.push(edge)
        walk(edge.to)
      }
    }
  }
  atoms.forEach((_, i) => {
    if (!seen[i]) {
      roots.push(i)
      walk(i)
    }
  })

  // Ring digits are never reused, so no atom can close and reopen the same digit.
  const openDigits = new Map<number, number>()
  let nextDigit = 1
  const emit = (atom: number): string => {
    let out = bracketAtom(atoms[atom]!)
    for (const bond of closures[atom]!) {
      const open = openDigits.get(bond)
      const digit = open ?? nextDigit++
      if (digit > 99) throw new LewisError('Too many rings to draw.')
      out += (open === undefined ? BOND_SYMBOLS[bonds[bond]!.order] ?? '' : '') + (digit < 10 ? `${digit}` : `%${digit}`)
      openDigits.set(bond, digit)
    }
    const branches = [
      ...Array.from({ length: hydrogens[atom]! }, () => '[H]'),
      ...children[atom]!.map(edge => (BOND_SYMBOLS[bonds[edge.bond]!.order] ?? '') + emit(edge.to)),
    ]
    branches.forEach((branch, k) => {
      out += k < branches.length - 1 ? `(${branch})` : branch
    })
    return out
  }
  return roots.map(emit).join('.')
}
