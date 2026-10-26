# Interactive Prototypes

Use this reference when the deliverable is a clickable, hi-fi prototype — a mocked product with real interactions, navigation between screens, and state. Not a slide deck, not a static mockup.

---

## Framing

Prototypes are almost always React + inline Babel in a single HTML file. This lets you use real component composition, state, and event handling without a build step, while staying portable (the file opens anywhere).

See `react-setup.md` for the pinned CDN setup and scope-sharing gotchas.

---

## Device frames

Most prototypes need a device frame. Copy the matching starter into your project:

- `assets/starters/ios_frame.jsx` — iOS status bar + Dynamic Island + home indicator
- `assets/starters/android_frame.jsx` — Android status bar + nav bar + keyboard
- `assets/starters/macos_window.jsx` — macOS window chrome with traffic lights
- `assets/starters/browser_window.jsx` — Browser chrome with tab bar and URL

Load via `<script type="text/babel" src="ios_frame.jsx"></script>`, then compose inside your main component.

**Center the prototype in the viewport** with reasonable margins. Don't add a "title" screen around it — that's wasted chrome. If the user wants annotation, put it next to the device, not wrapping it.

---

## Starting without a design system

If the user has no codebase, UI kit, or brand, this is a warning sign. **Ask them for one before starting.** Options to offer:

- Their own Figma / codebase / live site URL
- A screenshot of the existing product
- A UI kit you'll commit to (shadcn/ui conventions, iOS HIG, Material)
- "Design a visual system from scratch" — last resort; invoke `frontend-design.md` if going this route

Document which path you're taking in a comment at the top of the file.

---

## Component composition

Split the prototype into files when it grows past ~500 lines:

```
Onboarding Prototype.html       <- main file, wires everything together
components/
  WelcomeScreen.jsx
  SignupForm.jsx
  OnboardingCarousel.jsx
  HomeFeed.jsx
shared/
  Button.jsx
  Input.jsx
```

Load them in order from the main file. Remember: each `<script type="text/babel">` gets its own scope. To share components, assign to `window` at the end of each component file:

```jsx
Object.assign(window, { WelcomeScreen, SignupForm });
```

Then reference `WelcomeScreen`, etc., from the main file.

---

## State and navigation

For most prototypes, `useState` + a `screen` state variable is enough:

```jsx
const [screen, setScreen] = useState('welcome');
const [formData, setFormData] = useState({ name: '', email: '' });

return screen === 'welcome' ? <Welcome onNext={() => setScreen('form')} />
     : screen === 'form'    ? <Form data={formData} onSubmit={...} />
     : screen === 'success' ? <Success />
     : null;
```

For more complex flows, use `useReducer` or a small finite-state-machine pattern. Don't reach for a routing library — prototypes don't need routes.

**Persist flow position to localStorage** so the user can reload mid-iteration without losing their place. A single `useEffect` syncing state to localStorage is enough.

---

## Interactions and animation

- **CSS transitions** handle most interactions — hover states, button presses, focus rings, modal slide-ins.
- **React state** with conditional classes handles screen transitions.
- **For richer motion**, see `animated-video.md` — the animation starter can be used for interactive timelines too.
- **Popmotion** (`https://unpkg.com/popmotion@11.0.5/dist/popmotion.min.js`) is a fallback when you need spring physics or complex tweens.

Keep interactions feeling real:

- Button presses should have a fast (80–120ms) tactile response
- Transitions between screens should be slower (250–400ms) with proper easing
- Loading states should exist — a button that submits instantly looks fake

---

## Tweaks for exploring variants

When the user asks for variations of a prototype, add them as **in-page toggles** rather than creating separate files. See `tweaks.md` for the full pattern.

Common things to expose as tweaks:

- Alternative copy for key CTAs
- Color palette swaps
- Layout variants (single column vs split / stacked vs side-by-side)
- Feature flags (with / without onboarding skip, with / without social auth)
- Density (compact vs comfortable)

---

## Common mistakes

**Adding a wrapper "title" page.** The prototype IS the content. Don't wrap it in a "Your Prototype: Onboarding Flow" header.

**Hand-drawing icons as SVG.** Use a real icon library. If you must use SVG placeholders, make them obvious shapes (a solid circle, a square), not badly-drawn real icons.

**Inventing copy that sounds like AI.** "Unlock the power of...", "Seamlessly integrated...", "Revolutionary approach to..." If you're writing placeholder copy, write boringly-real copy (e.g. actual error messages, actual form labels) not marketing speak.

**Hit targets smaller than 44px on mobile.** Physical thumbs exist. Respect them.

**Building the whole thing before showing the user.** Get a skeleton up fast, show it, then iterate. The user's feedback in the first 10 minutes is worth more than two hours of solo polishing.

---

## Using Claude from prototypes (if your environment supports it)

Some host environments expose a `window.claude.complete(...)` helper that lets prototypes call an LLM directly, no API key needed. Check your host's docs. If available, it's a good way to make AI-powered features feel real in demos without wiring up auth.

```js
const result = await window.claude.complete("Summarize this feedback: ...");
```

If your environment doesn't have this, fall back to mocking the LLM response with a hardcoded string + setTimeout to simulate latency.
