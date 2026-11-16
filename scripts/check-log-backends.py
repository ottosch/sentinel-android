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

COORD = re.compile(r"([A-Za-z0-9_.-]+):([A-Za-z0-9_.-]+):")
ALLOWED = {("org.slf4j", "slf4j-api"), ("org.slf4j", "slf4j-nop")}


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


def check_text(text: str, label: str) -> list[str]:
    found = []
    seen = set()
    has_nop = False
    for match in COORD.finditer(text):
        group, artifact = match.group(1), match.group(2)
        if group == "org.slf4j" and artifact == "slf4j-nop":
            has_nop = True
        if not is_backend(group, artifact):
            continue
        key = f"{group}:{artifact}"
        if key in seen:
            continue
        seen.add(key)
        found.append(f"{label}: logging backend {key} is not slf4j-nop")
    if not has_nop:
        found.append(f"{label}: org.slf4j:slf4j-nop is not on this runtime classpath")
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
