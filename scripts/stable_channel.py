"""The stable channel's plugin repository for a `<upstream>-schreko.N` tag: updatePlugins.xml for GitHub Pages and
the GitHub Release's notes.

The IDE reads updatePlugins.xml as a custom plugin repository. Its update dialog shows the entry's change notes as
What's New (the fork tickets new since the previous stable tag, from STABLE.md rows) and its description as the
Overview (every fork feature in STABLE.md).

Usage:  python3 scripts/stable_channel.py TAG REPO OUT_DIR
        writes OUT_DIR/updatePlugins.xml, OUT_DIR/index.html and OUT_DIR/release-notes.md
"""
from __future__ import annotations

import html
import re
import subprocess
import sys
from pathlib import Path

PLUGIN_ID = "dev.marginalis.plugin"
SINCE_BUILD = "252"
TAG = re.compile(r"^(?P<upstream>\d+\.\d+\.\d+)-schreko\.(?P<n>\d+)$")
STABLE_ROW = re.compile(r"^\| (MARG-\d+) \| ([^|]+?) \|", re.M)


def git(*args: str) -> str:
    return subprocess.run(["git", *args], check=True, text=True, capture_output=True, encoding="utf-8").stdout.strip()


def stable_features(ref: str) -> list[tuple[str, str]]:
    """(MARG key, feature title) per STABLE.md row as of `ref`; a ticket shipped in parts has a row per part."""
    shown = subprocess.run(["git", "show", f"{ref}:STABLE.md"], capture_output=True, text=True, encoding="utf-8")
    return STABLE_ROW.findall(shown.stdout) if shown.returncode == 0 else []


def previous_tag(tag: str) -> str | None:
    found = subprocess.run(["git", "describe", "--tags", "--abbrev=0", "--match", "*-schreko.*", f"{tag}^"],
                           capture_output=True, text=True)
    return found.stdout.strip() or None


def news(tag: str, previous: str | None) -> tuple[list[str], list[str]]:
    """The fork features the release adds, and the other first-parent commits it brings, by subject."""
    before = set(stable_features(previous)) if previous else set()
    added = [f"{key} {title}" for key, title in stable_features(tag) if (key, title) not in before]
    since = f"{previous}..{tag}" if previous else tag
    # A feature lands with its STABLE.md row, so it is listed by that row, not again by its commit subject.
    feature_commits = set(git("log", "--first-parent", "--format=%H", since, "--", "STABLE.md").split())
    others = [subject for sha, subject in
              (line.split(" ", 1) for line in git("log", "--first-parent", "--format=%H %s", since).splitlines())
              if sha not in feature_commits]
    return added, others


def html_list(items: list[str]) -> str:
    return "<ul>" + "".join(f"<li>{html.escape(item)}</li>" for item in items) + "</ul>"


def change_notes(previous: str | None, added: list[str], others: list[str]) -> str:
    notes = f"<p>New since {html.escape(previous)}:</p>" if previous else "<p>New:</p>"
    notes += html_list(added) if added else "<p>No new fork features.</p>"
    return notes + ("<p>Also:</p>" + html_list(others) if others else "")


def overview(tag: str) -> str:
    return ("<p>Ondra's everyday Marginalis: upstream plus the fork's features.</p>"
            + html_list([f"{key} {title}" for key, title in stable_features(tag)]))


def release_notes(previous: str | None, added: list[str], others: list[str]) -> str:
    lines = [f"New since `{previous}`:" if previous else "New:", ""]
    lines += [f"- {item}" for item in added] or ["- No new fork features."]
    if others:
        lines += ["", "Also:", ""] + [f"- {item}" for item in others]
    return "\n".join(lines) + "\n"


def repository(tag: str, zip_url: str, notes: str, description: str) -> str:
    return ("<plugins>\n"
            f'  <plugin id="{PLUGIN_ID}" url="{html.escape(zip_url)}" version="{tag}">\n'
            f'    <idea-version since-build="{SINCE_BUILD}"/>\n'
            "    <name>Marginalis</name>\n"
            f"    <description><![CDATA[{description}]]></description>\n"
            f"    <change-notes><![CDATA[{notes}]]></change-notes>\n"
            "  </plugin>\n"
            "</plugins>\n")


def index(tag: str, repo: str) -> str:
    return ("<!doctype html><meta charset=utf-8><title>Marginalis stable channel</title>\n"
            f"<p>Marginalis stable channel, currently <b>{html.escape(tag)}</b>.</p>\n"
            "<p>Add this page's <code>updatePlugins.xml</code> in Settings &rarr; Plugins &rarr; &#9881; &rarr; "
            "Manage Plugin Repositories.</p>\n"
            f'<p><a href="https://github.com/{repo}/releases/tag/{tag}">Release {html.escape(tag)}</a></p>\n')


def main() -> None:
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    tag, repo, out_dir = sys.argv[1], sys.argv[2], Path(sys.argv[3])
    parsed = TAG.match(tag)
    if not parsed:
        sys.exit(f"{tag}: not a stable tag `<upstream>-schreko.N`")
    upstream = re.search(r'^version = "([^"]+)"', git("show", f"{tag}:build.gradle.kts"), flags=re.M)
    if not upstream or upstream.group(1) != parsed["upstream"]:
        sys.exit(f"{tag}: the tagged tree's build.gradle.kts has version {upstream and upstream.group(1)}, "
                 f"not {parsed['upstream']}")
    previous = previous_tag(tag)
    added, others = news(tag, previous)
    zip_url = f"https://github.com/{repo}/releases/download/{tag}/marginalis-{tag}.zip"
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "updatePlugins.xml").write_text(
        repository(tag, zip_url, change_notes(previous, added, others), overview(tag)), encoding="utf-8")
    (out_dir / "index.html").write_text(index(tag, repo), encoding="utf-8")
    (out_dir / "release-notes.md").write_text(release_notes(previous, added, others), encoding="utf-8")
    print(f"{tag}: {len(added)} new fork features, {len(others)} other commits since {previous or 'the start'}")


if __name__ == "__main__":
    main()
