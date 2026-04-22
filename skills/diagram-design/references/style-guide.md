# Diagram Design Style Guide

This file serves as the single source of truth for all colors, typography, and design tokens used in diagrams. When onboarding a new site, this file gets updated automatically.

## Semantic Tokens

Semantic tokens describe the purpose of each color/style, not its visual value. The specific values can be customized per project.

### Default Tokens

```css
:root {
  /* Base Colors */
  --paper: #faf7f2;       /* Page background */
  --paper-2: #ffffff;     /* Card/container background */
  --ink: #1c1917;         /* Primary text & borders */
  --muted: #52534e;       /* Secondary text, default arrows */
  --soft: #65655c;        /* Tertiary text, sublabels */
  
  /* Lines & Rules */
  --rule: rgba(11,13,11,0.10);  /* Hairline separators */
  --rule-solid: #e0ddd7;        /* Solid line separators */
  
  /* Accent Colors */
  --accent: #b5523a;      /* Primary accent (1-2 focal elements) */
  --accent-tint: #fbeae6; /* Background for focal elements */
  
  /* Functional Colors */
  --link: #1a70c7;        /* HTTP/API calls, external arrows */
  
  /* Typography */
  --title-font: "Instrument Serif", serif; /* Page title, H1 only */
  --body-font: "Geist", sans-serif;        /* Node names, body text */
  --mono-font: "Geist Mono", monospace;    /* Technical content: ports, URLs, field types */
}
```

## Node Type Treatments

| Type | Fill | Stroke |
|------|------|--------|
| Focal (1-2 max) | var(--accent-tint) | var(--accent) |
| Backend / API / Step | var(--paper-2) | var(--ink) |
| Store / State | rgba(var(--ink-rgb), 0.05) | var(--muted) |
| External / Cloud | rgba(var(--ink-rgb), 0.03) | rgba(var(--ink-rgb), 0.30) |
| Input / User | rgba(var(--muted-rgb), 0.10) | var(--soft) |
| Optional / Async | rgba(var(--ink-rgb), 0.02) | rgba(var(--ink-rgb), 0.20) dashed 4,3 |
| Security / Boundary | rgba(var(--accent-rgb), 0.05) | rgba(var(--accent-rgb), 0.50) dashed 4,4 |

## Typography Scale

| Element | Font | Size | Weight | Case | Tracking |
|---------|------|------|--------|------|----------|
| Title | var(--title-font) | 1.75rem | 400 | Normal | Normal |
| Node name | var(--body-font) | 12px | 600 | Normal | Normal |
| Sublabel | var(--mono-font) | 9px | 400 | Normal | Normal |
| Eyebrow / tag | var(--mono-font) | 7-8px | 400 | Uppercase | 0.08em |
| Arrow label | var(--mono-font) | 8px | 400 | Uppercase | 0.06em |
| Editorial aside | var(--title-font) | 14px | 400 | Normal (italic) | Normal |

## Layout Grid

All values must be divisible by 4:

| Category | Allowed Values |
|----------|----------------|
| Font sizes | 8, 12, 16, 20, 24, 28, 32, 40 |
| Node width/height | 80, 96, 112, 120, 128, 140, 144, 160, 180, 200, 240, 320 |
| x/y coordinates | Multiples of 4 |
| Gap between nodes | 20, 24, 32, 40, 48 |
| Padding inside boxes | 8, 12, 16 |
| Border radius | 4, 6, 8, 10 |

## Required Google Fonts

```html
<link href="https://fonts.googleapis.com/css2?family=Instrument+Serif:ital@0;1&family=Geist:wght@400;500;600&family=Geist+Mono:wght@400;500;600&display=swap" rel="stylesheet">
```