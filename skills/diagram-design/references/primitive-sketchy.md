# Sketchy Primitive

The sketchy primitive applies a hand-drawn effect to diagrams using SVG filters. It's perfect for less technical contexts, creative presentations, or exploratory ideas where a polished look might feel too rigid.

## When to Use

Use the sketchy variant when:
- Creating diagrams for essays, blog posts, or creative contexts
- Presenting early-stage or exploratory ideas
- You want to convey a more approachable, human feel
- The content is conceptual rather than strictly technical

Avoid using sketchy style for:
- Technical documentation
- System specifications
- Formal presentations
- Precise engineering diagrams

## Implementation Technique

The sketchy effect is created using SVG filters:
1. **Turbulence** - Creates random noise
2. **Displacement map** - Uses the noise to displace the original shapes
3. **Filter application** - Applied to stroke and fills separately

## SVG Filter Definition

Add this to the `<defs>` section of your SVG:

```html
<filter id="sketchy" x="-10%" y="-10%" width="120%" height="120%">
  <!-- Turbulence noise -->
  <feTurbulence type="fractalNoise" baseFrequency="0.04" numOctaves="1" result="noise" seed="123"/>
  
  <!-- Displacement map for wobble effect -->
  <feDisplacementMap in="SourceGraphic" in2="noise" scale="4" xChannelSelector="R" yChannelSelector="G"/>
</filter>

<!-- Less intense version for fills -->
<filter id="sketchy-fill" x="-10%" y="-10%" width="120%" height="120%">
  <feTurbulence type="fractalNoise" baseFrequency="0.04" numOctaves="1" result="noise" seed="123"/>
  <feDisplacementMap in="SourceGraphic" in2="noise" scale="2" xChannelSelector="R" yChannelSelector="G"/>
</filter>
```

## Application

To apply the sketchy effect, add the filter to SVG elements:

```html
<!-- For strokes (lines, borders) -->
<rect x="80" y="120" width="160" height="80" rx="6" 
      fill="#ffffff" stroke="#1c1917" stroke-width="1.2" 
      filter="url(#sketchy)"/>

<!-- For filled areas (use the less intense version) -->
<rect x="80" y="120" width="160" height="80" rx="6" 
      fill="#fbeae6" stroke="none"
      filter="url(#sketchy-fill)"/>

<!-- For text (usually not filtered to maintain readability) -->
<text x="160" y="162" fill="#1c1917" font-size="12" font-weight="600" 
      font-family="'Geist', sans-serif" text-anchor="middle">
  Component Name
</text>
```

## Sketchy Style Adjustments

When using the sketchy primitive, make these style adjustments:

1. **Increase stroke width** - Use stroke-width="1.2" instead of 1px
2. **Increase font weight** - Consider 500 or 600 weight for better visibility
3. **Add margin to the SVG viewBox** - Add 20px in each direction
4. **Avoid precise alignment** - Embrace slight misalignment for authenticity
5. **Don't filter text** - Keep text clean for readability

## Complete Example

Here's how to modify a standard architecture diagram to use the sketchy style:

```html
<svg width="100%" height="480" viewBox="0 0 800 480" xmlns="http://www.w3.org/2000/svg">
  <defs>
    <!-- Standard arrow markers -->
    <marker id="arrow" markerWidth="8" markerHeight="6" refX="7" refY="3" orient="auto">
      <polygon points="0 0, 8 3, 0 6" fill="#52534e"/>
    </marker>
    
    <!-- Sketchy filters -->
    <filter id="sketchy" x="-10%" y="-10%" width="120%" height="120%">
      <feTurbulence type="fractalNoise" baseFrequency="0.04" numOctaves="1" result="noise" seed="123"/>
      <feDisplacementMap in="SourceGraphic" in2="noise" scale="4" xChannelSelector="R" yChannelSelector="G"/>
    </filter>
    
    <filter id="sketchy-fill" x="-10%" y="-10%" width="120%" height="120%">
      <feTurbulence type="fractalNoise" baseFrequency="0.04" numOctaves="1" result="noise" seed="123"/>
      <feDisplacementMap in="SourceGraphic" in2="noise" scale="2" xChannelSelector="R" yChannelSelector="G"/>
    </filter>
  </defs>
  
  <!-- Background -->
  <rect width="100%" height="100%" fill="#faf7f2"/>
  
  <!-- Connections (draw first, apply sketchy filter) -->
  <line x1="240" y1="160" x2="320" y2="160" stroke="#52534e" stroke-width="1.2" 
        marker-end="url(#arrow)" filter="url(#sketchy)"/>
        
  <!-- Components -->
  <!-- Component fill (with light filter) -->
  <rect x="80" y="120" width="160" height="80" rx="6" 
        fill="#fbeae6" stroke="none" filter="url(#sketchy-fill)"/>
  
  <!-- Component border (with stronger filter) -->
  <rect x="80" y="120" width="160" height="80" rx="6" 
        fill="none" stroke="#b5523a" stroke-width="1.2" filter="url(#sketchy)"/>
  
  <!-- Text (no filter) -->
  <text x="160" y="162" fill="#1c1917" font-size="12" font-weight="600" 
        font-family="'Geist', sans-serif" text-anchor="middle">
    Component Name
  </text>
  
  <!-- Continue with other components... -->
</svg>
```

## Hand-Drawn vs Sketchy

There are two common approaches to creating a hand-drawn look:

1. **Sketchy filter** (this primitive) - Uses SVG filters for a wobble effect
2. **Hand-drawn assets** - Uses pre-drawn SVG paths that look hand-sketched

This primitive uses the filter approach because:
- It can be applied to any diagram type
- It's more maintainable (change one filter, affects everything)
- It's more dynamic (can adjust intensity)
- It doesn't require separate asset files

## Alternative Filter Variations

For different sketchy styles:

### More Intense Wobble
```html
<filter id="sketchy-intense">
  <feTurbulence type="fractalNoise" baseFrequency="0.05" numOctaves="2" result="noise" seed="123"/>
  <feDisplacementMap in="SourceGraphic" in2="noise" scale="6" xChannelSelector="R" yChannelSelector="G"/>
</filter>
```

### Pencil-Like Effect
```html
<filter id="sketchy-pencil">
  <feTurbulence type="turbulence" baseFrequency="0.08" numOctaves="3" result="noise" seed="123"/>
  <feDisplacementMap in="SourceGraphic" in2="noise" scale="3" xChannelSelector="R" yChannelSelector="G"/>
  <feGaussianBlur stdDeviation="0.3" result="blur"/>
  <feComposite in="SourceGraphic" in2="blur" operator="arithmetic" k1="1" k2="0" k3="0" k4="0"/>
</filter>
```

## Diagram Title Style

For sketchy diagrams, consider using a hand-drawn style font for the title:

```html
<link href="https://fonts.googleapis.com/css2?family=Caveat:wght@400;600&display=swap" rel="stylesheet">

<style>
  .sketchy-title {
    font-family: 'Caveat', cursive;
    font-size: 2rem;
    font-weight: 600;
  }
</style>
```

This completes the hand-drawn aesthetic while maintaining the readability and structure of the diagram.
