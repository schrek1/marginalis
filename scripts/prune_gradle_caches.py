"""Deletes the Gradle cache entries of one branch that a newer entry of the same kind has replaced.

Usage: python3 scripts/prune_gradle_caches.py REPO REF [--dry-run]

setup-gradle saves its entries under content hashes, so a push whose dependencies or transforms changed adds a new
1-1.5 GB entry and leaves the old one until GitHub evicts it: a few such pushes a day took the repository's cache to
its 10 GB limit. Per kind (the key without its trailing hash) the most recently used entry stays; it is the one the
next run restores. Other caches (the IDE layout index, pull requests' entries) are left alone.
"""
from __future__ import annotations

import json
import re
import subprocess
import sys

KIND = re.compile(r"^(gradle-.+)-[0-9a-f]{12,}$")


def gh(*args: str) -> str:
    return subprocess.run(["gh", *args], check=True, capture_output=True, text=True).stdout


def entries(repo: str, ref: str) -> list[dict]:
    found = []
    for page in range(1, 11):
        batch = json.loads(gh("api", f"repos/{repo}/actions/caches?per_page=100&page={page}&ref={ref}"))["actions_caches"]
        found += batch
        if len(batch) < 100:
            return found
    sys.exit("more than 1000 cache entries on one branch: refusing to judge an incomplete list")


def superseded(caches: list[dict]) -> list[dict]:
    kinds: dict[str, list[dict]] = {}
    for cache in caches:
        kind = KIND.match(cache["key"])
        if kind:
            kinds.setdefault(kind.group(1), []).append(cache)
    stale = []
    for same_kind in kinds.values():
        same_kind.sort(key=lambda cache: max(cache["last_accessed_at"], cache["created_at"]), reverse=True)
        stale += same_kind[1:]
    return stale


def main() -> None:
    args = [arg for arg in sys.argv[1:] if arg != "--dry-run"]
    if len(args) != 2:
        sys.exit(__doc__)
    repo, ref = args
    dry_run = "--dry-run" in sys.argv
    stale = superseded(entries(repo, ref))
    freed = sum(cache["size_in_bytes"] for cache in stale) / 1e9
    for cache in stale:
        print(f"{'would delete' if dry_run else 'deleting'} {cache['key']} ({cache['size_in_bytes'] / 1e6:.0f} MB, "
              f"last used {cache['last_accessed_at']})")
        if not dry_run:
            gh("api", "--method", "DELETE", f"repos/{repo}/actions/caches/{cache['id']}")
    print(f"{len(stale)} superseded entries, {freed:.2f} GB{' (dry run)' if dry_run else ' freed'}")


if __name__ == "__main__":
    main()
