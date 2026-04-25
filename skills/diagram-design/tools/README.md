# Diagram Design Tools

This skill doesn't require any binary tools. It generates diagrams as standalone HTML/SVG files using the agent's built-in tools.

## Implementation Details

The diagram-design skill uses:
- `filesystem` tool to read/write files
- `web_fetch` tool for onboarding (extracting colors/fonts from websites)
- `documents` tool for rendering HTML files

All diagrams are generated as self-contained HTML files with inline SVG and embedded CSS, requiring no external dependencies except Google Fonts.

## Output Location

Generated diagrams are stored in the workspace under:
```
workspace/main/diagram-design/
```

Each file is a complete standalone HTML page that can be opened directly in any modern browser.
