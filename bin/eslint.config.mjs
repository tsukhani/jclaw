// ESLint for bin/'s Node scripts (JF-446). frontend/ lints only itself, so this config loads ESLint's recommended
// rules and the Node globals from frontend's own dependencies.
import { createRequire } from "node:module";

const frontend = createRequire(new URL("../frontend/package.json", import.meta.url));
const js = frontend("@eslint/js");
const globals = frontend("globals");

export default [
  {
    files: ["bin/**/*.mjs"],
    ...js.configs.recommended,
    languageOptions: { ecmaVersion: "latest", sourceType: "module", globals: globals.node },
    rules: {
      ...js.configs.recommended.rules,
      // A rest sibling such as `{ SKIP: _, ...env }` names what it leaves out of env.
      "no-unused-vars": ["error", { ignoreRestSiblings: true }],
    },
  },
];
