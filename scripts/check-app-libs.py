#!/usr/bin/env python3
"""#576: app/libs is an explicit reviewed list, and it cannot carry SLF4J.

`app/build.gradle` packages every jar in app/libs onto the runtime classpath.
A binder jar dropped there never appears as an org.slf4j coordinate. Fail
unless each jar or aar is named in REVIEWED, and fail again if a listed
archive contains org/slf4j/** or META-INF/services/org.slf4j.*.

  python3 scripts/check-app-libs.py
  python3 scripts/check-app-libs.py --libs /path/to/libs
"""

from __future__ import annotations

import argparse
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

# Filenames allowed to ship from app/libs. Empty on purpose: there is no
# reviewed jar today. Adding one is a review, and it still cannot contain SLF4J.
REVIEWED: set[str] = set()


def check_dir(libs: Path) -> list[str]:
    errors = []
    if not libs.is_dir():
        return errors
    for path in sorted(libs.iterdir()):
        if path.suffix.lower() not in {".jar", ".aar"} or not path.is_file():
            continue
        if path.name not in REVIEWED:
            errors.append(f"{path.name}: not on the reviewed app/libs list")
        try:
            with zipfile.ZipFile(path) as zf:
                for name in zf.namelist():
                    norm = name.replace("\\", "/")
                    if norm.startswith("org/slf4j/") or "/org/slf4j/" in norm:
                        errors.append(f"{path.name}: contains {norm}")
                    if "META-INF/services/org.slf4j." in norm:
                        errors.append(f"{path.name}: contains {norm}")
        except zipfile.BadZipFile:
            errors.append(f"{path.name}: not a readable jar or aar")
    return errors


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--libs", type=Path, default=ROOT / "app" / "libs")
    args = parser.parse_args(argv)
    errors = check_dir(args.libs)
    if errors:
        sys.stderr.write("app/libs gate failed (#576):\n")
        for err in errors:
            sys.stderr.write(f"  {err}\n")
        return 1
    print(f"app/libs gate ok ({args.libs})")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
