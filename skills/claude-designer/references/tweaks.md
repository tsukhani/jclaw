# Tweaks — In-page Variant Controls

Use this reference when you need to expose **toggleable variants inside a single design file**, instead of creating multiple files for each variation.

---

## When to use tweaks

- The user asked for "3 versions" of something → put all 3 in one file and toggle between them
- You want to offer a variant the user didn't ask for → add it as a tweak so they can try it without committing
- An element has a few plausible alternatives (copy A vs B, warm vs cool palette, compact vs spacious) → tweak it
- The user is iterating and likely to say "what if X was different?" → preemptively tweakable

**Don't tweak every possible choice.** Tweakable surface should be small — the 3–6 decisions most likely to matter. Hide the control panel entirely when not in use so the design looks final.

---

## Control panel pattern

Put a small floating control panel in the bottom-right corner, or use inline handles next to the tweakable elements. Keep it visually distinct from the design (maybe an outlined panel with a monospaced label) so it's clearly meta.

Basic shape:

```html
<div id="tweaks-panel" style="
  position: fixed;
  bottom: 16px;
  right: 16px;
  background: white;
  border: 1px solid #ddd;
  border-radius: 8px;
  padding: 12px 16px;
  font-family: ui-monospace, monospace;
  font-size: 12px;
  box-shadow: 0 2px 10px rgba(0,0,0,0.08);
  z-index: 9999;
">
  <div style="margin-bottom: 8px; font-weight: 600; letter-spacing: 0.05em;">TWEAKS</div>
  <!-- controls here -->
</div>
```

Title the panel "Tweaks" so the naming is consistent.

---

## Common things to expose

- **Primary color** — a color picker or 3–4 swatch buttons
- **Font family** — a dropdown with 3–4 options
- **Font size** — a slider (14–20px for body, 40–100px for display)
- **Spacing density** — compact / comfortable / spacious radio
- **Layout variant** — if the design has 2–3 layout options, toggle between them
- **Dark mode** — a simple on/off toggle
- **Copy variants** — A/B of a headline or CTA
- **Feature flags** — show/hide a specific section
- **Radius** — square / rounded / pill
- **Density** — number of items, line-height, padding scale

---

## Implementation in vanilla JS

For small prototypes, plain DOM events + CSS variables is easiest:

```html
<style>
  :root {
    --accent: #d97757;
    --font-display: 'Fraunces', serif;
    --density: 1;
  }
  .hero h1 { color: var(--accent); font-family: var(--font-display); }
  .card { padding: calc(16px * var(--density)); }
</style>

<script>
  document.getElementById('accent-picker').addEventListener('input', e => {
    document.documentElement.style.setProperty('--accent', e.target.value);
  });
  document.getElementById('density-slider').addEventListener('input', e => {
    document.documentElement.style.setProperty('--density', e.target.value);
  });
</script>
```

## Implementation in React

For React prototypes, hold tweak values in state at the top of the tree:

```jsx
function App() {
  const [tweaks, setTweaks] = useState({
    accent: '#d97757',
    density: 'comfortable',
    showOnboarding: true,
    copyVariant: 'A',
  });

  return (
    <div style={{ '--accent': tweaks.accent }}>
      {/* ... design ... */}
      <TweaksPanel tweaks={tweaks} onChange={setTweaks} />
    </div>
  );
}
```

Keep the `TweaksPanel` component in a separate file and import it — it's noise that shouldn't clutter the main design code.

---

## Persisting tweak state

Save tweak values to `localStorage` so they survive reload:

```jsx
const [tweaks, setTweaks] = useState(() => {
  try {
    return JSON.parse(localStorage.getItem('tweaks')) || DEFAULTS;
  } catch { return DEFAULTS; }
});

useEffect(() => {
  localStorage.setItem('tweaks', JSON.stringify(tweaks));
}, [tweaks]);
```

Also provide a "Reset" button on the panel that clears localStorage and restores defaults.

---

## Hide the panel when done

Offer a small "hide" button or a keyboard shortcut (e.g., press `.` to toggle) so the user can see the design clean. When hidden, the panel should go fully off-screen — not just collapsed to a tab — so screenshots look final.

You can also check a URL parameter like `?tweaks=0` to start with the panel hidden.

---

## Cycling through pre-set variants

If the user wants to try "4 different versions of the hero", you can offer a single "Version" cycler instead of 10 individual tweaks:

```
[ < ] Version 2 of 4: "Bold editorial" [ > ]
```

Each version is a named preset that sets multiple tweak values at once. This is especially useful when variants are coherent design directions, not individual dials.

---

## Keep the design final-looking when Tweaks are off

The most important rule: with the panel hidden, the design should look **finished, not stripped down**. Don't show placeholder labels where tweakable copy goes; don't show hit areas where a hover color is tweakable. Tweaks are an overlay on a polished design, not scaffolding holding it up.
