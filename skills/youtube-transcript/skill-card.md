## Description:

Fetches YouTube video transcripts by video ID or URL and returns transcript text, timestamps, title, and author for summarization.

This skill is ready for commercial/non-commercial use.

## Publisher:

[xthezealot](https://clawhub.ai/user/xthezealot)

### License/Terms of Use:


## Use Case:

Developers, agents, and external users use this skill to retrieve transcript text and metadata from YouTube videos for summarization, transcription, or content extraction workflows. It is intended for environments where the user intentionally configures residential VPN routing for transcript access.

### Deployment Geography for Use:

Global

## Known Risks and Mitigations:

Risk: The skill may change host-level WireGuard and routing settings when bringing up the configured VPN interface.

Mitigation: Run it only in an isolated environment where residential VPN routing is intentionally preconfigured and host networking changes are acceptable.

Risk: Requested video IDs may be visible to noembed.com when the skill fetches title and author metadata.

Mitigation: Treat noembed.com as an additional metadata recipient and avoid using the skill for videos whose IDs should not be shared with that service.

Risk: Unpinned Python dependencies can change behavior across installs.

Mitigation: Pin and review dependencies before deployment, then run the skill as an unprivileged user.

## Reference(s):

- [YouTube Transcript Setup Guide](references/SETUP.md)
- [ClawHub Skill Page](https://clawhub.ai/xthezealot/skills/youtube-transcript)

## Skill Output:

**Output Type(s):** [Text, JSON, Shell commands, Configuration guidance]

**Output Format:** [Markdown instructions with shell commands; transcript output is JSON.]

**Output Parameters:** [1D]

**Other Properties Related to Output:** [JSON output includes video ID, title, author, selected language, entry count, full transcript text, and timestamped transcript entries when available.]

## Skill Version(s):

1.0.1 (source: ClawHub release metadata)

## Ethical Considerations:

Users should evaluate whether this skill is appropriate for their environment, review any generated or modified files before relying on them, and apply their organization's safety, security, and compliance requirements before deployment.
