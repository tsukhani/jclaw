// node --test bin/comment-review.test.mjs

import { deepStrictEqual, strictEqual, throws } from "node:assert";
import { test } from "node:test";

import { addedComments, parseVerdicts, prompt, rubric } from "./comment-review.mjs";

const DIFF = `diff --git a/app/services/Printers.java b/app/services/Printers.java
index 1111111..2222222 100644
--- a/app/services/Printers.java
+++ b/app/services/Printers.java
@@ -10,4 +10,9 @@ public class Printers {
     void sweep() {
-        // old note
+        // Loop through the discovered printers
+        // and send each a job
         for (var p : printers) {
+            send(p); // Canon rejects text/plain outright
+            var url = "http://printer.local/ipp";
         }
\\ No newline at end of file
diff --git a/jclaw.sh b/jclaw.sh
--- a/jclaw.sh
+++ b/jclaw.sh
@@ -1,2 +1,3 @@
 #!/usr/bin/env bash
+# macOS ships bash 3.2, so no associative arrays
 set -euo pipefail
diff --git a/frontend/types/api-contract.generated.ts b/frontend/types/api-contract.generated.ts
--- a/frontend/types/api-contract.generated.ts
+++ b/frontend/types/api-contract.generated.ts
@@ -1,1 +1,2 @@
+/** Format: int32 */
 export type paths = Record<string, never>;
`;

test("added comments are found with their new line numbers, consecutive lines joined", () => {
  const found = addedComments(DIFF).map(({ hunk, ...c }) => c);
  deepStrictEqual(found, [
    { id: 1, file: "app/services/Printers.java", line: 11, text: "// Loop through the discovered printers\n// and send each a job" },
    { id: 2, file: "app/services/Printers.java", line: 14, text: "// Canon rejects text/plain outright" },
    { id: 3, file: "jclaw.sh", line: 2, text: "# macOS ships bash 3.2, so no associative arrays" },
  ]);
});

test("a URL in a string, a removed comment, a shebang and a generated file are not comments to review", () => {
  const texts = addedComments(DIFF).map((c) => c.text).join("\n");
  strictEqual(texts.includes("printer.local"), false);
  strictEqual(texts.includes("old note"), false);
  strictEqual(texts.includes("#!/usr/bin/env"), false);
  strictEqual(texts.includes("Format: int32"), false);
});

test("the rubric is AGENTS.md section 8 and nothing after it", () => {
  const md = "### 7. Reach\nx\n\n### 8. Comments Carry Reasoning\nthe delete test\n\n### 9. Commit Messages\ny";
  strictEqual(rubric(md), "### 8. Comments Carry Reasoning\nthe delete test");
  throws(() => rubric("### 7. only"), /no section 8/);
});

test("the prompt carries the rule, each comment's hunk once and every comment by id", () => {
  const comments = addedComments(DIFF);
  const text = prompt("RULE", comments);
  strictEqual(text.includes("<rule>\nRULE\n</rule>"), true);
  strictEqual(text.split("@@ -10,4 +10,9 @@").length - 1, 1);
  strictEqual(text.includes("[3] jclaw.sh:2"), true);
});

test("the verdicts are the last <verdicts> block of the answer", () => {
  const answer = 'Thinking about <verdicts>[]</verdicts> first.\n<verdicts>[{"id":1,"verdict":"cut","rule":"restates","why":"the loop says it"}]</verdicts>';
  deepStrictEqual(parseVerdicts(answer), [{ id: 1, verdict: "cut", rule: "restates", why: "the loop says it" }]);
  throws(() => parseVerdicts("no block here"), /no <verdicts> block/);
});
