// The only click the server lets an agent make without asking is on a link that provably goes to a new page.
// A page script can answer a click on an anchor whose href is "#" or empty (Delete, Confirm, Pay on a
// single-page app), and an addEventListener handler cannot be seen from outside, so anything that does not
// visibly leave the page waits for the operator.
const ACTION_ROLES = /^(?!link$)/i

/**
 * @param {{ tag: string, rawHref: string | null, href: string, onclick: boolean, role: string | null }} facts
 *   as read off the element: the href attribute as written, the resolved address, an onclick attribute, the ARIA role
 * @param {string} pageUrl the address of the page the element is on
 */
export function plainLink(facts, pageUrl) {
  if (facts.tag !== 'A' && facts.tag !== 'a') return false
  const raw = (facts.rawHref ?? '').trim()
  if (!raw || raw.startsWith('#')) return false
  if (facts.onclick) return false
  if (facts.role && ACTION_ROLES.test(facts.role.trim())) return false
  let target
  let here
  try {
    target = new URL(facts.href)
    here = new URL(pageUrl)
  } catch {
    return false
  }
  if (target.protocol !== 'http:' && target.protocol !== 'https:') return false
  target.hash = ''
  here.hash = ''
  return target.href !== here.href
}
