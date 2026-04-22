# Onboarding Flow

This document describes how to onboard a new website's branding to the diagram design system.

## URL-to-Tokens Flow

When the user wants to onboard the skill to their website:

1. **Fetch the website** using the `web_fetch` tool
2. **Extract the dominant palette** and font stack from the HTML/CSS
3. **Map detected values** to semantic roles:
   - paper (background)
   - ink (primary text)
   - muted (secondary text)
   - paper-2 (cards/containers)
   - accent (brand color)
   - title font family
   - node-name font family
   - sublabel font family

4. **Perform contrast checks** to ensure WCAG AA compliance for small text
   - Verify contrast of ink over paper (primary text over background)
   - Verify contrast of muted over paper (secondary text over background)
   - If any fails, propose adjusted values

5. **Show a proposed diff** of what will change in the style guide
6. **Write the updated tokens** to `references/style-guide.md` with user approval

## Color Extraction Logic

1. Extract `<body>` background color → paper token
2. Extract primary text color (most common text color) → ink token
3. Extract secondary/caption text color → muted token
4. Extract card/container background → paper-2 token
5. Extract most prominent brand color (buttons, headings, links) → accent token

## Typography Extraction Logic

1. Extract `<h1>` font family → title font
2. Extract `<body>` font family → node-name font
3. Extract `<code>/<pre>` font family → sublabel font

If any font family isn't found, keep the default.

## Manual Override Option

If URL extraction doesn't work or the user wants to manually specify values:

1. Accept the user's provided color/font values
2. Map them to the appropriate semantic tokens
3. Update `references/style-guide.md` accordingly

## Example Dialog

**User:** "Onboard diagram-design to https://mycompany.com"

**Assistant:** 
1. Fetches the site
2. Extracts colors and fonts
3. Shows proposal:
   ```
   Found these values:
   - Background: #ffffff → paper
   - Text: #333333 → ink
   - Secondary text: #666666 → muted
   - Brand color: #0055ff → accent
   - Heading font: "Montserrat" → title font
   - Body font: "Open Sans" → node-name font
   ```
4. Asks for confirmation
5. Updates style-guide.md

## First-Run Gate

The first time a user asks for a diagram in a project, check if the style guide has been customized:

1. Read `references/style-guide.md`
2. Check if the accent color differs from the default (#b5523a)
3. If still default, pause and offer:
   - "This is your first diagram in this project. The style guide is still at the default. Want to run onboarding, paste tokens manually, or proceed with default?"