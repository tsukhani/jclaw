# Frontend Design (no existing brand)

Use this reference when designing something outside an existing brand or design system — e.g., a personal project, an early-stage concept, a "help me make this feel designed" request. When you have no tokens to lift, you have to commit to a direction.

---

## The failure mode to avoid

The default AI aesthetic: generic gradient hero, system sans-serif, purple-to-pink, rounded corners everywhere, floating glass cards, hollow SaaS feel. This is what happens when you don't commit to anything. **Pick a direction and go.**

---

## Pick a direction before you pick values

Before choosing colors or fonts, decide on an aesthetic direction. Some prompts that produce distinctive work:

- **Editorial** — think magazines: real serif for display, generous whitespace, careful photo crops, restrained palette, text as a design element
- **Swiss / Bauhaus** — tight grid, limited palette, heavy sans-serif, asymmetric balance, color blocks
- **Brutalist** — exposed structure, raw system fonts, default form controls, heavy borders, no shadows
- **Terminal / Monospaced** — monospace everywhere, high-contrast two-color palette, ASCII accents, line separators
- **Y2K / CD-ROM** — chrome gradients, liquid metal, bevels, Comic Sans adjacent, saturated color
- **Corporate memphis opposite** — photography-led, human scale, warm but specific color (avoid "purple")
- **Editorial dark** — near-black bg, cream text, one saturated accent, serif display
- **Soft pastel** — chalky desaturated palette, rounded-but-not-cute forms, careful type
- **Data-dense / Bloomberg** — small type, tables, dense grids, terminal colors, no decorative space

Commit to ONE. Don't hedge.

---

## Typography is 70% of the work

Type choice and type hierarchy do more to set tone than color does. Skip the defaults (Inter, Roboto, system-ui, Arial). Reach for:

- **Display serifs**: Fraunces, Playfair Display, Canela, GT Sectra, PP Editorial, Tiempos
- **Text serifs**: Source Serif, Libre Caslon, Lora, Crimson Pro
- **Character sans**: Space Grotesk, General Sans, Geist, Satoshi, Clash Display
- **Mono**: JetBrains Mono, Geist Mono, Berkeley Mono, IBM Plex Mono
- **Weird in a good way**: Redaction, Monument Grotesk, Migra, Authentic Sans

Use Google Fonts or Fontsource for availability. Two faces maximum — one display, one text — or one well-made sans in multiple weights.

**Set real type scales.** Don't just use browser defaults. A modular scale (1.2, 1.25, 1.333, 1.414, 1.5) applied to a base size produces harmonious sizes.

---

## Color is about the palette, not the accent

Bad: "pick a primary color, then pick a gray."
Good: build a palette with intent — what's the mood, what's the context of use, what contrasts do I need?

Use `oklch()` to build harmonious palettes:

```css
:root {
  --bg: oklch(97% 0.01 90);        /* warm near-white */
  --fg: oklch(20% 0.02 90);         /* warm near-black */
  --accent: oklch(60% 0.18 25);     /* terracotta */
  --muted: oklch(70% 0.02 90);      /* warm gray */
  --line: oklch(90% 0.01 90);       /* hairline */
}
```

- Keep palettes tight — 4–6 colors including white/black
- Warm or cool — pick one and stay there
- Use `oklch()` for consistent perceptual lightness across hues
- One accent is usually enough; two competing accents split attention

---

## Layout principles

- **Real grids** — use CSS `grid-template-columns` with named lines, not a pile of flexboxes
- **Asymmetry beats symmetry** — editorial layouts are rarely centered
- **Whitespace is a feature** — give things room; don't fill every pixel
- **Line length matters** — body text wraps at 60–75 characters, always
- **Align to something** — every element should align to something else on the page

---

## Things to use (that feel designed)

- `text-wrap: pretty` and `text-wrap: balance` on headings
- Real pull quotes with large type and light rules
- Hairline borders (`1px solid oklch(90% 0.01 h)`) instead of heavy shadows
- `letter-spacing: -0.02em` on large display text to tighten
- CSS `grid-auto-flow: dense` for interesting masonry layouts
- `view-transition-name` on navigating elements
- Subtle `mix-blend-mode: multiply` on colored overlays

---

## Things to avoid (that read as AI)

- Centered hero with h1 + muted subtitle + two buttons
- Purple-to-pink or blue-to-cyan gradients on anything
- Glass cards with backdrop-blur everywhere
- Emoji in UI copy
- Floating particle backgrounds
- Vague stock-photo-style illustrations
- Rounded-corner containers with a colored left border
- "Seamless", "powerful", "intelligent" marketing copy
- Trust badges or fake testimonials
- Every container has a shadow and a border and a gradient

---

## When the user asks for "clean and modern"

This means nothing. Follow up: **modern like what?** Offer them 2–3 concrete directions to pick from (use the list above). Once they pick, commit fully.

---

## Test the design against the deliverable

Before shipping, ask: does this actually work for the use case?

- Marketing page → is there a clear hierarchy and CTA?
- Dashboard → is the data readable, not decorated?
- Pitch deck → does it hold up at 1920×1080 projected?
- Mobile app → are hit targets big enough, readable at arm's length?

Aesthetic direction matters, but not at the cost of function.
