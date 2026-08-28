# Local Certification Reuse Measurement

This procedure measures the operational effect of Phase-DAG reuse for Issue
#185. It is a measurement wrapper around the existing full production-like
certification runner; it is not another certification pipeline.

The command runs two independent full certifications:

1. **cold** — uses a new, empty reusable certification-evidence cache;
2. **warm** — uses the cache produced by the cold run with otherwise unchanged
   source and configuration.

Both runs create fresh runtime state and execute every phase whose policy is
`FRESH`.

## Preconditions

The measurement requires:

- a clean Git working tree;
- the canonical local kind cluster and Kubernetes context expected by
  `run-local-production-like-certification.sh`;
- no conflicting retained production-like certification namespace;
- an approved Market Reference delivery manifest for the selected trading day;
- the same source revision and clean source state throughout both runs.

The measurement intentionally does **not** clear Docker, Gradle, compiler, or
other machine build caches. The experiment isolates certification-evidence reuse
rather than a machine-cold build.

## Run

Use an approved trading day:

```bash
scripts/measure-local-certification-reuse.sh \
  --trading-day YYYY-MM-DD
```

The wrapper also accepts:

```text
--tag TAG
--image-transport registry|kind-load
--output-dir DIR
```

Without `--output-dir`, evidence is written below:

```text
out/certification-performance/<measurement-id>/
```

The wrapper does not use `--keep-resources`. Each certification owns and cleans
its disposable runtime before the next run starts.

## Structural validation

Both underlying certifications must produce a full `PASSED` result.

The cold plan uses an empty reusable evidence cache, so every required phase
must plan `EXECUTE`.

The unchanged warm plan must satisfy its declared policy:

| Policy | Required warm decision |
| --- | --- |
| `FRESH` | `EXECUTE` |
| `CONTENT_ADDRESSED` | `REUSE` |
| `REVALIDATE` | `REVALIDATE` |

Any `SKIP`, reusable phase execution, or FRESH phase reuse fails the
measurement.

## Acceptance verdict

The measurement has one machine-checkable acceptance criterion:

```text
NON_FRESH_WALL_CLOCK_NOT_DOMINANT
```

The current scheduler executes phases serially. Recorded phase timers end before
all reuse materialization, result persistence, reporting, and orchestration work
has necessarily completed, so summing reusable phase timers alone can
underestimate actual warm-run overhead.

The measurement therefore computes a conservative upper bound for non-FRESH
wall-clock work:

```text
non-fresh wall-clock = max(warm wall-clock - recorded FRESH phase time, 0)
```

This residual includes reusable lookup/revalidation/materialization work plus
other orchestration time not attributed to recorded FRESH phase execution.

Non-FRESH work is considered **dominant** when:

```text
non-fresh wall-clock >= recorded FRESH phase time
```

Equivalently, non-FRESH work occupies at least half of the observed warm
wall-clock represented by those two categories.

`acceptanceVerdict` is `PASS` only when recorded FRESH execution is present and
non-FRESH wall-clock work does not dominate. This directly evaluates the
architecture requirement that unchanged reusable work must not dominate the
warm-run critical path without relying on incomplete reusable phase timers.

## Wall-clock observation

One invocation produces one cold/warm pair. Its total wall-clock comparison is
reported as:

```text
IMPROVED
UNCHANGED
REGRESSED
```

This is an operational observation, not a statistical performance claim.
`summary.json` therefore records:

```json
{
  "wallClock": {
    "samplePairs": 1,
    "statisticalClaim": false
  }
}
```

The acceptance verdict does not become `PASS` merely because one warm sample is
faster than one cold sample. Conversely, scheduler or host noise in one pair
does not redefine the Phase-DAG composition criterion.

For host-level performance conclusions, repeat the measurement under comparable
machine load and compare the resulting observations. A separate statistical
benchmark should be specified only if the project later needs a performance SLA
or regression threshold.

## Evidence

The measurement directory contains:

```text
cache/                  isolated reusable evidence cache
cold/                   complete cold production-like run evidence
warm/                   complete warm production-like run evidence
cold.log                cold runner output
warm.log                warm runner output
cold-phases.json        normalized per-phase cold timing
warm-phases.json        normalized per-phase warm timing
summary.json            machine-readable comparison
report.md               human-readable comparison
```

`summary.json` records:

- exact source revision and trading day;
- cold and warm wall-clock milliseconds;
- wall-clock observation, saved milliseconds, and reduction percentage;
- planner decision counts;
- recorded warm FRESH phase milliseconds;
- recorded reusable phase and planning milliseconds for diagnostics;
- conservative non-FRESH wall-clock residual and its warm-run share;
- whether non-FRESH wall-clock work dominates;
- warm FRESH phases ranked by duration;
- the rank and FRESH-execution share of `kafka-broker-failure-live`.

## Environment-fault decision

Issue #185 deliberately keeps environment-fault proof `FRESH`.

Being the largest FRESH phase is not enough to justify a new environment
identity. In this measurement, `kafka-broker-failure-live` is considered
**dominant** only when it consumes at least half of recorded FRESH execution
time:

```text
environmentFault.dominatesFreshExecution = true
```

Only that condition sets:

```text
environmentFault.followUpIdentitySpecificationRecommended = true
```

A dominant result justifies a separate specification for phase-specific
environment identity and revalidation before changing the existing `FRESH`
policy. It does not itself authorize reuse.

The same phase ranking can inform a later bounded-parallelism proposal, but
parallel execution remains separate work.
