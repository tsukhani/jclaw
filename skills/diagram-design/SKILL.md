---
name: diagram-design
description: Create technical and product diagrams — architecture, flowchart, sequence, state machine, ER / data model, timeline, swimlane, quadrant, nested, tree, layer stack, venn, pyramid — as standalone HTML files with inline SVG. Supports customizing colors and fonts to match your brand.
version: 1.0.0
author: main
tools: [filesystem, web_fetch, documents]
commands: []
---

# Diagram Design

Create visual diagrams as self-contained HTML files with inline SVG and CSS, following an opinionated editorial design system. Generate beautiful, clean diagrams with a consistent style that can be customized to match your brand.

## Thirteen Diagram Types

This skill supports creating the following diagram types:

1. **Architecture** — Components + connections in a system
2. **Flowchart** — Decision logic with branches
3. **Sequence** — Time-ordered messages between actors
4. **State Machine** — States + transitions + guards
5. **ER / Data Model** — Entities + fields + relationships
6. **Timeline** — Events positioned in time
7. **Swimlane** — Cross-functional process with handoffs
8. **Quadrant** — Two-axis positioning / prioritization
9. **Nested** — Hierarchy through containment / scope
10. **Tree** — Parent → children relationships
11. **Layer Stack** — Stacked abstraction levels
12. **Venn** — Overlap between sets
13. **Pyramid / Funnel** — Ranked hierarchy or conversion drop-off

## Design Philosophy

The design system follows these principles:
- **Confident restraint** — Earn every element
- **One color accent** — Reserved for 1-2 focal elements
- **Grid system** — Every coordinate divisible by 4
- **Clean typography** — Sans for node names, mono for technical details
- **No shadows** — Clean, minimal aesthetic with 1px hairline borders
- **Target density: 4/10** — Remove what doesn't add value

## Using The Skill

When the user asks to create a diagram, I will:

1. **Determine the appropriate diagram type** based on the user's needs
2. **Check if this is the first diagram** in the project to offer style customization
3. **Generate the HTML/SVG** using semantic tokens for colors and typography
4. **Apply the style guide** to maintain consistent visuals
5. **Run the output through quality checks** to ensure it meets the design standards
6. **Save the output** as a standalone HTML file

## Style Customization Options

The skill supports customizing the visual style to match the user's brand:

1. **URL-based onboarding** — Extract colors and fonts from the user's website
2. **Manual token setting** — User provides specific color/font values
3. **Default style** — Neutral stone + rust color scheme

## Output Location

Outputs are written to `workspace/main/diagram-design/`.

Each diagram is delivered as a standalone HTML file containing:
- Embedded CSS (no external dependencies except Google Fonts)
- Inline SVG (no external images)
- No JavaScript required

## Examples

To create a diagram:
- "Make me an architecture diagram of my app: frontend, backend, database, Redis cache."
- "I need a flowchart showing the user authentication process."
- "Create a sequence diagram for an API request flow."
- "Draw a quadrant showing my Q2 projects by impact vs. effort."

To customize the style:
- "Onboard diagram-design to match https://mywebsite.com"
- "Update the diagram style guide with my brand colors"