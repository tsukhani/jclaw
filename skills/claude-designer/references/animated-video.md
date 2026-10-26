# Animated Video

Use this reference when the deliverable is a timeline-based motion design piece: an explainer video, a product demo animation, a title sequence, an animated concept.

---

## Core architecture

Animated videos use a Stage + Sprite + scrubber pattern. Copy `assets/starters/animations.jsx` into your project — it provides:

- `<Stage>` — auto-scaling container with scrubber, play/pause, duration
- `<Sprite start end>` — child element that's only rendered (or faded in/out) during a time range
- `useTime()` / `useSprite()` hooks — access current time in seconds, and the active sprite's normalized progress
- `Easing` — ease-in/out/cubic/bezier helpers
- `interpolate(t, [fromT, toT], [fromV, toV], easing?)` — core tweening primitive
- Entry/exit primitives — fade, slide, scale-in

Basic usage:

```jsx
<Stage width={1920} height={1080} duration={10}>
  <Sprite start={0} end={3}>
    <h1>Title</h1>
  </Sprite>
  <Sprite start={2} end={8}>
    <ProductShot />
  </Sprite>
  <Sprite start={7} end={10}>
    <CTA />
  </Sprite>
</Stage>
```

Inside a Sprite, use `useSprite()` to get `{ progress }` (0→1 over the sprite's lifetime) and `useTime()` for global time.

---

## When to fall back to Popmotion

The `animations.jsx` starter handles 90% of cases. Reach for Popmotion (`https://unpkg.com/popmotion@11.0.5/dist/popmotion.min.js`) only when you need:

- Spring physics
- Gesture-driven animation (drag, pinch)
- Very fine-grained keyframe control

For most explainer-style videos, the starter is enough.

---

## Composition principles

**Structure in beats.** A good explainer video has 4–8 beats, each 1–3 seconds. Don't let any single idea sit on screen for more than ~4 seconds without change.

**Enter, hold, exit.** Every element appears with a clear entry (slide, fade, scale), holds for its message, and exits. Abrupt cuts work but should be intentional.

**Stagger related elements.** When multiple items appear together, stagger their entries by 100–200ms. Everything appearing simultaneously looks mechanical.

**Use easing with purpose.** `ease-out` for entries (feels natural, quick start slow end). `ease-in` for exits. `ease-in-out` for moves. Linear only for things that genuinely move at constant speed.

**Motion amplifies hierarchy.** The thing moving draws the eye. If everything is moving at once, nothing is.

---

## Timing references

Rough durations to calibrate by:

- **Micro-interaction** (button press): 80–150ms
- **UI transition** (modal, screen change): 250–400ms
- **Scene beat** (one idea on screen): 1.5–3s
- **Full explainer**: 30–60s typical, 90s max

If a video is over 60 seconds, check whether it should be split into multiple shorter pieces.

---

## Layout and scale

Default canvas: 1920×1080. The Stage component letterboxes into any viewport.

Text sizes for video should be larger than you think — people watch on phones. Minimum 48px for any important text. Titles typically 120–200px.

---

## No titles on the page itself

Don't put "My Animated Video" as an h1 above the Stage. The Stage IS the deliverable. Let it be centered in the viewport.

---

## Exporting

Most environments let users screen-record or save the HTML. If the user needs an MP4, point them to screen recording (OS-level, or QuickTime / OBS) from the fullscreen playback. Don't try to render frames to video inside the browser — it's unreliable.
