#!/usr/bin/env bash
#
# The whole CI sequence, runnable on a developer machine.
#
# .git/hooks/pre-push runs it (install with `bash validation/hooks/install.sh`), and
# `bash validation/ci_local.sh` runs it by hand. Keep it in step with
# .github/workflows/build.yml.
#
# GitHub Actions was disabled account-wide while this repo was started, which is why the whole
# workflow is duplicated here. Actions came back on 2026-09-17, so this is now the pre-push gate
# rather than the only one -- but it stays, because it is faster than a push and because the first
# step below is one the workflow structurally cannot perform.
set -euo pipefail

project_root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$project_root"

if command -v python3 >/dev/null 2>&1; then
  py=python3
elif command -v python >/dev/null 2>&1; then
  py=python
else
  echo "ci_local: no python interpreter on PATH" >&2
  exit 1
fi

step() { printf '\n\033[1m== %s\033[0m\n' "$1"; }

# Said up front rather than left to Gradle, whose "Could not resolve" is easy to misread as a
# network problem.
cobbleraids_version="$(sed -n 's/^cobbleraids_version=//p' gradle.properties)"
m2="${HOME}/.m2/repository/com/cobbleraids/cobbleraids/${cobbleraids_version}"
if [ ! -d "$m2" ]; then
  echo "ci_local: CobbleRaids ${cobbleraids_version} is not in Maven Local (${m2})." >&2
  echo "ci_local: run 'gradlew publishToMavenLocal' in SnobblemonRaids at that version first." >&2
  exit 1
fi

step "Check git can see every source file"
# A .gitignore pattern without a leading slash matches a directory of that name at ANY depth. The
# entry meant for the dev server's run/ directory also matched src/main/java/com/cobbletowers/run,
# hiding the entire run-state package -- while the build stayed green, because Gradle compiles what
# is on disk and does not care what git can see. A fresh clone would not have had the files.
#
# The GitHub workflow cannot catch this: it builds a clone, where an ignored file simply is not
# there. This check only ever bites here, before the push that would lose the file.
hidden="$(git ls-files --others --ignored --exclude-standard -- src/ validation/   | grep -Ev '(^|/)__pycache__/|[.]pyc$' || true)"
if [ -n "$hidden" ]; then
  echo "ci_local: git ignores these source files, so a commit would silently leave them behind:" >&2
  echo "$hidden" | sed 's/^/  /' >&2
  echo "ci_local: check .gitignore for an unanchored pattern; anchor it with a leading slash." >&2
  exit 1
fi

step "Check the committed arenas still match their schematics"
# The .schem sources are committed beside the converter so this is reproducible. It catches the
# thing nobody would otherwise notice: a schematic re-exported without re-running the conversion,
# leaving the jar shipping the previous building.
for schematic in validation/schematics/*.schem; do
  name="$(basename "$schematic" .schem)"
  # The Battle Tower is one schematic recoloured into four structures; retheme_schem.py checks those below.
  [ "$name" = "battle_tower" ] && continue
  # The Hollow's Eve generator writes hollow_tower.schem; its structure is committed as battle_tower_halloween.nbt.
  structure="$name"
  [ "$name" = "hollow_tower" ] && structure="battle_tower_halloween"
  "$py" validation/schem_to_structure.py --check "$schematic"     "src/main/resources/data/cobbletowers/structure/${structure}.nbt"
done
"$py" validation/retheme_schem.py --check
# BlockArchitect-style builds ship as .mcfunction (setblock lists), with an optional <name>.options.json for lighting.
for build in validation/schematics/*.mcfunction; do
  name="$(basename "$build" .mcfunction)"
  "$py" validation/mcfunction_to_structure.py --check "$build" "src/main/resources/data/cobbletowers/structure/${name}.nbt"
done

step "Validate the bundled tower definitions"
# Before the build: this reads the shipped JSON, and a dangling reference in our own content should
# fail here rather than be skipped at runtime by the registry's malformed-file safety net.
"$py" validation/validate_definitions.py

step "Validate the geo armor models"
# A broken geo or a missing texture renders nothing for a player with Armor Model API, and nothing else notices.
"$py" validation/validate_armor_geo.py

step "Check the committed web UI bundle is current"
# The jar ships src/main/resources/web/cobbletowers, built from web/. An edit to web/src without a rebuild would ship the old page.
if command -v node >/dev/null 2>&1 && [ -d web/node_modules ]; then
  web_tmp="$(mktemp -d)"
  WEB_OUT="$web_tmp/cobbletowers" node web/build.mjs >/dev/null
  # Compared with what is committed (the index), not the working copy: with core.autocrlf git rewrites a checked-out file with
  # CRLF, which would differ from a fresh build though the commit is right.
  stale=""
  built="$(cd "$web_tmp/cobbletowers" && find . -type f | sed 's#^\./##' | sort)"
  for rel in $built; do
    want="$(git rev-parse ":src/main/resources/web/cobbletowers/$rel" 2>/dev/null || echo missing)"
    have="$(git hash-object "$web_tmp/cobbletowers/$rel")"
    [ "$want" = "$have" ] || stale="$stale $rel"
  done
  for rel in $(git ls-files src/main/resources/web/cobbletowers | sed 's#^src/main/resources/web/cobbletowers/##'); do
    [ -f "$web_tmp/cobbletowers/$rel" ] || stale="$stale $rel(not built any more)"
  done
  rm -rf "$web_tmp"
  if [ -n "$stale" ]; then
    echo "ci_local: src/main/resources/web/cobbletowers is stale; run 'node build.mjs' in web/ and commit the result. Differs:$stale" >&2
    exit 1
  fi
  # The workflow runs `npm test` here too; it builds the dev bundle into web/dist-dev, never the committed one.
  step "Run the web UI tests"
  (cd web && npm test)
else
  echo "ci_local: node or web/node_modules is missing; skipping the web bundle check (run 'npm install' in web/)."
fi

step "Compile, test and remap"
./gradlew clean build --stacktrace --warning-mode all

# After the build on purpose: the check reads compiled bytecode.
step "Validate architecture boundary (bytecode)"
"$py" validation/validate_architecture.py

version="$(sed -n "s/^version = '\(.*\)'$/\1/p" build.gradle)"
jar="build/libs/CobbleTowers-${version}.jar"
if [ ! -f "$jar" ]; then
  echo "ci_local: missing $jar" >&2
  ls -la build/libs >&2 || true
  exit 1
fi

step "Validate public API boundary (bytecode)"
"$py" validation/validate_api_boundary.py

step "Validate persisted state holds no live references (bytecode)"
"$py" validation/validate_persistence.py

step "Validate architecture boundary (jar)"
"$py" validation/validate_architecture.py "$jar"

step "Validate public API boundary (jar)"
"$py" validation/validate_api_boundary.py "$jar"

step "Validate persisted state holds no live references (jar)"
"$py" validation/validate_persistence.py "$jar"

# Source compatibility is what the build above proves; this proves BINARY compatibility, which is what a jar built
# against one Cobblemon needs to run on another (see check_cobblemon_binary_compat.py). Cobblemon is taken from the
# Gradle cache by its Modrinth version id: GJ2a1b8O is 1.8.2, the compile target (the jar is built against it, so it
# must always pass, or the checker itself is wrong). 1.8.1 is still checked; add the next Cobblemon
# here (and a rig for it) when one is released. A version missing from the cache is a skipped check, not a failure.
cache="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1/maven.modrinth/MdwFAVRL"
for pair in "GJ2a1b8O:1.8.2" "gBW3vLC7:1.8.1"; do
  id="${pair%%:*}"
  name="${pair##*:}"
  step "Binary compatibility with Cobblemon $name"
  cobblemon_jar="$(find "$cache/$id" -name '*.jar' 2>/dev/null | head -1)"
  if [ -n "$cobblemon_jar" ]; then
    "$py" validation/check_cobblemon_binary_compat.py "$jar" "$cobblemon_jar"
  else
    echo "skipped: Cobblemon $name is not in the Gradle cache ($cache/$id)"
  fi
done

printf '\n\033[32mci_local: all checks passed for %s\033[0m\n' "$version"
