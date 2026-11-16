#!/usr/bin/env python3
"""#564 / #576: fail if main sources can write wallet data to release logcat.

Scans app, boltzmann-java, hummingbird-android, extlibj, bitcoinj and
xmanager-protocol main sources. Comments are ignored. A hit that is not
on the allowlist fails the build.

The matchers cover the text-regex bypasses from the #576 review:
qualified kotlin.io.println, Log import aliases and typealiases, member
and static imports, method references, java.util.logging, reflection,
and a BoltzmannResult.print() call under any local name (B10).

  python3 scripts/check-release-logcat.py
  python3 scripts/check-release-logcat.py --sources DIR --strict
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

MODULES = (
    "app/src/main",
    "boltzmann-java/src/main",
    "hummingbird-android/src/main",
    "extlibj/src/main",
    "bitcoinj/src/main",
    "xmanager-protocol/src/main",
)

# path -> (reason, exact stripped lines)
ALLOW: dict[str, tuple[str, tuple[str, ...]]] = {}

LOG_CALL = re.compile(r"\bLog\.(?:v|d|i|w|e|wtf|println)\s*\(")
PRINTLN_CALL = re.compile(r"println\s*\(")
PRINT_CALL = re.compile(r"(?<![\w])print\s*\(")
PRINT_DECL = re.compile(r"\b(?:fun|void)\s+print\s*\(")
METHOD_REF = re.compile(r"::(?:println|printStackTrace|print)\b")
JUL = re.compile(r"java\.util\.logging|Logger\.getLogger\s*\(")
REFLECTION = re.compile(
    r"(?:get|getDeclared)Method\s*\(\s*\"(?:println|print|printStackTrace)\""
    r"|Class\.forName\s*\(\s*\"(?:android\.util\.Log|java\.io\.PrintStream"
    r"|java\.util\.logging\.Logger|kotlin\.io\.ConsoleKt)\""
)
STATIC_IMPORT = re.compile(
    r"import\s+static\s+(?:android\.util\.Log\b|java\.lang\.System\.(?:\*|out|err)\b)"
)
STREAM_CALL = re.compile(
    r"\b(?:System\.out|System\.err|err)\.(?:printf|write|append|format|print|println)\s*\("
    r"|\bprintf\s*\("
)
KOTLIN_IO_IMPORT = re.compile(r"import\s+kotlin\.io\.(?:println|print)\b")
LOG_ALIAS_IMPORT = re.compile(r"import\s+android\.util\.Log\s+as\s+(\w+)")
LOG_TYPEALIAS = re.compile(r"typealias\s+(\w+)\s*=\s*android\.util\.Log\b")
LOG_MEMBER_IMPORT = re.compile(r"import\s+(?:static\s+)?android\.util\.Log\.")
CONSOLE_KT = re.compile(r"\bConsoleKt\b")


def mask_comments(source: str) -> str:
    out = []
    i = 0
    n = len(source)
    while i < n:
        if source.startswith('"""', i):
            end = source.find('"""', i + 3)
            end = n if end < 0 else end + 3
            out.append(source[i:end])
            i = end
            continue
        c = source[i]
        if c == "/" and i + 1 < n and source[i + 1] == "/":
            out.append("  ")
            i += 2
            while i < n and source[i] != "\n":
                out.append(" ")
                i += 1
            continue
        if c == "/" and i + 1 < n and source[i + 1] == "*":
            out.append("  ")
            i += 2
            while i < n and not source.startswith("*/", i):
                out.append("\n" if source[i] == "\n" else " ")
                i += 1
            if i < n:
                out.append("  ")
                i += 2
            continue
        if c == '"':
            out.append(c)
            i += 1
            while i < n:
                s = source[i]
                out.append(s)
                i += 1
                if s == "\\" and i < n:
                    out.append(source[i])
                    i += 1
                    continue
                if s == '"':
                    break
            continue
        out.append(c)
        i += 1
    return "".join(out)


def line_hits(masked_line: str, aliases: set[str]) -> bool:
    if PRINT_DECL.search(masked_line):
        return False
    if (
        "printStackTrace" in masked_line
        or "System.out" in masked_line
        or "System.err" in masked_line
        or PRINTLN_CALL.search(masked_line)
        or PRINT_CALL.search(masked_line)
        or LOG_CALL.search(masked_line)
        or METHOD_REF.search(masked_line)
        or JUL.search(masked_line)
        or REFLECTION.search(masked_line)
        or STATIC_IMPORT.search(masked_line)
        or KOTLIN_IO_IMPORT.search(masked_line)
        or LOG_ALIAS_IMPORT.search(masked_line)
        or LOG_TYPEALIAS.search(masked_line)
        or LOG_MEMBER_IMPORT.search(masked_line)
        or CONSOLE_KT.search(masked_line)
        or STREAM_CALL.search(masked_line)
    ):
        return True
    for alias in aliases:
        if re.search(rf"\b{re.escape(alias)}\s*\(", masked_line):
            return True
        if re.search(rf"\b{re.escape(alias)}\.(?:v|d|i|w|e|wtf|println|print)\s*\(", masked_line):
            return True
    return False


def aliases_in(masked: str) -> set[str]:
    names = set()
    names.update(LOG_ALIAS_IMPORT.findall(masked))
    names.update(LOG_TYPEALIAS.findall(masked))
    for match in re.finditer(r"import\s+kotlin\.io\.(?:println|print)\s+as\s+(\w+)", masked):
        names.add(match.group(1))
    return names


def scan_tree(base: Path, relative_to: Path) -> dict[str, list[tuple[int, str]]]:
    found: dict[str, list[tuple[int, str]]] = {}
    for path in base.rglob("*"):
        if path.suffix not in {".kt", ".java"} or not path.is_file():
            continue
        if "/src/test/" in str(path) or "/src/androidTest/" in str(path):
            continue
        text = path.read_text(encoding="utf-8")
        masked = mask_comments(text)
        aliases = aliases_in(masked)
        rel = str(path.relative_to(relative_to))
        for lineno, line in enumerate(masked.splitlines(), start=1):
            if line_hits(line, aliases):
                found.setdefault(rel, []).append((lineno, line.strip()))
    return found


def scan_repo() -> dict[str, list[tuple[int, str]]]:
    found: dict[str, list[tuple[int, str]]] = {}
    for module in MODULES:
        base = ROOT / module
        if not base.is_dir():
            continue
        found.update(scan_tree(base, ROOT))
    return found


def boltzmann_api_errors(files: dict[str, str]) -> list[str]:
    """B10: any local name can call a public print() on the library result."""
    errors = []
    for rel, text in files.items():
        masked = mask_comments(text)
        if rel.endswith("BoltzmannResult.java") and re.search(r"\bvoid\s+print\s*\(", masked):
            errors.append(f"{rel}: BoltzmannResult must not declare print(); the CLI owns the report")
        if rel.endswith("Application.java"):
            continue
        if ".process(" in masked and re.search(r"\w+\.print\s*\(", masked):
            errors.append(
                f"{rel}: .print() after .process() dumps a Boltzmann result (B10). "
                "The report is private to Application."
            )
    return errors


def collect_sources(base: Path) -> dict[str, str]:
    files = {}
    for path in base.rglob("*"):
        if path.suffix in {".kt", ".java"} and path.is_file():
            if "/src/test/" in str(path) or "/src/androidTest/" in str(path):
                continue
            files[str(path.relative_to(base))] = path.read_text(encoding="utf-8")
    return files


def repo_sources() -> dict[str, str]:
    files = {}
    for module in MODULES:
        base = ROOT / module
        if not base.is_dir():
            continue
        for path in base.rglob("*"):
            if path.suffix in {".kt", ".java"} and path.is_file():
                files[str(path.relative_to(ROOT))] = path.read_text(encoding="utf-8")
    return files


def gated_errors(sources: dict[str, str], found: dict[str, list[tuple[int, str]]]) -> list[str]:
    errors = []
    for psbt in (
        "extlibj/src/main/java/com/samourai/wallet/psbt/PSBT.java",
        "extlibj/src/main/java/com/samourai/wallet/cahoots/psbt/PSBT.java",
    ):
        if psbt not in sources:
            continue
        body = sources[psbt]
        if "bDebug = false" not in body:
            errors.append(f"{psbt} must keep bDebug default false")
        raw = body.splitlines()
        for lineno, _text in found.get(psbt, []):
            window = "\n".join(raw[max(0, lineno - 8) : lineno])
            if "bDebug" not in window:
                errors.append(f"{psbt}:{lineno}: System.out is not inside bDebug")
    return errors


def evaluate(found: dict[str, list[tuple[int, str]]], sources: dict[str, str], strict: bool) -> list[str]:
    errors = boltzmann_api_errors(sources)
    if not strict:
        errors.extend(gated_errors(sources, found))
    if strict:
        for rel, hits in sorted(found.items()):
            for lineno, text in hits:
                errors.append(f"{rel}:{lineno}: {text}")
        return errors
    for rel, hits in sorted(found.items()):
        lines = tuple(text for _, text in hits)
        allowed = ALLOW.get(rel)
        if allowed is None:
            for lineno, text in hits:
                errors.append(f"{rel}:{lineno}: {text}")
            continue
        reason, expected = allowed
        if not reason.strip():
            errors.append(f"{rel}: allowlist entry needs a reason")
        if lines != expected:
            errors.append(
                f"{rel}: allowlist drift\n  expected:\n    "
                + "\n    ".join(expected)
                + "\n  found:\n    "
                + "\n    ".join(f"{n}: {t}" for n, t in hits)
            )
    for rel in ALLOW:
        if rel not in found:
            errors.append(f"{rel}: allowlisted file has no remaining hits; drop it")
    return errors


def load_allow() -> None:
    """Filled after the first repo scan; see the assignment below."""
    global ALLOW
    ALLOW = _ALLOW


# Exact lines are filled in _ALLOW. Reasons are per file.
_ALLOW: dict[str, tuple[str, tuple[str, ...]]] = {
    "app/src/main/java/com/samourai/sentinel/SentinelApplication.kt": (
        "CrashReportingTree. Redacted ExplorerProbe line, and a fixed string when error_dump.log cannot be written.",
        (
            "Log.println(priority, tag, LogRedaction.redact(message))",
            'Log.e("CrashReportingTree", "error dump write failed")',
        ),
    ),
    "app/src/main/java/com/samourai/sentinel/data/repository/Bip329Exporter.kt": (
        "PrintWriter.println of a BIP-329 export the user chose. Not System.out.",
        (
            "writer.println(o.toString())",
            "writer.println(o.toString())",
        ),
    ),
    "app/src/main/java/com/samourai/sentinel/util/LogRedaction.kt": (
        "OkHttp JUL filter. It redacts the record before Android's log bridge; it does not print it.",
        (
            "import java.util.logging.Filter",
            "import java.util.logging.LogRecord",
            "import java.util.logging.Logger",
            "Logger.getLogger(OkHttpClient::class.java.name).filter = redacting",
        ),
    ),
    "bitcoinj/src/main/java/org/bitcoinj/utils/BriefLogFormatter.java": (
        "JUL formatter. printStackTrace writes into a StringWriter. init() is only called from bitcoinj tests.",
        (
            "import java.util.logging.*;",
            'logger = Logger.getLogger("");',
            'Logger.getLogger("org.bitcoinj").setLevel(Level.SEVERE);',
            "logRecord.getThrown().printStackTrace(new PrintWriter(result));",
        ),
    ),
    "boltzmann-java/src/main/java/com/samourai/boltzmann/Application.java": (
        "CLI main only. The report method is private, so the library API cannot call it.",
        (
            'System.err.println("Usage: <txid> [maxCjIntrafeesRatio]");',
            "System.out.println(",
            "print(new Boltzmann(settings).process(txid));",
            'System.out.println("Inputs = " + result.getTxos().getInputs());',
            'System.out.println("Outputs = " + result.getTxos().getOutputs());',
            'System.out.println("Fees = " + result.getFees() + " satoshis");',
            "System.out.println(",
            "System.out.println(",
            "System.out.println(",
            'System.out.println("Nb combinations = " + result.getNbCmbn());',
            'System.out.println("Tx entropy = " + result.getEntropy() + " bits");',
            'System.out.println("Entropy denstity = " + result.getDensity());',
            "System.out.println(",
            "System.out.println(",
            'System.out.println("Linkability Matrix (probabilities):");',
            "System.out.println(result.getMatLnkProbabilities());",
            'System.out.println("Linkability Matrix (#combinations with link):");',
            "System.out.println(result.getMatLnkCombinations());",
            'System.out.println("Deterministic links: all");',
            'System.out.println("Deterministic links:");',
            'System.out.println(in + " & " + out + " are deterministically linked");',
            'System.out.println("Deterministic links: none");',
            'System.out.println("Benchmarks:");',
            'System.out.println("Duration = " + Utils.duration(result.getDuration()));',
            'System.out.println("Max mem used: " + maxMem + "M");',
            "System.out.println(progress.getResult());",
            'System.out.println("Export: " + exportStr);',
            "e.printStackTrace();",
        ),
    ),
    "hummingbird-android/src/main/java/com/invertedx/hummingbird/QrCodeAnalyzer.kt": (
        "Image-format int and a fixed success line. The QR text is not logged.",
        (
            'Log.e(TAG, "Format non pris en charge: ${image.format}")',
            'Log.i(TAG, "Success QR code")',
        ),
    ),
    "hummingbird-android/src/main/java/com/invertedx/hummingbird/QrPerf.kt": (
        "QRPerf counters and part indexes. Lines carry no frame bytes, payloads or wallet data.",
        (
            "Log.i(TAG, qrPerfBbqrSkipLine(sessionId.get(), reason, index))",
            "Log.i(",
        ),
    ),
}


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--sources", type=Path, help="Scan this tree instead of the repo")
    parser.add_argument("--strict", action="store_true", help="No allowlist; any hit fails")
    parser.add_argument("--report", action="store_true", help="Print hits and exit 0")
    args = parser.parse_args(argv)
    load_allow()
    if args.sources:
        base = args.sources
        found = scan_tree(base, base)
        sources = collect_sources(base)
    else:
        found = scan_repo()
        sources = repo_sources()
    if args.report:
        for rel, hits in sorted(found.items()):
            print(f"# {rel}")
            for lineno, text in hits:
                print(f"{lineno}: {text}")
        extra = boltzmann_api_errors(sources)
        for err in extra:
            print(f"! {err}")
        return 0
    errors = evaluate(found, sources, strict=args.strict)
    if errors:
        sys.stderr.write("release logcat source gate failed (#564):\n")
        for err in errors:
            sys.stderr.write(f"  {err}\n")
        return 1
    count = sum(len(v) for v in found.values())
    print(f"release logcat source gate ok ({count} allowlisted lines)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
