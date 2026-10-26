## Description:

Claude Designer helps agents produce polished visual design artifacts such as slide decks, interactive prototypes, hi-fi mockups, animated videos, landing pages, dashboards, and marketing one-pagers using HTML, CSS, JavaScript, and SVG.

This skill is ready for commercial/non-commercial use.

## Publisher:

[cced3000](https://clawhub.ai/user/cced3000)

### License/Terms of Use:

MIT-0

## Use Case:

Developers, designers, and other agent users use this skill to turn design requests, product context, screenshots, brand systems, or PRDs into polished visual artifacts for review and iteration.

### Deployment Geography for Use:

Global

## Known Risks and Mitigations:

Risk: Generated prototypes may include third-party CDN scripts.

Mitigation: Review generated HTML before sharing or opening it with realistic data, and pin or remove remote scripts where appropriate.

Risk: Prototype state may be stored in localStorage.

Mitigation: Avoid sensitive data in prototype flows that persist state locally, and clear localStorage before sharing demos.

Risk: Browser-side LLM helpers can send prototype text to a host AI service.

Mitigation: Use browser-side AI helpers only when users understand what text may be sent, or replace them with mocked responses.

## Reference(s):

- [ClawHub skill page](https://clawhub.ai/cced3000/skills/claude-designer)
- [README](README.md)
- [Slide Decks](references/decks.md)
- [Interactive Prototypes](references/prototypes.md)
- [Animated Video](references/animated-video.md)
- [Design Canvas](references/design-canvas.md)
- [Frontend Design](references/frontend-design.md)
- [React + Babel Setup](references/react-setup.md)
- [Question Banks](references/questions.md)
- [Tweaks](references/tweaks.md)

## Skill Output:

**Output Type(s):** [text, markdown, code, shell commands, configuration, guidance]

**Output Format:** [Markdown guidance and generated HTML/CSS/JavaScript/SVG files or snippets]

**Output Parameters:** [1D]

**Other Properties Related to Output:** [May include browser-openable visual artifacts, copied starter components, and concise verification notes.]

## Skill Version(s):

1.0.0 (source: server release evidence)

## Ethical Considerations:

Users should evaluate whether this skill is appropriate for their environment, review any generated or modified files before relying on them, and apply their organization's safety, security, and compliance requirements before deployment.
