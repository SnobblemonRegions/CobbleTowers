#!/usr/bin/env python3
"""The player changelog ships in the jar and is shown in game, so it must match the build and stay simple to render.

src/main/resources/cobbletowers/CHANGELOG.md is read from the jar at cobbletowers/CHANGELOG.md. This check holds it to:

  - the first line is "# CobbleRaids changelog";
  - each release is "## <version> (YYYY-MM-DD)", newest first, versions unique, dates never going up the file;
  - the newest release is the version in build.gradle, so a version bump without a changelog entry fails;
  - each release has at least one "### New", "### Changed" or "### Fixed" section, each with at least one "- " line;
  - nothing else: no other headings, no backticks, bold or links, so an in-game renderer needs only these four line kinds.

With a jar path it also checks that the jar carries the file, byte for byte.
"""

import re
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "src/main/resources/cobbletowers/CHANGELOG.md"
JAR_ENTRY = "cobbletowers/CHANGELOG.md"
TITLE = "# CobbleTowers changelog"
RELEASE = re.compile(r"^## (\S+) \((\d{4}-\d{2}-\d{2})\)$")
SECTIONS = {"### New", "### Changed", "### Fixed"}
MARKUP = re.compile(r"`|\*\*|\]\(")


def build_version() -> str:
    for line in (ROOT / "build.gradle").read_text(encoding="utf-8").splitlines():
        match = re.match(r"^version = '(.*)'$", line)
        if match:
            return match.group(1)
    raise SystemExit("validate_changelog: no version line in build.gradle")


def problems(text: str, version: str) -> list[str]:
    out = []
    lines = text.splitlines()
    if not lines or lines[0] != TITLE:
        out.append(f"line 1 must be '{TITLE}'")
    releases = []          # (version, date, line number)
    bullets = {}           # release version -> {section: bullet count}
    section = None
    for number, line in enumerate(lines[1:], start=2):
        if not line.strip():
            continue
        release = RELEASE.match(line)
        if release:
            releases.append((release.group(1), release.group(2), number))
            bullets[release.group(1)] = {}
            section = None
        elif line in SECTIONS:
            if not releases:
                out.append(f"line {number}: '{line}' before any release heading")
                continue
            section = line
            bullets[releases[-1][0]].setdefault(section, 0)
        elif line.startswith("- ") and line[2:].strip():
            if section is None:
                out.append(f"line {number}: a bullet outside a New/Changed/Fixed section")
                continue
            if MARKUP.search(line):
                out.append(f"line {number}: no backticks, bold or links -- the in-game renderer shows plain text")
            bullets[releases[-1][0]][section] += 1
        else:
            out.append(f"line {number}: not a release heading, a New/Changed/Fixed heading or a '- ' bullet: {line!r}")

    if not releases:
        return out + ["no '## <version> (YYYY-MM-DD)' release headings"]
    if releases[0][0] != version:
        out.append(f"the newest release is {releases[0][0]}, but build.gradle is {version}: add a changelog entry for it")
    seen = set()
    for name, _, number in releases:
        if name in seen:
            out.append(f"line {number}: {name} is listed twice")
        seen.add(name)
    for (newer, newer_date, _), (older, older_date, number) in zip(releases, releases[1:]):
        if older_date > newer_date:
            out.append(f"line {number}: {older} ({older_date}) is dated after {newer} ({newer_date}) above it; newest first")
    for name, _, number in releases:
        sections = bullets[name]
        if not sections:
            out.append(f"line {number}: {name} has no New/Changed/Fixed section")
        for heading, count in sections.items():
            if count == 0:
                out.append(f"{name}: '{heading}' has no bullets")
    return out


def main() -> int:
    if not SOURCE.is_file():
        print(f"Changelog validation: FAIL -- {SOURCE.relative_to(ROOT)} is missing")
        return 1
    text = SOURCE.read_text(encoding="utf-8")
    found = problems(text, build_version())
    if len(sys.argv) > 1:
        jar = Path(sys.argv[1])
        with zipfile.ZipFile(jar) as archive:
            if JAR_ENTRY not in archive.namelist():
                found.append(f"{jar.name} has no {JAR_ENTRY}")
            elif archive.read(JAR_ENTRY) != SOURCE.read_bytes():
                found.append(f"{jar.name}'s {JAR_ENTRY} differs from the source file; rebuild")
    where = f" ({Path(sys.argv[1]).name})" if len(sys.argv) > 1 else ""
    if found:
        print(f"Changelog validation{where}: FAIL")
        for problem in found:
            print("  - " + problem)
        return 1
    releases = sum(1 for line in text.splitlines() if RELEASE.match(line))
    print(f"Changelog validation{where}: PASS -- {releases} releases, newest {build_version()}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
