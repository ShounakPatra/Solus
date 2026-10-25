"""Reject release uploads whose successful main CI run built a different commit."""
import argparse
import json
from pathlib import Path
import re


def validate(run, tag_sha):
    expected = ("completed", "success", "push", "main", "Android CI")
    actual = tuple(run.get(key) for key in ("status", "conclusion", "event", "headBranch", "workflowName"))
    if actual != expected:
        raise ValueError("Select a successful Android CI push run from main")
    source_sha = run.get("headSha", "")
    tag_sha = tag_sha.strip()
    if not all(isinstance(sha, str) and re.fullmatch(r"[0-9a-fA-F]{40}", sha) for sha in (source_sha, tag_sha)):
        raise ValueError("Source run and release tag must resolve to full commit SHAs")
    if source_sha.lower() != tag_sha.lower():
        raise ValueError("Release tag does not match the CI source commit; select the matching run or tag")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-json", required=True)
    parser.add_argument("--tag-sha-file", required=True)
    args = parser.parse_args()
    validate(json.loads(Path(args.run_json).read_text(encoding="utf-8")),
             Path(args.tag_sha_file).read_text(encoding="utf-8"))
