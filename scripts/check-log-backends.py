#!/usr/bin/env python3
"""#576: slf4j-nop is the only logging backend on every release runtime classpath.

Reads Gradle `:app:dependencies` output for one or more configurations.
org.slf4j:slf4j-api and org.slf4j:slf4j-nop are allowed. Every other SLF4J
binding or logging provider fails the build, including a dependency that
is only on productionReleaseRuntimeClasspath or only on the release build
type.

  python3 scripts/check-log-backends.py staging.txt production.txt
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

COORD = re.compile(
    r"([A-Za-z0-9_.-]+):([A-Za-z0-9_.-]+):(\d[^|\s]*)(?:\s*->\s*(\d\S+))?"
)
ALLOWED = {("org.slf4j", "slf4j-api"), ("org.slf4j", "slf4j-nop")}


def is_17(version: str) -> bool:
    return version == "1.7" or version.startswith("1.7.")


def is_backend(group: str, artifact: str) -> bool:
    g = group.lower()
    a = artifact.lower()
    if (g, a) in ALLOWED:
        return False
    if g == "org.slf4j":
        return True
    if "logback" in g or a.startswith("logback") or "logback-android" in a:
        return True
    if a.startswith("log4j-slf4j") or a.startswith("slf4j-"):
        return True
    if g.startswith("org.apache.logging.log4j"):
        return True
    if a in {"slf4j-android", "logback-android"}:
        return True
    return False


# Left side, then an optional Gradle " -> " resolution. A module substitution
# (`slf4j-nop -> org.slf4j:slf4j-api`) means nop is not on the classpath.
EDGE = re.compile(
    r"org\.slf4j:(slf4j-api|slf4j-nop):(\d\S*|\{[^}]+\})"
    r"(?:\s*->\s*(\S+))?"
)


def resolved_module(artifact: str, version: str, arrow: str | None) -> tuple[str, str] | None:
    """The module that actually lands on the classpath."""
    if arrow and ":" in arrow:
        substituted = re.match(r"org\.slf4j:(slf4j-api|slf4j-nop):(\S+)", arrow)
        if not substituted:
            return None
        return substituted.group(1), substituted.group(2).rstrip("()")
    chosen = arrow or version
    if not chosen or chosen.startswith("{"):
        return None
    return artifact, chosen.rstrip("()")


def check_text(text: str, label: str) -> list[str]:
    found = []
    seen = set()
    has_nop = False
    saw_api = False
    for match in COORD.finditer(text):
        group, artifact = match.group(1), match.group(2)
        if not is_backend(group, artifact):
            continue
        key = f"{group}:{artifact}"
        if key in seen:
            continue
        seen.add(key)
        found.append(f"{label}: logging backend {key} is not slf4j-nop")
    for match in EDGE.finditer(text):
        resolved = resolved_module(match.group(1), match.group(2), match.group(3))
        if resolved is None:
            continue
        artifact, version = resolved
        if not is_17(version):
            found.append(f"{label}: org.slf4j:{artifact}:{version} is not the 1.7.x line")
            continue
        if artifact == "slf4j-nop":
            has_nop = True
        elif artifact == "slf4j-api":
            saw_api = True
    if not has_nop:
        found.append(f"{label}: org.slf4j:slf4j-nop:1.7.x is not on this runtime classpath")
    if not saw_api:
        found.append(f"{label}: org.slf4j:slf4j-api:1.7.x is not on this runtime classpath")
    return found


def main(argv: list[str]) -> int:
    if len(argv) < 1:
        sys.stderr.write("usage: check-log-backends.py DEP_FILE...\n")
        return 2
    errors = []
    for raw in argv:
        path = Path(raw)
        errors.extend(check_text(path.read_text(encoding="utf-8"), path.name))
    if errors:
        sys.stderr.write("release logging-backend gate failed (#576):\n")
        for err in errors:
            sys.stderr.write(f"  {err}\n")
        return 1
    print(f"release logging-backend gate ok ({len(argv)} classpath(s))")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
