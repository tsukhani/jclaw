/**
 * A `$fetch` (or `$fetch.raw`) call with a write method must run through `useApiMutation().mutate`
 * or inside a `useSaveAttempt().attempt(...)` callback, so its failure is kept as ApiErrorDetails
 * (JCLAW-1131) and reaches ApiErrorAlert. A hand-rolled try/catch around a write is what left the
 * Settings radios stuck on a refused choice (JCLAW-1221). `allowFiles` names the wrappers themselves.
 * Only a callee named `attempt` (or `x.attempt`) counts, so a second attempt in one file is held as
 * `const xSave = useSaveAttempt()` and called `xSave.attempt(...)`, not destructured under an alias.
 */

const WRITE_METHODS = new Set(['POST', 'PUT', 'DELETE', 'PATCH'])

/** @param {any} callee */
function isFetchCallee(callee) {
  if (callee.type === 'Identifier') return callee.name === '$fetch'
  return callee.type === 'MemberExpression'
    && callee.object.type === 'Identifier' && callee.object.name === '$fetch'
    && callee.property.type === 'Identifier' && callee.property.name === 'raw'
}

/**
 * The write method a `method:` value names, or null. A conditional counts when either branch is a
 * write, since `starred ? 'DELETE' : 'PUT'` is a write on every path.
 * @param {any} v
 * @returns {string | null}
 */
function methodNamed(v) {
  let method = null
  if (v.type === 'Literal' && typeof v.value === 'string') method = v.value
  else if (v.type === 'TemplateLiteral' && v.expressions.length === 0) method = v.quasis[0]?.value.cooked ?? null
  else if (v.type === 'ConditionalExpression') return methodNamed(v.consequent) ?? methodNamed(v.alternate)
  return method && WRITE_METHODS.has(method.toUpperCase()) ? method.toUpperCase() : null
}

/** @param {any} node */
function writeMethod(node) {
  const opts = node.arguments[1]
  if (!opts || opts.type !== 'ObjectExpression') return null
  for (const p of opts.properties) {
    if (p.type !== 'Property' || p.key.type !== 'Identifier' || p.key.name !== 'method') continue
    return methodNamed(p.value)
  }
  return null
}

/** @param {any} node */
function insideAttempt(node) {
  for (let n = node.parent; n; n = n.parent) {
    if (n.type !== 'CallExpression') continue
    const c = n.callee
    const name = c.type === 'Identifier'
      ? c.name
      : (c.type === 'MemberExpression' && c.property.type === 'Identifier' ? c.property.name : null)
    if (name === 'attempt') return true
  }
  return false
}

/** @type {import('eslint').Rule.RuleModule} */
export default {
  meta: {
    type: 'problem',
    docs: { description: 'Route API writes through useApiMutation().mutate or useSaveAttempt().attempt' },
    schema: [{
      type: 'object',
      properties: { allowFiles: { type: 'array', items: { type: 'string' } } },
      additionalProperties: false,
    }],
    messages: {
      bareWrite: '$fetch {{method}} outside useApiMutation().mutate or useSaveAttempt().attempt: '
        + 'its failure never reaches ApiErrorAlert as ApiErrorDetails.',
    },
  },
  create(context) {
    const allow = context.options[0]?.allowFiles ?? []
    const filename = context.filename.replaceAll('\\', '/')
    if (allow.some(suffix => filename.endsWith(suffix))) return {}
    return {
      CallExpression(node) {
        if (!isFetchCallee(node.callee)) return
        const method = writeMethod(node)
        if (!method || insideAttempt(node)) return
        context.report({ node, messageId: 'bareWrite', data: { method } })
      },
    }
  },
}
