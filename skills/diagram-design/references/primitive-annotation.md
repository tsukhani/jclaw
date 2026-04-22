# Annotation Callout Primitive

Editorial callouts provide context, insights, or explanations around diagram elements. They use italic serif text with dashed leaders to connect to the relevant part of the diagram.

## When to Use

Use annotation callouts when you want to:
- Explain the significance of a particular component
- Highlight a design decision
- Note constraints or trade-offs
- Draw attention to a specific interaction
- Provide editorial commentary on the diagram

Limit to 1-2 callouts per diagram for maximum impact.

## Visual Style

- **Text**: Italic Instrument Serif, 14px, #1c1917
- **Leader line**: Dashed path (stroke-dasharray="3,3"), #52534e
- **Position**: In the margins or open spaces of the diagram
- **Max width**: 200px (40-50 characters)

## SVG Template

```html
<!-- Annotation callout -->
<g class="annotation">
  <!-- Dashed leader path - customize the path coordinates -->
  <path d="M400,160 C460,140 480,120 500,100" fill="none" stroke="#52534e" stroke-width="1" stroke-dasharray="3,3"/>
  
  <!-- Text container - position this at the end of the path -->
  <foreignObject x="500" y="60" width="200" height="100">
    <div xmlns="http://www.w3.org/1999/xhtml" style="font-family:'Instrument Serif', serif; font-style:italic; font-size:14px; color:#1c1917; line-height:1.4;">
      This component handles all authentication requests and maintains user sessions.
    </div>
  </foreignObject>
</g>
```

## Implementation Notes

1. **Leader path**: Use a curved Bézier path (`C` command) to create a natural-looking leader line. The path should start at the element being annotated and curve toward the text.

2. **Text positioning**: Place the text in open space, away from diagram elements. Ensure it doesn't overlap with other components or annotations.

3. **foreignObject**: Using foreignObject allows for proper text wrapping and styling. For cross-browser compatibility, set explicit width and height.

4. **Placement options**:
   - Top margin
   - Bottom margin
   - Left/right margins
   - Open spaces between components

## Examples

### Top-Right Annotation

```html
<g class="annotation">
  <path d="M240,120 C280,80 320,60 360,60" fill="none" stroke="#52534e" stroke-width="1" stroke-dasharray="3,3"/>
  <foreignObject x="360" y="40" width="200" height="100">
    <div xmlns="http://www.w3.org/1999/xhtml" style="font-family:'Instrument Serif', serif; font-style:italic; font-size:14px; color:#1c1917; line-height:1.4;">
      The frontend uses React with TypeScript for type safety and better developer experience.
    </div>
  </foreignObject>
</g>
```

### Bottom-Left Annotation

```html
<g class="annotation">
  <path d="M320,320 C260,360 220,380 180,400" fill="none" stroke="#52534e" stroke-width="1" stroke-dasharray="3,3"/>
  <foreignObject x="80" y="380" width="200" height="100">
    <div xmlns="http://www.w3.org/1999/xhtml" style="font-family:'Instrument Serif', serif; font-style:italic; font-size:14px; color:#1c1917; line-height:1.4;">
      PostgreSQL was chosen for its robust transaction support and JSON capabilities.
    </div>
  </foreignObject>
</g>
```

## Anti-patterns

- Don't use more than 2 annotations per diagram
- Don't place annotations that overlap with diagram elements
- Don't use annotations for information that should be in the diagram itself
- Don't use non-italic text for annotations (italic serif is the visual signal)
- Don't use straight lines for leaders (curves are more elegant)

## Annotation as Counterpoint

The most effective annotations provide a counterpoint to the diagram - they don't just describe what's visible, but add insight, context, or editorial perspective. Use them to express information that doesn't fit in the diagram's visual grammar but adds valuable context.