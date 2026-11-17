#!/usr/bin/env python3
"""Summarize the Native clang-tidy advisory run.

GitHub Actions reports execution failures. The analyzer log contains every
diagnostic. This file validates coverage and writes one bounded report.
"""

from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys


FAMILIES = (
    "clang-analyzer-", "modernize-", "performance-", "readability-",
    "cppcoreguidelines-", "misc-", "google-",
)
PROGRESS = re.compile(
    r"^\[\s*(\d+)/(\d+)\]\[\d+(?:\.\d+)?s\]"
    r".*?(/\S*/matching-engine/(?:src|tests)/\S+\.cpp)\s*$"
)
WARNING = re.compile(
    r"^(.*/matching-engine/(?:include|src|tests)/[^:]+):"
    r"(\d+):(\d+): warning: .* \[([^\]]+)\]$"
)
OTHER_DIAGNOSTIC = re.compile(r"^.+:\d+:\d+: (?:warning|error): ")
OTHER_PROGRESS = re.compile(r"^\[\s*\d+/\d+\]\[")


def tracked_files() -> list[str]:
    output = subprocess.check_output([
        "git", "ls-files", "-z", "--", "matching-engine/src",
        "matching-engine/include", "matching-engine/tests",
    ])
    return sorted(path for path in output.decode().split("\0")
                  if path.endswith((".cpp", ".hpp")))


def relative_source(path: str, repo: Path) -> str:
    try:
        relative = Path(path).resolve().relative_to(repo.resolve()).as_posix()
    except ValueError as exc:
        raise RuntimeError("Diagnostic path is outside this checkout") from exc
    if not re.fullmatch(r"matching-engine/(?:src|include|tests)/.+", relative):
        raise RuntimeError("Diagnostic path is outside owned Native sources")
    return relative


def compilation_sources(database: Path, repo: Path, expected: set[str]) -> None:
    entries = json.loads(database.read_text())
    sources = [entry["file"] for entry in entries]
    owned = [relative_source(path, repo) for path in sources
             if re.search(r"/matching-engine/(?:src|tests)/.*\.cpp$", path)]
    generated = [path for path in sources if path.endswith(".pb.cc")]
    if len(owned) != len(set(owned)) or set(owned) != expected:
        raise RuntimeError("Compilation database does not match tracked Native sources")
    if len(generated) != 3 or len(set(generated)) != 3:
        raise RuntimeError("Full Native preset must contain three protobuf units")


def diagnostics(log: Path, repo: Path, expected: set[str]
                ) -> Counter[tuple[str, int, int, str]]:
    sites: Counter[tuple[str, int, int, str]] = Counter()
    indices: set[int] = set()
    scanned: set[str] = set()
    started = False
    with log.open() as stream:
        for line in stream:
            line = line.rstrip("\n")
            if "Run report-only clang-tidy on handwritten Native C++ sources" in line:
                started = True
                continue
            if not started:
                continue
            progress = PROGRESS.match(line)
            if progress:
                index, total, path = progress.groups()
                source = relative_source(path, repo)
                if (int(total) != len(expected) or int(index) < 1
                        or int(index) > len(expected) or int(index) in indices
                        or source in scanned):
                    raise RuntimeError("Analyzer progress does not match source scope")
                indices.add(int(index))
                scanned.add(source)
            elif OTHER_PROGRESS.match(line):
                raise RuntimeError("Unrecognized analyzer progress line")
            warning = WARNING.match(line)
            if warning:
                path, number, column, check = warning.groups()
                source = relative_source(path, repo)
                sites[(source, int(number), int(column), check)] += 1
            elif OTHER_DIAGNOSTIC.match(line):
                raise RuntimeError("Unparsed analyzer diagnostic; inspect the analyzer log")
    if indices != set(range(1, len(expected) + 1)) or scanned != expected:
        raise RuntimeError("Analyzer did not scan every tracked Native source")
    return sites


def source_fingerprint(files: list[str], repo: Path) -> str:
    digest = hashlib.sha256()
    for filename in files:
        digest.update(filename.encode() + b"\0")
        digest.update(hashlib.sha256((repo / filename).read_bytes()).digest())
    return digest.hexdigest()


def main() -> None:
    repo = Path.cwd()
    expected_sha = os.environ["GITHUB_SHA"]
    actual_sha = subprocess.check_output(
        ["git", "rev-parse", "HEAD"], text=True,
    ).strip()
    if actual_sha != expected_sha:
        raise RuntimeError("Checkout commit differs from the triggering commit")

    sources = tracked_files()
    expected = {path for path in sources if path.endswith(".cpp")}
    if not expected:
        raise RuntimeError("No tracked Native translation units")
    database = repo / "out/build/full-clang-tidy-report/compile_commands.json"
    compilation_sources(database, repo, expected)

    output = subprocess.check_output([
        str(Path(os.environ["LLVM_VENV"]) / "bin/clang-tidy"),
        "--list-checks", "-p", str(database.parent),
        sorted(expected)[0],
    ], text=True)
    checks = [line.strip() for line in output.splitlines()
              if line.startswith("    ")]
    if (len(checks) < 300 or len(checks) != len(set(checks))
            or "modernize-use-trailing-return-type" in checks
            or any(not any(check.startswith(family) for check in checks)
                   for family in FAMILIES)):
        raise RuntimeError("Enabled checks do not match the approved analyzer policy")

    sites = diagnostics(Path(os.environ["RUNNER_TEMP"]) / "native-clang-tidy-output.log",
                        repo, expected)
    if any(path not in sources for path, _, _, _ in sites):
        raise RuntimeError("Warning refers to a source not tracked by Git")
    counts: Counter[str] = Counter()
    examples: dict[str, tuple[str, int, int, str]] = {}
    for site, occurrences in sorted(sites.items()):
        counts[site[3]] += occurrences
        examples.setdefault(site[3], site)
    unexpected = sorted({name for tag in counts for name in tag.split(",")
                         if name not in checks})
    if unexpected:
        raise RuntimeError(f"Analyzer log contains disabled checks: {unexpected[:3]}")
    sample = [
        {"path": examples[check][0], "line": examples[check][1],
         "column": examples[check][2], "check": check}
        for check, _ in counts.most_common(10)
    ]
    report = {
        "commit_sha": actual_sha,
        "tool": {"clang_tidy": os.environ["LLVM_VERSION"],
                 "checks": sorted(checks)},
        "source": {
            "analyzed_tus": sorted(expected),
            "owned_sha256": source_fingerprint(sources, repo),
            "policy_sha256": hashlib.sha256((repo / ".clang-tidy").read_bytes()).hexdigest(),
            "compile_db_sha256": hashlib.sha256(database.read_bytes()).hexdigest(),
        },
        "warnings": {
            "emissions": sum(counts.values()),
            "unique_sites": len(sites),
            "by_tag": dict(sorted(counts.items())),
            "examples": sample,
        },
    }
    artifact = Path(os.environ["RUNNER_TEMP"]) / "native-tidy-report.json"
    temporary = artifact.with_suffix(".tmp")
    temporary.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")
    temporary.replace(artifact)
    print(f"Native clang-tidy: {report['warnings']['emissions']} advisory warnings "
          f"at {len(sites)} sites in {len(expected)} sources")
    for check, count in counts.most_common(10):
        print(f"  {count}  {check}")
    print("See the analyzer step log for all diagnostic locations")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError, TypeError, RuntimeError,
            subprocess.CalledProcessError) as error:
        print(f"Native clang-tidy report failed: {error}", file=sys.stderr)
        sys.exit(1)
