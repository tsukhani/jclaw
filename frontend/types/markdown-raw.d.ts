// Vite's `?raw` query returns a string at build time. Nuxt 3 lets us
// import any file with `?raw` to inline its contents into the bundle —
// the User Guide uses this to ship its .md sources from `docs/user-guide`.
declare module '*.md?raw' {
  const content: string
  export default content
}
