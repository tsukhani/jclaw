### Design System (paste the `:root` block into the `<style>` tag verbatim, adapt accent color as needed)

```css
:root {
  /* BACKGROUNDS */
  --color-bg:             #FAF7F2;
  --color-bg-warm:        #F5F0E8;
  --color-bg-code:        #1E1E2E;
  --color-text:           #2C2A28;
  --color-text-secondary: #6B6560;
  --color-text-muted:     #9E9790;
  --color-border:         #E5DFD6;
  --color-border-light:   #EEEBE5;
  --color-surface:        #FFFFFF;
  --color-surface-warm:   #FDF9F3;

  /* ACCENT — pick ONE. Default: vermillion.
     Alternatives: coral (#E06B56), teal (#2A7B9B), amber (#D4A843), forest (#2D8B55). */
  --color-accent:         #D94F30;
  --color-accent-hover:   #C4432A;
  --color-accent-light:   #FDEEE9;
  --color-accent-muted:   #E8836C;

  /* SEMANTIC */
  --color-success:        #2D8B55;
  --color-success-light:  #E8F5EE;
  --color-error:          #C93B3B;
  --color-error-light:    #FDE8E8;
  --color-info:           #2A7B9B;
  --color-info-light:     #E4F2F7;

  /* ACTOR COLORS — assign to main components for chat bubbles & diagrams */
  --color-actor-1: #D94F30;   /* vermillion */
  --color-actor-2: #2A7B9B;   /* teal */
  --color-actor-3: #7B6DAA;   /* muted plum */
  --color-actor-4: #D4A843;   /* golden */
  --color-actor-5: #2D8B55;   /* forest */

  /* FONTS */
  --font-display: 'Bricolage Grotesque', Georgia, serif;
  --font-body:    'DM Sans', -apple-system, sans-serif;
  --font-mono:    'JetBrains Mono', 'Fira Code', Consolas, monospace;

  /* TYPE SCALE (1.25 ratio) */
  --text-xs: .75rem; --text-sm: .875rem; --text-base: 1rem;
  --text-lg: 1.125rem; --text-xl: 1.25rem; --text-2xl: 1.5rem;
  --text-3xl: 1.875rem; --text-4xl: 2.25rem; --text-5xl: 3rem; --text-6xl: 3.75rem;

  /* LINE HEIGHTS */
  --leading-tight: 1.15; --leading-snug: 1.3;
  --leading-normal: 1.6; --leading-loose: 1.8;

  /* SPACING */
  --space-1: .25rem; --space-2: .5rem; --space-3: .75rem; --space-4: 1rem;
  --space-5: 1.25rem; --space-6: 1.5rem; --space-8: 2rem; --space-10: 2.5rem;
  --space-12: 3rem; --space-16: 4rem; --space-20: 5rem; --space-24: 6rem;

  /* LAYOUT */
  --content-width: 800px;
  --content-width-wide: 1000px;
  --nav-height: 50px;
  --radius-sm: 8px; --radius-md: 12px; --radius-lg: 16px; --radius-full: 9999px;

  /* SHADOWS — warm-tinted, never pure black */
  --shadow-sm: 0 1px 2px rgba(44,42,40,.05);
  --shadow-md: 0 4px 12px rgba(44,42,40,.08);
  --shadow-lg: 0 8px 24px rgba(44,42,40,.10);
  --shadow-xl: 0 16px 48px rgba(44,42,40,.12);

  /* ANIMATION */
  --ease-out: cubic-bezier(.16,1,.3,1);
  --ease-in-out: cubic-bezier(.65,0,.35,1);
  --duration-fast: 150ms; --duration-normal: 300ms; --duration-slow: 500ms;
  --stagger-delay: 120ms;
}
```

### Fonts `<link>` to put in `<head>`

```html
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=Bricolage+Grotesque:opsz,wght@12..96,400;12..96,600;12..96,700;12..96,800&family=DM+Sans:ital,opsz,wght@0,9..40,300;0,9..40,400;0,9..40,500;0,9..40,600;0,9..40,700;1,9..40,400;1,9..40,500&family=JetBrains+Mono:wght@400;500;600&display=swap" rel="stylesheet">
```

### Syntax highlighting (Catppuccin-inspired, for dark code blocks)

```css
.code-keyword  { color: #CBA6F7; }  /* purple — if/else/return/function */
.code-string   { color: #A6E3A1; }  /* green — "strings" */
.code-function { color: #89B4FA; }  /* blue — function names */
.code-comment  { color: #6C7086; }  /* muted gray — // comments */
.code-number   { color: #FAB387; }  /* peach — numbers */
.code-property { color: #F9E2AF; }  /* yellow — object keys */
.code-operator { color: #94E2D5; }  /* teal — =, =>, + */
.code-tag      { color: #F38BA8; }  /* pink — HTML tags */
.code-attr     { color: #F9E2AF; }  /* yellow — HTML attributes */
.code-value    { color: #A6E3A1; }  /* green — attribute values */
```
