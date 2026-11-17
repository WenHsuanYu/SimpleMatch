#!/usr/bin/env python3
"""The Native advisory CI adapter: pinned tools, report, and run receipt.

The GitHub Actions workflow owns ordering and permissions. This module owns
the platform-independent checks and bounded, source-aligned evidence format.
It never edits Native source or promotes advisory findings to build failures.
"""

from __future__ import annotations

import argparse
from collections import Counter
from collections.abc import Iterable
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys


HANDWRITTEN_TUS = 28  # Deliberately update with the audited CMake source list.
GENERATED_TUS = 3
WARNING_SAMPLE_LIMIT = 200
FAMILIES = (
    "clang-analyzer-", "modernize-", "performance-", "readability-",
    "cppcoreguidelines-", "misc-", "google-",
)
STAGES = ("selftest", "toolchain", "bootstrap", "configure", "analyze", "inventory")
RUNNER_URL = (
    "https://raw.githubusercontent.com/llvm/llvm-project/"
    "llvmorg-{version}/clang-tools-extra/clang-tidy/tool/run-clang-tidy.py"
)
OWNED_WARNING = re.compile(
    r"^(.*/matching-engine/(?:include|src|tests)/[^:]+):(\d+):(\d+): "
    r"warning: .* \[([^\]]+)\]$"
)
ANY_WARNING = re.compile(r"^.+:\d+:\d+: warning: ")
PROGRESS = re.compile(rf"^\[\s*(\d+)/{HANDWRITTEN_TUS}\]\[\d+(?:\.\d+)?s\]")


def setting(name: str) -> str:
    value = os.environ.get(name)
    if not value:
        raise RuntimeError(f"Missing required CI setting {name}")
    return value


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def file_sha(path: Path) -> str:
    return sha256(path.read_bytes())


def ensure_sha(path: Path, expected: str) -> None:
    if not path.is_file() or file_sha(path) != expected:
        raise RuntimeError(f"Invalid pinned SHA-256 for {path.name}")


def ensure_version(binary: Path, expected: str) -> None:
    if not binary.is_file():
        raise RuntimeError(f"Required LLVM executable missing: {binary.name}")
    output = subprocess.check_output([str(binary), "--version"], text=True)
    if not re.search(rf"(?<!\d){re.escape(expected)}(?!\d)", output):
        raise RuntimeError(f"LLVM version mismatch for {binary.name}: {output.strip()}")


def paths() -> tuple[Path, Path]:
    root = Path(setting("RUNNER_TEMP"))
    return root, root / "native-tidy-artifact"


def write_json(file: Path, data: dict) -> None:
    file.parent.mkdir(parents=True, exist_ok=True)
    file.write_text(json.dumps(data, sort_keys=True, indent=2) + "\n")


def install_tools() -> None:
    root, _ = paths()
    version = setting("LLVM_VERSION")
    tidy_hash = setting("LLVM_TIDY_WHEEL_SHA256")
    format_hash = setting("LLVM_FORMAT_WHEEL_SHA256")
    runner_hash = setting("LLVM_RUN_TIDY_SHA256")
    virtualenv = root / "llvm21"
    subprocess.run([sys.executable, "-m", "venv", str(virtualenv)], check=True)
    python = virtualenv / "bin/python"
    requirements = (
        f"clang-tidy=={version} --hash=sha256:{tidy_hash}\n"
        f"clang-format=={version} --hash=sha256:{format_hash}\n"
    )
    subprocess.run(
        [str(python), "-m", "pip", "install", "--no-deps", "--no-cache-dir",
         "--require-hashes", "-r", "/dev/stdin"],
        input=requirements, text=True, check=True,
    )
    runner = root / "run-clang-tidy.py"
    subprocess.run(
        ["curl", "--fail", "--location", "--silent", "--show-error",
         "--retry", "3", "--output", str(runner),
         RUNNER_URL.format(version=version)], check=True,
    )
    ensure_sha(runner, runner_hash)
    runner.chmod(0o755)
    tidy = virtualenv / "bin/clang-tidy"
    formatter = virtualenv / "bin/clang-format"
    ensure_version(tidy, version)
    ensure_version(formatter, version)
    with Path(setting("GITHUB_ENV")).open("a") as handle:
        handle.write(f"TIDY_BIN={tidy}\nFORMAT_BIN={formatter}\nRUN_TIDY_BIN={runner}\n")
    print(f"Pinned LLVM {version}, analyzer and runner identities validated")


def compilation_scope(database: Path) -> tuple[list[str], list[str]]:
    entries = json.loads(database.read_text())
    owned = [item["file"] for item in entries if re.search(
        r"/matching-engine/(src|tests)/.*\.cpp$", item["file"])]
    generated = [item["file"] for item in entries if item["file"].endswith(".pb.cc")]
    if (len(owned), len(generated)) != (HANDWRITTEN_TUS, GENERATED_TUS):
        raise RuntimeError(
            f"Unexpected Native source scope: {len(owned)} handwritten, "
            f"{len(generated)} generated"
        )
    if len(set(owned)) != HANDWRITTEN_TUS:
        raise RuntimeError("Duplicate handwritten Native compilation entries")
    return owned, generated


def parse_warnings(lines: Iterable[str]) -> tuple[Counter[str], set[tuple[str, int, str]]]:
    findings: Counter[str] = Counter()
    sites: set[tuple[str, int, str]] = set()
    progress: set[int] = set()
    started = False
    unmatched = 0
    for line in lines:
        if "Run report-only clang-tidy on handwritten Native C++ sources" in line:
            started = True
        if not started:
            continue
        match_progress = PROGRESS.match(line)
        if match_progress:
            progress.add(int(match_progress.group(1)))
        match = OWNED_WARNING.match(line)
        if match:
            path, line_number, _, check = match.groups()
            relative = "matching-engine/" + path.split("/matching-engine/", 1)[1]
            findings[check] += 1
            sites.add((relative, int(line_number), check))
        elif ANY_WARNING.match(line):
            unmatched += 1
    if progress != set(range(1, HANDWRITTEN_TUS + 1)):
        raise RuntimeError(f"Incomplete handwritten-TU scan: {len(progress)}/{HANDWRITTEN_TUS}")
    if unmatched:
        raise RuntimeError(f"Unparsed clang-tidy warning records: {unmatched}")
    return findings, sites


def report() -> None:
    root, output = paths()
    repo = Path.cwd()
    db = repo / "out/build/full-clang-tidy-report/compile_commands.json"
    owned, generated = compilation_scope(db)

    tidy = Path(setting("TIDY_BIN"))
    formatter = Path(setting("FORMAT_BIN"))
    fixture = "matching-engine/src/input_offset_ledger.cpp"
    checks_text = subprocess.check_output(
        [str(tidy), "--list-checks", "-p", str(db.parent), fixture], text=True,
    )
    subprocess.run([str(formatter), "--style=file", fixture],
                   stdout=subprocess.DEVNULL, check=True)
    checks = [line.strip() for line in checks_text.splitlines() if line.startswith("    ")]
    if (len(checks) < 300 or "modernize-use-trailing-return-type" in checks
            or any(not any(check.startswith(prefix) for check in checks)
                   for prefix in FAMILIES)):
        raise RuntimeError("Selected checks do not match approved policy")

    log = root / "native-clang-tidy-output.log"
    with log.open() as stream:
        findings, sites = parse_warnings(stream)
    head = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    if head != setting("GITHUB_SHA"):
        raise RuntimeError("Checkout does not match the triggering commit SHA")

    owned_files = sorted(path for path in subprocess.check_output(
        ["git", "ls-files", "-z", "matching-engine/src", "matching-engine/include",
         "matching-engine/tests"]
    ).decode().split("\0") if path.endswith((".cpp", ".hpp")))
    sources = {path: file_sha(repo / path) for path in owned_files}
    bounded = [
        {"path": path, "line": line, "check_tag": check}
        for path, line, check in sorted(sites)[:WARNING_SAMPLE_LIMIT]
    ]
    selected = output / "selected-checks.txt"
    output.mkdir(parents=True, exist_ok=True)
    selected.write_text(checks_text)
    write_json(output / "owned-source-sha256.json", sources)
    write_json(output / "warning-sites.json", {
        "total_unique_sites": len(sites),
        "sample_limit": WARNING_SAMPLE_LIMIT,
        "sample_truncated": len(sites) > WARNING_SAMPLE_LIMIT,
        "sites": bounded,
        "redaction": "Repo-relative path/line/check only; no messages or source excerpts",
    })
    summary = {
        "status": "PASS_WITH_ADVISORY_WARNINGS" if findings else "PASS_CLEAN",
        "commit_sha": head,
        "run_id": setting("GITHUB_RUN_ID"),
        "run_attempt": setting("GITHUB_RUN_ATTEMPT"),
        "tool_version": setting("LLVM_VERSION"),
        "tidy_wheel_sha256": setting("LLVM_TIDY_WHEEL_SHA256"),
        "format_wheel_sha256": setting("LLVM_FORMAT_WHEEL_SHA256"),
        "runner_sha256": setting("LLVM_RUN_TIDY_SHA256"),
        "clang_tidy_binary_sha256": file_sha(tidy),
        "clang_format_binary_sha256": file_sha(formatter),
        "policy_sha256": file_sha(repo / ".clang-tidy"),
        "format_policy_sha256": file_sha(repo / ".clang-format"),
        "compile_db_sha256": file_sha(db),
        "selected_check_count": len(checks),
        "selected_checks_sha256": file_sha(selected),
        "handwritten_tu_count": len(owned),
        "excluded_generated_tu_count": len(generated),
        "owned_source_count": len(sources),
        "warning_emissions": sum(findings.values()),
        "unique_owned_warning_sites": len(sites),
        "parsed_warning_emissions": sum(findings.values()),
        "unparsed_warning_emissions": 0,
        "bounded_site_sample_limit": WARNING_SAMPLE_LIMIT,
        "per_check_emissions": dict(sorted(findings.items())),
        "warnings_as_errors": False,
        "finding_disposition": "UNADJUDICATED unless reviewed in Issue #210",
    }
    write_json(output / "summary.json", summary)
    print(f"Native advisory: {summary['warning_emissions']} warning occurrences, "
          f"{len(sites)} distinct sites across {len(owned)} handwritten TUs")


def verdict() -> None:
    _, output = paths()
    steps = {stage: os.environ.get(f"{stage.upper()}_OUTCOME", "skipped")
             for stage in STAGES}
    succeeded = all(outcome == "success" for outcome in steps.values())
    summary = output / "summary.json"
    valid_summary = False
    status = "UNRUN"
    if succeeded and summary.exists():
        try:
            parsed = json.loads(summary.read_text())
            candidate = parsed.get("status") if isinstance(parsed, dict) else None
            if isinstance(candidate, str) and candidate in {
                    "PASS_CLEAN", "PASS_WITH_ADVISORY_WARNINGS"}:
                status = candidate
                valid_summary = True
        except ValueError:
            pass
    if not valid_summary and (succeeded or "failure" in steps.values()):
        status = "FAIL"
    head = subprocess.run(["git", "rev-parse", "--verify", "HEAD"],
                          capture_output=True, text=True)
    receipt = {
        "status": status,
        "commit_sha": head.stdout.strip() if head.returncode == 0 else None,
        "expected_commit_sha": setting("GITHUB_SHA"),
        "run_id": setting("GITHUB_RUN_ID"),
        "run_attempt": setting("GITHUB_RUN_ATTEMPT"),
        "workflow_steps": steps,
        "tool_version_requested": setting("LLVM_VERSION"),
        "run_clang_tidy_sha256_requested": setting("LLVM_RUN_TIDY_SHA256"),
        "inventory_available": valid_summary,
        "report_only": True,
    }
    write_json(output / "verdict.json", receipt)
    print(f"Native advisory Hosted verdict: {status}")
    if succeeded and not valid_summary:
        raise RuntimeError("All stages succeeded but source-aligned summary is invalid")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("install", "report", "verdict"))
    args = parser.parse_args()
    try:
        {"install": install_tools, "report": report, "verdict": verdict}[args.action]()
    except (OSError, ValueError, RuntimeError, subprocess.CalledProcessError) as exc:
        print(f"Native advisory {args.action} failed: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
