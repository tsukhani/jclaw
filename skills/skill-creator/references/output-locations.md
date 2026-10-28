# Output file locations

Skills that generate files (HTML diagrams, courses, reports, etc.) MUST store output in a directory matching the skill name at the root of the agent's workspace:

```
{skill-name}/           # e.g., visual-explainer/
└── {output-files}      # e.g., black-hole-guide.html
```

### Rules

1. **Destination:** All output files go to `{skill-name}/` at the root of the agent's workspace (e.g., `visual-explainer/`)
2. **Create if missing:** Use `filesystem` tool to create the directory if it doesn't exist
3. **Filenames:** Use descriptive, kebab-case filenames (e.g., `black-hole-guide.html`)
4. **No .agent directory:** Do NOT put skill outputs in `.agent/` or other hidden directories
5. **Delivery:** After writing the file, provide a markdown link: `[filename](<relative/path>)` so the user can download it

### Example

A visual-explainer skill generating an architecture diagram:
```markdown
1. Create directory `visual-explainer/` at the root of the workspace if it doesn't exist
2. Write the HTML file to `visual-explainer/jclaw-architecture.html`
3. Report: `[jclaw-architecture.html](visual-explainer/jclaw-architecture.html)`
```

