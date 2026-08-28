# Local Certification Reuse Measurement

This procedure measures the operational effect of Phase-DAG reuse for Issue
#185. It is a measurement wrapper around the existing full production-like
certification runner; it is not another certification pipeline.

The command runs two independent full certifications:

1. **cold** — uses a new, empty reusable certification-evidence cache;
2. **warm** — uses the cache produced by the cold run with otherwise unchanged
   source and configuration.

Both runs still create fresh runtime state and execute every phase whose policy
is `FRESH`.

## Preconditions

The measurement requires:

- a clean Git working tree;
- the canonical local kind cluster and Kubernetes context expected by
  `run-local-production-like-certification.sh`;
- no conflicting retained production-like certification namespace;
- an approved Market Reference delivery manifest for the selected trading day;
- the same source revision throughout both runs.

The measurement intentionally does **not** clear Docker, Gradle, compiler, or
other machine build caches. The experiment isolates the effect of the new
certification evidence cache. Clearing unrelated caches would measure a
machine-cold build instead of the incremental certification design.

## Run

Use an approved trading day:

```bash
scripts/measure-local-certification-reuse.sh \
  --trading-day YYYY-MM-DD
```

The command accepts the same local image identity choices through:

```text
--tag TAG
--image-transport registry|kind-load
```

A custom measurement directory may be supplied with `--output-dir`. Otherwise
evidence is written below:

```text
out/certification-performance/<measurement-id>/
```

The wrapper does not use `--keep-resources`. Each underlying certification owns
and cleans its disposable runtime before the next run starts.

## Validation

The measurement fails closed unless both underlying certifications produce a
full `PASSED` result.

The cold plan must contain no reuse because its isolated certification cache
starts empty. Every required phase must therefore plan `EXECUTE`.

The unchanged warm plan must satisfy the declared policy exactly:

| Policy | Required warm decision |
| --- | --- |
| `FRESH` | `EXECUTE` |
| `CONTENT_ADDRESSED` | `REUSE` |
| `REVALIDATE` | `REVALIDATE` |

Any `SKIP`, reusable phase execution, or FRESH phase reuse fails the
measurement rather than being hidden by aggregate wall-clock timing.

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
summary.json             machine-readable comparison
report.md                human-readable comparison
```

`summary.json` records:

- exact source revision and trading day;
- cold and warm wall-clock milliseconds;
- wall-clock milliseconds saved and reduction percentage;
- planner decision counts;
- recorded per-phase durations;
- warm FRESH phases ranked by duration;
- the rank and FRESH-execution share of `kafka-broker-failure-live`.

The measurement verdict is `PASS` only when the structurally valid warm run is
faster than the cold run.

## Environment-fault decision

Issue #185 deliberately does not make environment-fault evidence reusable.
This measurement determines whether that work deserves a separate design.

If `kafka-broker-failure-live` is the largest recorded FRESH phase, the report
sets:

```text
environmentFault.followUpIdentitySpecificationRecommended = true
```

That result justifies a separate specification for a phase-specific environment
identity and revalidation rule before any broker-failure evidence reuse is
implemented. It does not justify weakening the current `FRESH` policy directly.

If another FRESH phase is larger, the report records the ranking instead and no
environment-fault reuse work is justified by this measurement alone.

The same ranking can be used to decide whether bounded DAG parallelism deserves
separate work. Parallel execution should be considered only when remaining
independent FRESH phases dominate warm-run wall-clock time.
