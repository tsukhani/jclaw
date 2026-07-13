// JCLAW-741: client-side password strength estimate + policy constants for the
// set-password form. Advisory only — the backend (ApiAuthController.setup)
// re-validates length and screens the password against known breaches, which
// is authoritative. Kept as a pure function so it is unit-testable and so the
// meter and the canSubmit gate share one source of truth.
//
// Scoring favours LENGTH over composition (NIST 800-63B): a long passphrase
// scores well without symbol/number gymnastics.

export const MIN_PASSWORD_LENGTH = 12
export const MAX_PASSWORD_LENGTH = 128

export interface PasswordStrength {
  /** 0 (too short) … 4 (strong). */
  score: 0 | 1 | 2 | 3 | 4
  /** Human label for the score; empty string for an empty password. */
  label: string
}

const LABELS = ['Too short', 'Weak', 'Fair', 'Good', 'Strong'] as const

/**
 * Estimate password strength from length and character-class variety. Returns
 * a 0–4 score and a label. An empty password returns score 0 with an empty
 * label so the meter can stay hidden.
 */
export function estimatePasswordStrength(password: string): PasswordStrength {
  if (!password) return { score: 0, label: '' }

  const len = password.length
  let score = 0
  if (len >= 8) score++
  if (len >= MIN_PASSWORD_LENGTH) score++
  if (len >= 16) score++

  const classes = [/[a-z]/, /[A-Z]/, /\d/, /[^a-z0-9]/i]
    .filter(re => re.test(password)).length
  if (classes >= 3) score++

  // Anything under 8 chars is "too short" regardless of variety.
  if (len < 8) score = 0

  const clamped = Math.max(0, Math.min(4, score)) as 0 | 1 | 2 | 3 | 4
  return { score: clamped, label: LABELS[clamped] }
}
