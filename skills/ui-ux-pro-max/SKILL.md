---
name: ui-ux-pro-max
description: UI/UX design intelligence for web and mobile. Includes 67 styles, 161 color palettes, 57 font pairings, 161 product types, 99 UX guidelines, and 25 chart types across 16 stacks (React, Next.js, Vue, Svelte, SwiftUI, React Native, Flutter, Tailwind, shadcn/ui, Angular, Laravel, and more). Actions: plan, build, create, design, implement, review, fix, improve, optimize, enhance, refactor, and check UI/UX code. Projects: website, landing page, dashboard, admin panel, e-commerce, SaaS, portfolio, blog, and mobile app. Elements: button, modal, navbar, sidebar, card, table, form, and chart. Styles: glassmorphism, claymorphism, minimalism, brutalism, neumorphism, bento grid, dark mode, responsive, skeuomorphism, and flat design. Topics: color systems, accessibility, animation, layout, typography, font pairing, spacing, interaction states, shadow, and gradient.
version: 1.0.0
author: main
tools: [exec, filesystem]
commands: []
icon: 🎨
---

# UI/UX Pro Max — Design Intelligence

Comprehensive design guide for web and mobile applications. Contains 67 styles, 161 color palettes, 57 font pairings, 161 product types with reasoning rules, 99 UX guidelines, and 25 chart types across 16 technology stacks. Searchable database with BM25-ranked recommendations and an AI-powered Design System Generator.

## When to Apply

Use this skill when the task involves **UI structure, visual design decisions, interaction patterns, or user experience quality control**.

### Must Use

- Designing new pages (Landing Page, Dashboard, Admin, SaaS, Mobile App)
- Creating or refactoring UI components (buttons, modals, forms, tables, charts, etc.)
- Choosing color schemes, typography systems, spacing standards, or layout systems
- Reviewing UI code for user experience, accessibility, or visual consistency
- Implementing navigation structures, animations, or responsive behavior
- Making product-level design decisions (style, information hierarchy, brand expression)
- Improving perceived quality, clarity, or usability of interfaces

### Recommended

- UI looks "not professional enough" but the reason is unclear
- Receiving feedback on usability or experience
- Pre-launch UI quality optimization
- Aligning cross-platform design (Web / iOS / Android)
- Building design systems or reusable component libraries

### Skip

- Pure backend logic development
- Only involving API or database design
- Performance optimization unrelated to the interface
- Infrastructure or DevOps work
- Non-visual scripts or automation tasks

**Decision criteria**: If the task will change how a feature **looks, feels, moves, or is interacted with**, use this skill.

---

## Prerequisites

Python 3 is required. Check with:

```bash
python3 --version || python --version
```

If missing, install:
- **macOS:** `brew install python3`
- **Ubuntu/Debian:** `sudo apt update && sudo apt install python3`
- **Windows:** `winget install Python.Python.3.12`

---

## Workflow

### Step 1: Analyze User Requirements

Extract from the user's request:
- **Product type**: SaaS, e-commerce, fintech, healthcare, gaming, portfolio, beauty, service, etc.
- **Target audience**: Consumer, enterprise, age group, usage context
- **Style keywords**: playful, vibrant, minimal, dark mode, content-first, immersive, etc.
- **Stack**: HTML+Tailwind (default), React, Next.js, Vue, Svelte, SwiftUI, React Native, Flutter, etc.

### Step 2: Generate Design System (REQUIRED for new projects)

**Always start with `--design-system`** to get comprehensive AI-powered recommendations:

```bash
python3 skills/ui-ux-pro-max/scripts/search.py "<product_type> <industry> <keywords>" --design-system [-p "Project Name"]
```

This command:
1. Searches 5 domains in parallel (product, style, color, landing, typography)
2. Applies 161 industry-specific reasoning rules from `ui-reasoning.csv`
3. Returns a complete design system: pattern, style, colors, typography, effects
4. Includes anti-patterns to avoid and a pre-delivery checklist

**Example:**
```bash
python3 skills/ui-ux-pro-max/scripts/search.py "beauty spa wellness service" --design-system -p "Serenity Spa"
```

**Markdown output (for documentation):**
```bash
python3 skills/ui-ux-pro-max/scripts/search.py "fintech crypto" --design-system -f markdown
```

### Step 2b: Persist Design System (Master + Overrides Pattern)

To save the design system for hierarchical retrieval across sessions:

```bash
python3 skills/ui-ux-pro-max/scripts/search.py "<query>" --design-system --persist -p "Project Name"
```

This creates:
- `design-system/<project>/MASTER.md` — Global Source of Truth
- `design-system/<project>/pages/` — Folder for page-specific overrides

**With page-specific override:**
```bash
python3 skills/ui-ux-pro-max/scripts/search.py "<query>" --design-system --persist -p "Project Name" --page "dashboard"
```

**How hierarchical retrieval works:**
1. When building a specific page, first check `design-system/<project>/pages/[page].md`
2. If the page file exists, its rules **override** the Master file
3. If not, use `design-system/<project>/MASTER.md` exclusively

### Step 3: Supplement with Detailed Searches

After getting the design system, use domain searches for additional details:

```bash
python3 skills/ui-ux-pro-max/scripts/search.py "<keyword>" --domain <domain> [-n <max_results>]
```

| Need | Domain | Example |
|------|--------|---------|
| Product type patterns | `product` | `--domain product "entertainment social"` |
| More style options | `style` | `--domain style "glassmorphism dark"` |
| Color palettes | `color` | `--domain color "entertainment vibrant"` |
| Font pairings | `typography` | `--domain typography "playful modern"` |
| Chart recommendations | `chart` | `--domain chart "real-time dashboard"` |
| UX best practices | `ux` | `--domain ux "animation accessibility"` |
| Landing structure | `landing` | `--domain landing "hero social-proof"` |
| Individual Google Fonts | `google-fonts` | `--domain google-fonts "sans serif popular"` |
| Icon recommendations | `icons` | `--domain icons "navigation arrows"` |
| React/Next.js performance | `react` | `--domain react "rerender memo list"` |
| App interface guidelines | `web` | `--domain web "accessibilityLabel touch safe-areas"` |

### Step 4: Stack-Specific Guidelines

Get implementation-specific best practices for your stack:

```bash
python3 skills/ui-ux-pro-max/scripts/search.py "<keyword>" --stack <stack>
```

Available stacks: `react`, `nextjs`, `vue`, `svelte`, `astro`, `swiftui`, `react-native`, `flutter`, `nuxtjs`, `nuxt-ui`, `html-tailwind`, `shadcn`, `jetpack-compose`, `threejs`, `angular`, `laravel`

---

## Search Reference

### Available Domains

| Domain | Use For | Example Keywords |
|--------|---------|------------------|
| `product` | Product type recommendations | SaaS, e-commerce, portfolio, healthcare, beauty, service |
| `style` | UI styles, colors, effects | glassmorphism, minimalism, dark mode, brutalism |
| `typography` | Font pairings, Google Fonts | elegant, playful, professional, modern |
| `color` | Color palettes by product type | saas, ecommerce, healthcare, beauty, fintech, service |
| `landing` | Page structure, CTA strategies | hero, hero-centric, testimonial, pricing, social-proof |
| `chart` | Chart types, library recommendations | trend, comparison, timeline, funnel, pie |
| `ux` | Best practices, anti-patterns | animation, accessibility, z-index, loading |
| `google-fonts` | Individual Google Fonts lookup | sans serif, monospace, variable font, popular |
| `icons` | Icon library recommendations | navigation, arrows, social, media |
| `react` | React/Next.js performance | waterfall, bundle, suspense, memo, rerender, cache |
| `web` | App interface guidelines (iOS/Android/RN) | accessibilityLabel, touch targets, safe areas |

---

## Quick Reference: Rule Categories by Priority

| Priority | Category | Impact | Domain | Key Checks | Anti-Patterns |
|----------|----------|--------|--------|------------|---------------|
| 1 | Accessibility | CRITICAL | `ux` | Contrast 4.5:1, Alt text, Keyboard nav, Aria-labels | Removing focus rings, Icon-only buttons without labels |
| 2 | Touch & Interaction | CRITICAL | `ux` | Min size 44×44px, 8px+ spacing, Loading feedback | Reliance on hover only, Instant state changes (0ms) |
| 3 | Performance | HIGH | `ux` | WebP/AVIF, Lazy loading, Reserve space (CLS < 0.1) | Layout thrashing, Cumulative Layout Shift |
| 4 | Style Selection | HIGH | `style`, `product` | Match product type, Consistency, SVG icons (no emoji) | Mixing flat & skeuomorphic, Emoji as icons |
| 5 | Layout & Responsive | HIGH | `ux` | Mobile-first breakpoints, Viewport meta, No horizontal scroll | Horizontal scroll, Fixed px containers, Disable zoom |
| 6 | Typography & Color | MEDIUM | `typography`, `color` | Base 16px, Line-height 1.5, Semantic color tokens | Text < 12px body, Gray-on-gray, Raw hex in components |
| 7 | Animation | MEDIUM | `ux` | Duration 150–300ms, Motion conveys meaning, Spatial continuity | Decorative-only animation, Animating width/height, No reduced-motion |
| 8 | Forms & Feedback | MEDIUM | `ux` | Visible labels, Error near field, Helper text, Progressive disclosure | Placeholder-only label, Errors only at top, Overwhelm upfront |
| 9 | Navigation Patterns | HIGH | `ux` | Predictable back, Bottom nav ≤5, Deep linking | Overloaded nav, Broken back behavior, No deep links |
| 10 | Charts & Data | LOW | `chart` | Legends, Tooltips, Accessible colors | Relying on color alone to convey meaning |

---

## Pre-Delivery Checklist

Before delivering UI code, verify:

### Visual Quality
- [ ] No emojis used as icons (use SVG: Heroicons/Lucide)
- [ ] All icons from consistent icon family and style
- [ ] Official brand assets used with correct proportions
- [ ] Pressed-state visuals do not shift layout bounds
- [ ] Semantic theme tokens used consistently

### Interaction
- [ ] All tappable elements provide clear pressed feedback
- [ ] Touch targets ≥44×44pt (iOS) / ≥48×48dp (Android)
- [ ] Micro-interaction timing 150-300ms with native easing
- [ ] Disabled states visually clear and non-interactive
- [ ] Screen reader focus order matches visual order

### Light/Dark Mode
- [ ] Primary text contrast ≥4.5:1 in both modes
- [ ] Secondary text contrast ≥3:1 in both modes
- [ ] Dividers/borders visible in both modes
- [ ] Modal scrim opacity 40-60% black
- [ ] Both themes tested before delivery

### Layout
- [ ] Safe areas respected for headers, tab bars, CTAs
- [ ] Scroll content not hidden behind fixed bars
- [ ] Verified on small phone, large phone, tablet (portrait + landscape)
- [ ] 4/8dp spacing rhythm maintained
- [ ] No horizontal scroll on mobile

### Accessibility
- [ ] All meaningful images/icons have accessibility labels
- [ ] Form fields have labels, hints, and clear error messages
- [ ] Color is not the only indicator
- [ ] Reduced motion and dynamic text size supported
- [ ] `cursor-pointer` on all clickable elements (web)

---

## Tips for Better Results

- Use **multi-dimensional keywords**: `"entertainment social vibrant content-dense"` not just `"app"`
- Try different keywords for the same need: `"playful neon"` → `"vibrant dark"` → `"content-first minimal"`
- Always use `--design-system` first for full recommendations, then `--domain` to deep-dive
- Add `--stack <your-stack>` for implementation-specific guidance
- Run `--domain ux "animation accessibility z-index loading"` as a UX validation pass before implementation

---

## Example Workflow

**User request:** "Build a landing page for my SaaS product."

### Step 1: Analyze
- Product type: SaaS
- Style keywords: modern, professional, conversion-optimized
- Stack: HTML + Tailwind (default)

### Step 2: Generate Design System
```bash
python3 skills/ui-ux-pro-max/scripts/search.py "SaaS B2B modern professional" --design-system -p "MySaaS"
```

### Step 3: Supplement
```bash
python3 skills/ui-ux-pro-max/scripts/search.py "conversion optimization" --domain landing
python3 skills/ui-ux-pro-max/scripts/search.py "form validation loading" --domain ux
```

### Step 4: Stack Guidelines
```bash
python3 skills/ui-ux-pro-max/scripts/search.py "responsive layout" --stack html-tailwind
```

**Then:** Synthesize the design system + detailed searches and implement the design.

---

## Output Location

Generated design system files (via `--persist`) are written to `design-system/<project>/` in the current working directory. The skill's own scripts and data live at `skills/ui-ux-pro-max/`.
