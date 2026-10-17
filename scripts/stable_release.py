"""The fork's automatic stable release (the Stable release workflow): whether a commit on schreko_stable releases, and
what its fork issues and PRs get told afterwards.

Usage:
  python3 scripts/stable_release.py plan SHA [--force]
      prints `release=true|false`, `version`, `previous`, `reason` lines for $GITHUB_OUTPUT
  python3 scripts/stable_release.py follow-up SHA --outcome success|skipped|failure|cancelled
                                              [--version V] [--reason R] [--run-url URL] [--dry-run]
      comments on and labels the fork issues of the merges since the previous release, and tells the PR whose merge
      started the run what came of it; `--dry-run` prints the actions instead (needs `gh` logged in either way)
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys

REPO = "schrek1/marginalis"
STABLE_TAG = re.compile(r"(?P<upstream>\d+\.\d+\.\d+)-schreko\.(?P<n>\d+)")
# What the plugin zip is built from; anything else (docs, sandbox, CI, tests) ships with the next plugin release.
PLUGIN_INPUTS = ["src/main", "core/src/main", "*.gradle.kts", "gradle.properties", "gradle/", "CHANGELOG.md"]
STAGE_LABELS = {"in-progress", "waiting-for-dev-testing", "in-dev-testing", "request-demo", "waiting-for-gate-testing",
                "in-gate-testing", "request-merge", "refine", "needs-decision", "blocked"}
IN_STABLE = "in-stable"
PENDING = "pending"


def git(*args: str, check: bool = True) -> str:
    result = subprocess.run(["git", *args], text=True, capture_output=True, encoding="utf-8")
    if check and result.returncode != 0:
        sys.exit(f"git {' '.join(args)} failed: {result.stderr}")
    return result.stdout.strip()


def gh(*args: str) -> str:
    return subprocess.run(["gh", *args], text=True, capture_output=True, encoding="utf-8", check=True).stdout


def stable_tags() -> list[str]:
    tags = [tag for tag in git("tag", "-l", "*-schreko.*").split() if STABLE_TAG.fullmatch(tag)]
    return sorted(tags, key=lambda tag: int(STABLE_TAG.fullmatch(tag)["n"]))


def plan(sha: str, force: bool) -> dict[str, str]:
    tags = stable_tags()
    previous = tags[-1] if tags else ""
    released = [tag for tag in git("tag", "--points-at", sha).split() if STABLE_TAG.fullmatch(tag)]
    if released:
        return {"release": "false", "previous": previous, "reason": f"already released as {released[0]}"}
    if previous and subprocess.run(["git", "merge-base", "--is-ancestor", previous, sha]).returncode != 0:
        return {"release": "false", "previous": previous, "reason": f"not the newest merge: {previous} is not in its history"}
    changed = git("diff", "--name-only", f"{previous}..{sha}", "--", *PLUGIN_INPUTS) if previous else "first release"
    if not changed and not force:
        return {"release": "false", "previous": previous, "reason": "no plugin change"}
    upstream = re.search(r'^version = "([^"]+)"', git("show", f"{sha}:build.gradle.kts"), flags=re.M)
    if not upstream:
        sys.exit(f"{sha}: build.gradle.kts has no `version = \"...\"` line")
    n = int(STABLE_TAG.fullmatch(previous)["n"]) + 1 if previous else 1
    return {"release": "true", "version": f"{upstream.group(1)}-schreko.{n}", "previous": previous,
            "reason": "plugin change" if changed else "forced"}


def merged_prs(previous: str, sha: str) -> list[int]:
    """The fork PRs whose merges are on schreko_stable's first-parent line since `previous`, oldest first."""
    since = f"{previous}..{sha}" if previous else sha
    numbers = []
    for subject in reversed(git("log", "--first-parent", "--format=%s", since).splitlines()):
        found = re.search(r"\(#(\d+)\)$", subject) or re.match(r"Merge pull request #(\d+) ", subject)
        if found:
            numbers.append(int(found.group(1)))
    return numbers


def fork_issues(pr: dict) -> list[int]:
    """The issues a PR names on its `Fork issue(s):` line (one PR may carry several), else the `MARG-N:` of its title."""
    line = re.search(r"^Fork issues?: (.+)$", pr.get("body") or "", flags=re.M)
    if line:
        return [int(number) for number in re.findall(r"MARG-\d+ \(#(\d+)\)", line.group(1))]
    title = re.match(r"MARG-(\d+): ", pr["title"])
    return [int(title.group(1))] if title else []


class Actions:
    def __init__(self, dry_run: bool):
        self.dry_run = dry_run

    def run(self, description: str, *args: str) -> None:
        print(("would " if self.dry_run else "") + description)
        if not self.dry_run:
            gh(*args)


def follow_up(sha: str, outcome: str, version: str, reason: str, run_url: str, dry_run: bool) -> None:
    actions = Actions(dry_run)
    tags = [tag for tag in stable_tags() if tag != version]
    previous = tags[-1] if tags else ""
    prs = merged_prs(previous, sha)
    short = sha[:7]
    if outcome == "success":
        release_url = f"https://github.com/{REPO}/releases/tag/{version}"
        in_stable = (f"In `schreko_stable` since **{version}** (tag `{version}`, commit `{short}`); "
                     f"CI green: build, e2e, verify. {release_url}")
        told = f"Released as **{version}**: {release_url}"
    elif outcome == "skipped" and reason == "no plugin change":
        in_stable = f"In `schreko_stable` since commit `{short}` (no plugin change, no release)."
        told = (f"No release: nothing the plugin is built from changed since `{previous}`; "
                "this ships with the next plugin release.")
    else:
        in_stable = None
        told = {"skipped": f"No release: {reason}.",
                "cancelled": "No release: a newer merge took over this release run.",
                }.get(outcome, f"The release failed: {run_url}. Nothing was tagged; the fix goes forward.")
    if in_stable:
        for number in prs:
            for issue in fork_issues(json.loads(gh("pr", "view", str(number), "--repo", REPO, "--json", "title,body"))):
                tell_issue(actions, issue, in_stable)
    if prs:
        pr = prs[-1]
        actions.run(f"comment on PR {pr}: {told}", "pr", "comment", str(pr), "--repo", REPO, "--body", told)


def tell_issue(actions: Actions, number: int, in_stable: str) -> None:
    issue = json.loads(gh("issue", "view", str(number), "--repo", REPO, "--json", "state,labels"))
    labels = {label["name"] for label in issue["labels"]}
    if IN_STABLE in labels:
        print(f"issue {number} already carries {IN_STABLE}")
        return
    actions.run(f"comment on issue {number}: {in_stable}", "issue", "comment", str(number), "--repo", REPO, "--body", in_stable)
    edit = ["issue", "edit", str(number), "--repo", REPO, "--add-label", IN_STABLE]
    for stale in sorted(labels & STAGE_LABELS):
        edit += ["--remove-label", stale]
    actions.run(f"label issue {number}: +{IN_STABLE} -{sorted(labels & STAGE_LABELS)}", *edit)
    if PENDING in labels:
        print(f"issue {number} stays open: {PENDING}")
    elif issue["state"] == "OPEN":
        actions.run(f"close issue {number}", "issue", "close", str(number), "--repo", REPO, "--reason", "completed")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)
    planning = sub.add_parser("plan")
    planning.add_argument("sha")
    planning.add_argument("--force", action="store_true")
    following = sub.add_parser("follow-up")
    following.add_argument("sha")
    following.add_argument("--outcome", required=True, choices=["success", "skipped", "failure", "cancelled"])
    following.add_argument("--version", default="")
    following.add_argument("--reason", default="")
    following.add_argument("--run-url", default="")
    following.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    if args.command == "plan":
        for key, value in plan(args.sha, args.force).items():
            print(f"{key}={value}")
    else:
        follow_up(args.sha, args.outcome, args.version, args.reason, args.run_url, args.dry_run)


if __name__ == "__main__":
    main()
