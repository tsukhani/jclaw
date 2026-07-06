#!/usr/bin/env python3
"""Per-turn attribution parity vs the human-arbitrated gold (JCLAW-651).
For each gold turn, find its words in the hypothesis (fuzzy: normalized
word-sequence matching) and check the MAJORITY of the matched words carry
the gold speaker. Reports per-turn verdicts and the parity fraction.

usage: python3 parity.py <hyp-entries.json> <gold-transcript.json>
"""
import json, re, sys

def norm(t): return [w for w in re.sub(r"[^\w\s']", " ", t.lower()).split() if w]

hyp = json.load(open(sys.argv[1]))
gold = json.load(open(sys.argv[2]))["turns"]
# flat hypothesis word list with speakers
hw = []
for e in sorted(hyp, key=lambda e: e["start"]):
    for w in norm(e["text"]):
        hw.append((w, e["speaker"]))
ok = 0; total = 0; misses = []
cursor = 0
for gi, g in enumerate(gold):
    gw = norm(g["text"])
    if len(gw) < 2: continue
    # find best alignment of gw in hw at/after a sliding window near cursor
    best = (-1, -1)  # (score, pos)
    for pos in range(max(0, cursor - 30), min(len(hw), cursor + 120)):
        score = sum(1 for k in range(min(len(gw), len(hw) - pos)) if hw[pos + k][0] == gw[k])
        if score > best[0]: best = (score, pos)
    score, pos = best
    matched = min(len(gw), len(hw) - pos)
    if score < max(2, matched * 0.4):
        misses.append((gi, g["speaker"], g["text"][:40], "NOT-FOUND"))
        continue
    cursor = pos + matched
    votes = {}
    for k in range(matched):
        if hw[pos + k][0] == gw[k]:
            votes[hw[pos + k][1]] = votes.get(hw[pos + k][1], 0) + 1
    winner = max(votes, key=votes.get)
    total += 1
    if winner == g["speaker"]:
        ok += 1
    else:
        misses.append((gi, g["speaker"], g["text"][:40], f"got {winner}"))
print(f"attribution parity: {ok}/{total} gold turns")
for m in misses:
    print("  MISS turn %d gold=%s '%s' %s" % m)
