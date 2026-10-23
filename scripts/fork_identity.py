"""Gives a checkout the fork's plugin identity: id `dev.marginalis.plugin.schreko`, name "Marginalis (schreko)".

The fork's builds share upstream's source, and with it the plugin id. The IDE then offers upstream's Marketplace release
as an update over a fork build, and one click replaces the fork. CI's release step rewrites the checkout before it
builds the release zip (and e2e its harness on that run), so the stable channel ships a plugin of its own; the tree in
git and every other build keep upstream's id.

Every quoted id literal is rewritten: plugin.xml and build.gradle.kts declare the id, and the code and the tests look the
plugin up by it (the unload listener ignores any other plugin's unload). Package names never match, a dot follows them.
It fails when a declaration it expects is gone, so an upstream change to them stops CI instead of shipping upstream's id.

Usage:  python3 scripts/fork_identity.py [ROOT]
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

UPSTREAM_ID = "dev.marginalis.plugin"
PLUGIN_ID = "dev.marginalis.plugin.schreko"
PLUGIN_NAME = "Marginalis (schreko)"

SOURCES = ["build.gradle.kts", "src", "sandbox"]
SUFFIXES = {".kt", ".kts", ".java", ".xml", ".py"}
ID_LITERAL = re.compile(r"""(["'])dev\.marginalis\.plugin\1""")
# Each must match exactly once, or the declaration moved and the rewrite would miss it.
DECLARATIONS = {
    "build.gradle.kts": [
        (re.compile(r'^(\s+id = )"dev\.marginalis\.plugin"', re.M), rf'\1"{PLUGIN_ID}"'),
        (re.compile(r'^(\s+name = )"Marginalis"', re.M), rf'\1"{PLUGIN_NAME}"'),
    ],
    "src/main/resources/META-INF/plugin.xml": [
        (re.compile(r"<id>dev\.marginalis\.plugin</id>"), f"<id>{PLUGIN_ID}</id>"),
        (re.compile(r"<name>Marginalis</name>"), f"<name>{PLUGIN_NAME}</name>"),
    ],
}


def apply(root: Path) -> list[str]:
    """Rewrites the checkout under `root`; returns the changed files, relative to it."""
    changed = []
    for path in sorted(source_files(root)):
        relative = path.relative_to(root).as_posix()
        text = path.read_bytes().decode("utf-8")
        rewritten = text
        for pattern, replacement in DECLARATIONS.get(relative, []):
            rewritten, count = pattern.subn(replacement, rewritten)
            if count != 1:
                sys.exit(f"{relative}: expected one match of {pattern.pattern}, found {count}")
        rewritten = ID_LITERAL.sub(lambda quoted: f"{quoted[1]}{PLUGIN_ID}{quoted[1]}", rewritten)
        if rewritten != text:
            path.write_bytes(rewritten.encode("utf-8"))
            changed.append(relative)
    missing = [declared for declared in DECLARATIONS if declared not in changed]
    if missing:
        sys.exit(f"not found: {', '.join(missing)}")
    return changed


def source_files(root: Path):
    for source in SOURCES:
        path = root / source
        if path.is_file():
            yield path
        elif path.is_dir():
            yield from (found for found in path.rglob("*") if found.is_file() and found.suffix in SUFFIXES)


if __name__ == "__main__":
    for file in apply(Path(sys.argv[1] if len(sys.argv) > 1 else ".")):
        print(f"{PLUGIN_ID}: {file}")
