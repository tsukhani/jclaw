---
name: visual-explainer
description: Generate rich HTML pages or slide decks for diagrams, diff reviews, plan audits, data tables, and project recaps
version: 1.0.1
author: main
tools: [filesystem, exec, documents]
commands: []
icon: 🖼️
---

# Visual Explainer

A skill that turns complex terminal output, diagrams, comparisons, and tables into styled HTML pages you actually want to read.

## What It Does

This skill generates beautiful, interactive HTML pages for:
- System architecture diagrams
- Code and diff reviews
- Plan reviews and audits
- Data tables and comparisons
- Project recaps and summaries
- Slide decks for presentations

Instead of ASCII art and monospace tables in your terminal, you get proper typography, dark/light themes, interactive Mermaid diagrams with zoom and pan, and responsive layouts.

## Commands

### General Commands

- **Generate Web Diagram** - Create an HTML diagram for any topic
- **Generate Visual Plan** - Create a visual implementation plan
- **Generate Slides** - Create a magazine-quality slide deck
- **Diff Review** - Visual diff review with architecture comparison
- **Plan Review** - Compare a plan against requirements with risk assessment
- **Project Recap** - Create a mental model snapshot for a project
- **Fact Check** - Verify accuracy of a document against actual code

### Slide Deck Mode

Any command can generate a slide deck instead of a scrollable page by adding a "--slides" parameter:
- "Generate a diagram of our authentication flow --slides"
- "Create a project recap with slides"

## Usage Instructions

1. Ask for a visual representation of any complex topic:
   - "Draw a diagram of our authentication flow"
   - "Create a visual diff review for these changes"
   - "Generate a project recap for our backend service"
   - "Make a visual comparison of these requirements vs our plan"

2. The skill will:
   - Analyze the content to determine the best visualization approach
   - Choose the appropriate rendering engine (Mermaid for flowcharts, HTML tables for data, etc.)
   - Generate a self-contained HTML file with all necessary styling and JavaScript
   - Save the file to the workspace and provide a download link

3. Features automatically applied:
   - Responsive layouts that work on any device
   - Dark/light theme support based on system preferences
   - Interactive elements (zoomable diagrams, collapsible sections)
   - Typography optimized for readability
   - Consistent styling across all visualizations

## Output Location

Outputs are written to `visual-explainer/` at the root of the agent's workspace (e.g., `visual-explainer/`).

## Visualization Types

The skill automatically selects the best visualization based on content:

| Content Type | Visualization | Library/Tech |
|--------------|---------------|-------------|
| Flowcharts & Diagrams | Interactive SVG | Mermaid.js |
| System Architecture | Grid Layout | CSS Grid + Custom SVG |
| Data Tables | Styled HTML Tables | HTML + CSS |
| Comparisons | Side-by-side Cards | Flexbox Layout |
| Metrics & Stats | Charts & Graphs | Chart.js |
| Slide Decks | Reveal.js Style | Custom Animation System |

## Examples

### Example 1: Architecture Diagram

**Request:** "Draw a diagram of our user authentication flow"

**Output:** An HTML page with an interactive Mermaid diagram showing the authentication flow, with proper styling and responsive design.

### Example 2: Plan Review

**Request:** "Review this implementation plan against our requirements"

**Output:** An HTML page with a side-by-side comparison of requirements vs. plan items, with color-coding for coverage and risk assessment.

### Example 3: Project Recap

**Request:** "Create a project recap for the last 2 weeks of work"

**Output:** An HTML page with sections for completed work, pending items, blockers, and next steps, with proper styling and interactive elements.

### Example 4: Slide Deck

**Request:** "Generate slides for our new feature presentation"

**Output:** An HTML slide deck with animated transitions, proper typography, and responsive design.

## HTML Structure

Every generated HTML file is self-contained with:
- All CSS inline in `<style>` tags
- All JavaScript inline in `<script>` tags
- No external dependencies (works offline)
- Responsive design that adapts to any screen
- Theme support that respects system preferences
- Accessibility features for screen readers

## Error Handling

If the input is too complex or ambiguous:
1. The skill will ask clarifying questions
2. Suggest simplifications if needed
3. Split complex visualizations into multiple connected views

If technical limitations prevent rendering:
1. The skill will explain the limitation
2. Suggest alternative approaches
3. Offer a simpler version if possible

## Best Practices

This skill follows these design principles:
- Clean, minimal design focused on content
- Consistent visual language across all outputs
- No unnecessary decorations or distractions
- Mobile-first responsive approach
- Dark mode support with proper contrast
- Semantic HTML for accessibility
