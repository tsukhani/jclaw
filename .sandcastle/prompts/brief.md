Write the brief a human reviewer will read before merging this branch. Do not change files and do not run commands.

The harness posts this brief on the ticket as its review comment, `decisions` included. So a criterion that asks for a decision to be made or recorded on the ticket is met once that decision, with its reason, is in `decisions`: say so in its evidence, and do not list copying it to the ticket as a risk.

You are writing this only because the harness has already run the full `play autotest` suite on this exact branch head
and found no new failures. A criterion that the suite (or `play autotest`) is green is therefore met: cite the harness
gate as its evidence, and do not mark it unmet or list it as a risk because you did not run the suite yourself.

Emit exactly one <brief> block as the last thing in your response, containing JSON of this shape:

<brief>
{
  "summary": "two or three sentences: what changed and why",
  "acceptanceCriteria": [{ "criterion": "text", "met": true, "evidence": "file, test or commit that shows it" }],
  "decisions": ["each decision the ticket asked to be made or recorded, with its reason"],
  "risks": ["anything the reviewer should check by hand"],
  "testsRun": ["test classes or commands run during implementation and review"]
}
</brief>
