# React + Babel Setup (for inline JSX prototypes)

This reference documents the exact setup to use for HTML files that include React components via inline Babel. Deviations cause subtle failures, so follow the pinned versions below.

---

## Pinned CDN imports

Use these exact script tags with integrity hashes. Do not use unpinned versions (`react@18`) or omit integrity:

```html
<script src="https://unpkg.com/react@18.3.1/umd/react.development.js" integrity="sha384-hD6/rw4ppMLGNu3tX5cjIb+uRZ7UkRJ6BPkLpg4hAu/6onKUg4lLsHAs9EBPT82L" crossorigin="anonymous"></script>
<script src="https://unpkg.com/react-dom@18.3.1/umd/react-dom.development.js" integrity="sha384-u6aeetuaXnQ38mYT8rp6sbXaQe3NL9t+IBXmnYxwkUI2Hw4bsp2Wvmx4yRQF1uAm" crossorigin="anonymous"></script>
<script src="https://unpkg.com/@babel/standalone@7.29.0/babel.min.js" integrity="sha384-m08KidiNqLdpJqLq95G/LEi8Qvjl/xUYll3QILypMoQ65QorJ9Lvtp2RXYGBFj1y" crossorigin="anonymous"></script>
```

Then load your component files as Babel scripts:

```html
<script type="text/babel" src="components/Button.jsx"></script>
<script type="text/babel" src="components/Hero.jsx"></script>
<script type="text/babel" src="main.jsx"></script>
```

**Do NOT use `type="module"` on Babel script imports.** It breaks the scoping model and leads to subtle errors.

---

## Boilerplate HTML shell

```html
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Prototype</title>
  <!-- any fonts / CSS -->
  <style>
    body { margin: 0; font-family: system-ui, sans-serif; }
    #root { min-height: 100vh; }
  </style>
</head>
<body>
  <div id="root"></div>

  <!-- React + Babel -->
  <script src="https://unpkg.com/react@18.3.1/umd/react.development.js" integrity="sha384-hD6/rw4ppMLGNu3tX5cjIb+uRZ7UkRJ6BPkLpg4hAu/6onKUg4lLsHAs9EBPT82L" crossorigin="anonymous"></script>
  <script src="https://unpkg.com/react-dom@18.3.1/umd/react-dom.development.js" integrity="sha384-u6aeetuaXnQ38mYT8rp6sbXaQe3NL9t+IBXmnYxwkUI2Hw4bsp2Wvmx4yRQF1uAm" crossorigin="anonymous"></script>
  <script src="https://unpkg.com/@babel/standalone@7.29.0/babel.min.js" integrity="sha384-m08KidiNqLdpJqLq95G/LEi8Qvjl/xUYll3QILypMoQ65QorJ9Lvtp2RXYGBFj1y" crossorigin="anonymous"></script>

  <!-- Component files, loaded in dependency order -->
  <script type="text/babel" src="components/Button.jsx"></script>
  <script type="text/babel" src="components/Hero.jsx"></script>

  <!-- Main entry point last -->
  <script type="text/babel">
    const { useState } = React;

    function App() {
      return <Hero />;
    }

    ReactDOM.createRoot(document.getElementById('root')).render(<App />);
  </script>
</body>
</html>
```

---

## CRITICAL: Name your style objects

**Never write `const styles = { ... }` at module scope.**

Each `<script type="text/babel">` gets its own scope when transpiled, but top-level `const` declarations become properties of a shared object. If two files both declare `const styles = { ... }`, they collide and break silently — the component that loads second overwrites the first.

**Instead, give every styles object a specific name based on its component:**

```jsx
// components/Button.jsx
const buttonStyles = {
  base: { padding: '8px 16px', borderRadius: 6 },
  primary: { background: 'black', color: 'white' },
};

function Button({ variant, children }) {
  return <button style={{ ...buttonStyles.base, ...buttonStyles[variant] }}>{children}</button>;
}
```

```jsx
// components/Hero.jsx
const heroStyles = {
  container: { padding: '80px 20px', textAlign: 'center' },
  title: { fontSize: 64, fontWeight: 700 },
};
```

Or use inline styles. But **never** use a generic `styles` name.

---

## CRITICAL: Sharing components between files

Each `<script type="text/babel">` gets its own scope. Components defined in one file are NOT visible in another by default.

To share components across files, attach them to `window` at the end of each component file:

```jsx
// components/Button.jsx
function Button({ children }) {
  return <button>{children}</button>;
}

function IconButton({ icon, children }) {
  return <button>{icon} {children}</button>;
}

// At the bottom of the file:
Object.assign(window, { Button, IconButton });
```

Then reference them directly (`Button`, `IconButton`) from any subsequent Babel script. Do NOT import them — they're now globals.

Load order matters: a file that uses `Button` must load AFTER `Button.jsx`.

---

## Hooks are on the global `React`

Destructure hooks at the top of each component file:

```jsx
const { useState, useEffect, useMemo, useRef } = React;
```

Or reference them directly as `React.useState` etc.

---

## Third-party libraries

Some UMD-available libraries work directly:

```html
<script src="https://unpkg.com/popmotion@11.0.5/dist/popmotion.min.js"></script>
<script src="https://unpkg.com/lucide@latest"></script>
<script src="https://cdn.tailwindcss.com"></script>
```

Avoid pulling libraries that are ES-module-only — they won't work without a bundler.

---

## Debugging tips

- Open the browser console. Babel errors show up there with line numbers.
- If a component "doesn't exist", check: (1) is it exported to window? (2) does the file loading it come AFTER the component's file?
- If styles look broken, check for `const styles = ...` collisions.
- If you see "Hooks can only be called inside a function component", you're probably calling `useState` at module scope.
