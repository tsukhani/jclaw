# /// script
# requires-python = ">=3.11"
# dependencies = ["huggingface_hub"]
# ///
"""Lightweight HF provisioning helper for the diarize sidecar: cache-status
checks and one-shot downloads in a MINIMAL env (just huggingface_hub), so
serve.py never spawns the heavy pyannote/SER workers (torch + pyannote /
transformers) merely to check or fetch weights. HF_HOME is inherited from the
daemon, so this reads/writes the same data/diarize-models cache.

usage:
  uv run hf_prefetch.py --status <repo> [<repo>...]  -> {"status": {repo: {...}}}
  uv run hf_prefetch.py --prefetch <repo>            -> {"ok": true} | {"error": ...}
"""
import json
import os
import sys


def _repo_bytes(repo):
    from huggingface_hub.constants import HF_HUB_CACHE
    d = os.path.join(HF_HUB_CACHE, "models--" + repo.replace("/", "--"))
    total = 0
    for root, _, files in os.walk(d):
        for f in files:
            fp = os.path.join(root, f)
            try:
                if not os.path.islink(fp):
                    total += os.path.getsize(fp)
            except OSError:
                pass
    return total


def _status(repo):
    from huggingface_hub import snapshot_download
    try:
        snapshot_download(repo, local_files_only=True)
        cached = True
    except Exception:  # noqa: BLE001 — any miss means not fully cached
        cached = False
    return {"repo": repo, "cached": cached, "bytesOnDisk": _repo_bytes(repo)}


def main():
    if len(sys.argv) < 3:
        sys.stderr.write("usage: hf_prefetch.py --status <repo>... | --prefetch <repo>\n")
        return 2
    op = sys.argv[1]
    if op == "--status":
        print(json.dumps({"status": {r: _status(r) for r in sys.argv[2:]}}), flush=True)
        return 0
    if op == "--prefetch":
        from huggingface_hub import snapshot_download
        try:
            snapshot_download(sys.argv[2])
            print(json.dumps({"ok": True}), flush=True)
            return 0
        except Exception as e:  # noqa: BLE001 — reported; nonzero exit signals failure
            print(json.dumps({"error": str(e)}), flush=True)
            return 1
    sys.stderr.write("unknown op %r\n" % op)
    return 2


if __name__ == "__main__":
    sys.exit(main())
