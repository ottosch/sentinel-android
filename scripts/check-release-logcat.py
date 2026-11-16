#!/usr/bin/env python3
"""#564: fail if main sources write to logcat past Timber.

Scans app, boltzmann-java, hummingbird-android and extlibj main sources
for System.out, System.err, printStackTrace, a bare print/println, and
android.util.Log calls. Comments are ignored. Each remaining hit must be
listed below with the reason it is still allowed.

PrintWriter.println (writer.println) is not a match: the dot keeps it
off the bare-println rule, and it does not go to logcat.
"""

from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

MODULES = (
    "app/src/main",
    "boltzmann-java/src/main",
    "hummingbird-android/src/main",
    "extlibj/src/main",
)

# path relative to the repo -> (reason, exact source lines that may match)
ALLOW = {
    "boltzmann-java/src/main/java/com/samourai/boltzmann/Application.java": (
        "CLI entry point. Usage and the run banner. result.print() is the report.",
        (
            'System.err.println("Usage: <txid> [maxCjIntrafeesRatio]");',
            "System.out.println(",
        ),
    ),
    "boltzmann-java/src/main/java/com/samourai/boltzmann/beans/BoltzmannResult.java": (
        "CLI report only. Boltzmann.process() does not call print(); Application.main does.",
        (
            'System.out.println("Inputs = " + getTxos().getInputs());',
            'System.out.println("Outputs = " + getTxos().getOutputs());',
            'System.out.println("Fees = " + getFees() + " satoshis");',
            "System.out.println(",
            "System.out.println(",
            "System.out.println(",
            'System.out.println("Nb combinations = " + getNbCmbn());',
            'System.out.println("Tx entropy = " + getEntropy() + " bits");',
            'System.out.println("Entropy denstity = " + getDensity());',
            "System.out.println(",
            "System.out.println(",
            'System.out.println("Linkability Matrix (probabilities):");',
            "System.out.println(getMatLnkProbabilities());",
            'System.out.println("Linkability Matrix (#combinations with link):");',
            "System.out.println(getMatLnkCombinations());",
            'System.out.println("Deterministic links: all");',
            'System.out.println("Deterministic links:");',
            'System.out.println(in + " & " + out + " are deterministically linked");',
            'System.out.println("Deterministic links: none");',
            'System.out.println("Benchmarks:");',
            'System.out.println("Duration = " + Utils.duration(duration));',
            'System.out.println("Max mem used: " + maxMem + "M");',
            "System.out.println(progress.getResult());",
            'System.out.println("Export: " + exportStr);',
            "e.printStackTrace();",
        ),
    ),
    "extlibj/src/main/java/com/samourai/wallet/crypto/KOpenSSL.kt": (
        "Key, IV, salt and plaintext. printDetails defaults to false; no caller passes true.",
        (
            'println("Salt: ${salt.joinToString("") { "%02X".format(it) }}")',
            'println("Key: ${components.key.joinToString("") { "%02X".format(it) }}")',
            'println("IV: ${components.iv.joinToString("") { "%02X".format(it) }}")',
            "println(it)",
            'println("Salt: ${salt.joinToString("") { "%02X".format(it) }}")',
            'println("Key: ${components.key.joinToString("") { "%02X".format(it) }}")',
            'println("IV: ${components.iv.joinToString("") { "%02X".format(it) }}")',
            "println(it)",
        ),
    ),
    "extlibj/src/main/java/com/samourai/wallet/psbt/PSBT.java": (
        "PSBT dump. System.out.print runs only inside if (bDebug); the default is false.",
        (
            "System.out.print(s);",
            'System.out.print("\\n");',
        ),
    ),
    "extlibj/src/main/java/com/samourai/wallet/cahoots/psbt/PSBT.java": (
        "PSBT dump. System.out.print runs only inside if (bDebug); the default is false.",
        (
            "System.out.print(s);",
            'System.out.print("\\n");',
        ),
    ),
    "app/src/main/java/com/samourai/sentinel/SentinelApplication.kt": (
        "CrashReportingTree. Redacted ExplorerProbe line, and a fixed string when error_dump.log cannot be written.",
        (
            "Log.println(priority, tag, LogRedaction.redact(message))",
            'Log.e("CrashReportingTree", "error dump write failed")',
        ),
    ),
    "hummingbird-android/src/main/java/com/invertedx/hummingbird/QrPerf.kt": (
        "QRPerf counters and part indexes. Lines carry no frame bytes, payloads or wallet data.",
        (
            "Log.i(TAG, qrPerfBbqrSkipLine(sessionId.get(), reason, index))",
            "Log.i(",
        ),
    ),
    "hummingbird-android/src/main/java/com/invertedx/hummingbird/QrCodeAnalyzer.kt": (
        "Image-format int and a fixed success line. The QR text is not logged.",
        (
            'Log.e(TAG, "Format non pris en charge: ${image.format}")',
            'Log.i(TAG, "Success QR code")',
        ),
    ),
}


def mask_comments(source: str) -> str:
    """Replace comments with spaces, keeping newlines and string contents."""
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


def line_hits(masked_line: str) -> bool:
    import re

    # void print( / fun print( is the CLI report method, not a call.
    if re.search(r"\b(?:fun|void)\s+print\s*\(", masked_line):
        return False
    patterns = (
        re.compile(r"printStackTrace\s*\("),
        re.compile(r"System\.out"),
        re.compile(r"System\.err"),
        # Bare println/print. writer.println and System.out.print have a dot.
        re.compile(r"(?<![.\w])println\s*\("),
        re.compile(r"(?<![.\w])print\s*\("),
        re.compile(r"\bLog\.(?:v|d|i|w|e|wtf|println)\s*\("),
    )
    return any(p.search(masked_line) for p in patterns)


def scan() -> dict[str, list[tuple[int, str]]]:
    found: dict[str, list[tuple[int, str]]] = {}
    for module in MODULES:
        base = ROOT / module
        for path in base.rglob("*"):
            if path.suffix not in {".kt", ".java"} or not path.is_file():
                continue
            text = path.read_text(encoding="utf-8")
            masked = mask_comments(text)
            rel = str(path.relative_to(ROOT))
            for lineno, line in enumerate(masked.splitlines(), start=1):
                if line_hits(line):
                    found.setdefault(rel, []).append((lineno, line.strip()))
    return found


def main() -> int:
    found = scan()
    errors = []

    process = (
        ROOT / "boltzmann-java/src/main/java/com/samourai/boltzmann/Boltzmann.java"
    ).read_text(encoding="utf-8")
    if "result.print()" in process or "System.out" in process or "System.err" in process:
        errors.append("Boltzmann.process() must stay silent (no print, no System.out)")
    callers = []
    for module in MODULES:
        for path in (ROOT / module).rglob("*"):
            if path.suffix not in {".java", ".kt"} or not path.is_file():
                continue
            text = mask_comments(path.read_text(encoding="utf-8"))
            if "result.print()" in text:
                callers.append(str(path.relative_to(ROOT)))
    if callers != ["boltzmann-java/src/main/java/com/samourai/boltzmann/Application.java"]:
        errors.append(
            "result.print() must be called only from Application.main, found: "
            + ", ".join(callers)
        )

    kopenssl = (
        ROOT / "extlibj/src/main/java/com/samourai/wallet/crypto/KOpenSSL.kt"
    ).read_text(encoding="utf-8")
    if "printDetails: Boolean = true" in kopenssl or "printDetails = true" in kopenssl:
        errors.append("KOpenSSL printDetails must stay default false")

    for psbt in (
        "extlibj/src/main/java/com/samourai/wallet/psbt/PSBT.java",
        "extlibj/src/main/java/com/samourai/wallet/cahoots/psbt/PSBT.java",
    ):
        body = (ROOT / psbt).read_text(encoding="utf-8")
        if "bDebug = false" not in body:
            errors.append(f"{psbt} must keep bDebug default false")
        raw_lines = body.splitlines()
        for lineno, _text in found.get(psbt, []):
            window = "\n".join(raw_lines[max(0, lineno - 8) : lineno])
            if "bDebug" not in window:
                errors.append(f"{psbt}:{lineno}: System.out is not inside bDebug")

    raw_k = kopenssl.splitlines()
    kpath = "extlibj/src/main/java/com/samourai/wallet/crypto/KOpenSSL.kt"
    for lineno, _text in found.get(kpath, []):
        window = "\n".join(raw_k[max(0, lineno - 4) : lineno])
        if "printDetails" not in window:
            errors.append(f"{kpath}:{lineno}: println is not inside printDetails")

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

    if errors:
        sys.stderr.write("release logcat gate failed (#564):\n")
        for err in errors:
            sys.stderr.write(f"  {err}\n")
        return 1
    print(f"release logcat gate ok ({sum(len(v) for v in found.values())} allowlisted lines)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
