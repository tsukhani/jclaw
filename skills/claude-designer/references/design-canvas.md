# Design Canvas

Use this reference when presenting 2+ static visual variations side-by-side — color explorations, type treatments, layout alternatives, logo directions, a grid of component states.

---

## When to use a canvas

A design canvas is the right format when:

- You're exploring **visual** choices (color, type, static composition) — not flows or interactions
- The user needs to **compare options at a glance**, not click through them
- Each option is **self-contained** — a single rendered state, not a multi-screen flow

If the options have interactions or multiple states, build the full prototype and expose variants as toggles instead (see `tweaks.md`).

---

## Structure

Copy `assets/starters/design_canvas.jsx` into your project. It provides a labeled grid:

```jsx
<Canvas title="Button treatments">
  <Option label="Solid / High contrast">
    <button className="bg-black text-white px-6 py-3 rounded-full">Continue</button>
  </Option>
  <Option label="Outline / Minimal">
    <button className="border border-black px-6 py-3">Continue</button>
  </Option>
  <Option label="Filled / Branded">
    <button className="bg-indigo-600 text-white px-6 py-3 rounded-lg">Continue</button>
  </Option>
  <Option label="Pill / Soft">
    <button className="bg-indigo-100 text-indigo-900 px-6 py-3 rounded-full">Continue</button>
  </Option>
</Canvas>
```

Each `<Option>` is a labeled cell. The canvas lays them out in a responsive grid with enough space to see each cleanly.

---

## How many options

- **Minimum 3** — two options is a binary; three starts to map out a space
- **Typical 4–6** — enough variety to see patterns without overwhelming
- **Maximum ~9** — beyond this the canvas gets noisy; split into multiple canvases by theme

---

## Labeling

Labels should be **descriptive of the choice, not editorial**. Good:

- "Solid / High contrast"
- "Serif display + sans body"
- "Warm palette — terracotta + cream"

Bad:

- "Option 1"
- "My favorite"
- "The clean one"

---

## Spreading across dimensions

When exploring, don't give 4 near-identical options. Spread them across real choices:

- If exploring color: one monochrome, one warm, one cool, one high-contrast
- If exploring type: one serif-led, one sans-led, one display-heavy, one mono/editorial
- If exploring layout: one centered, one left-aligned, one asymmetric, one grid-based

Start conventional and get more adventurous as you go — the user can pull the brakes if the bold options are too much.

---

## Backgrounds

Canvas backgrounds are usually **neutral and quiet** (white, off-white, light gray) so the options pop. If an option needs a dark background to show correctly (e.g., a dark-mode variant), put the background on the Option's inner frame, not the whole canvas.

---

## When to ship the canvas vs explain the options

The canvas is the deliverable. Don't walk the user through each option in prose — the labels do that. Your summary afterward should be caveats and next steps only:

> "Laid out 5 button treatments. Options 3 and 5 both use the brand indigo — 3 is more conservative, 5 is the pill shape if you want more personality. Let me know which direction to refine."
