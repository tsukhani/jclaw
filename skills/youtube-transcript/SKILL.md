---
name: youtube-transcript
description: >-
  Retrieve the transcript text from a YouTube video given a URL or video ID.
metadata:
  version: "1.0.4"
  author: main
  jclaw-icon: "📝"
  jclaw-tools: exec filesystem
  jclaw-commands: ""
---

# YouTube Transcript

When asked to retrieve a transcript from a YouTube video:

1. **Extract the video ID**
   - If the user provides a full URL (e.g. `https://www.youtube.com/watch?v=VIDEO_ID` or `https://youtu.be/VIDEO_ID`), parse out the 11-character video ID.
   - If the user provides just the raw video ID, use it directly.

2. **Fetch the transcript**
   - Use the `exec` tool to run a one-liner Python script that calls the `youtube_transcript_api` library:
     ```python3 -c "from youtube_transcript_api import YouTubeTranscriptApi; api=YouTubeTranscriptApi(); t=api.fetch('VIDEO_ID'); print('TOTAL:{}'.format(len(t))); [print('{:06.2f}|{}'.format(s.start, s.text)) for s in t]"```
     - The output is a series of lines: `TOTAL:508` followed by entries like `72.50|Hello world`
   - If it fails with `ModuleNotFoundError`, the library is missing: run `python3 -m pip install "youtube-transcript-api==1.2.4" --quiet` once with `exec`, then run the fetch again. Install only then, and only this version, which is the one the skill is tested with.
   - If the library raises any other error (e.g. `TranscriptsDisabled`, `NoTranscriptFound`, or connectivity issues), report the error text concisely and stop.

3. **Parse the response**
   - Split the output by lines.
   - Skip the `TOTAL:` line (used only for verification).
   - For each remaining line: split on the first `|` — the left side is the timestamp in seconds (float), the right side is the caption text. Decode HTML entities on the caption text (e.g. `&amp;` → `&`, `&quot;` → `"`, `&#39;` → `'`, `&lt;` → `<`, `&gt;` → `>`).
   - Format each timestamp as `[MM:SS]` from the float seconds (e.g. `72.50` → `[01:12]`).
   - Combine each caption into a single line: `[MM:SS] {decoded_text}`.
   - Join all lines with a newline into the final transcript string.

4. **Deliver the result**
   - If the combined transcript exceeds 4000 characters, save it to a file:
     - Create the directory `youtube-transcript/` at the root of the workspace if it doesn't exist.
     - Write the content to `youtube-transcript/{VIDEO_ID}-transcript.txt` using the `filesystem` tool.
     - Report back with a markdown file link: `[{VIDEO_ID}-transcript.txt](<youtube-transcript/{VIDEO_ID}-transcript.txt>)`
   - If the transcript is short, return it directly in chat wrapped in a markdown code block for readability.

5. **Error handling**
   - If the library or transcript fetch fails, report the error text concisely.
   - If the video has no captions/transcript available, report this plainly to the user.
   - Be concise and factual when reporting errors.

**Output Location:** If this skill generates files, they are written to `youtube-transcript/` at the root of the agent's workspace (e.g., `youtube-transcript/abc123-transcript.txt`).