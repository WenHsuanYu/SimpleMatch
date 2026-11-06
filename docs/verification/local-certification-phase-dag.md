# Retired local certification phase graph

Issue #202 S5 P5b retired the former 62-phase Shell runner, its graph, adapters, and aggregate reporter.
The only complete-local verification entry now is:

```shell
./gradlew --no-daemon verifyAll
```

`verifyAllScope` requires committed, unchanged tracked source, rejects non-private untracked
inputs and `kubernetesRetain=true`, and enforces the `full-native-dev` native preset,
the `resting-buy` Kubernetes scenario, and the S5 Shell inventory limits.
`verifyAll` selects Java suites, static analysis, native CTest, manifest checks,
dependency/Flyway tests, QuickFIX certification, and run-owned Kubernetes tests.
The complete task requires the final Kubernetes report to record
`status=PASS`, `assertions=PASS`, `diagnostics=PASS` and `cleanup=PASS`.
The selected 2026-11-06 Live result does not establish a final P6 `verifyAll` PASS.

## Historical evidence and focused diagnostics

Older phase reports remain attributable to their original source, run, and image identities.
The historical schema version 2 records `run-context`, `source-revision`,
`phases/<phase-id>/result.json`, `evidence-manifest.json`, and image lock files.
The retained `run-local-cdc-delivery-focused-diagnostic.sh` can validate only
source-compatible legacy evidence, including exact historical CDC prerequisites,
ownership, image locks and verifier signatures. P5b removed the original aggregate
and changed the hashed runtime provenance. Therefore pre-P5b evidence is incompatible
with the current code and intentionally fails closed. There is no current runner to
produce a compatible legacy aggregate. This diagnostic is a historical validation
tool, not a runnable continuation of a pre-P5b full-local run.

## Intentional coverage loss

The retired graph no longer verifies a separate Compose Kafka/PostgreSQL/Redis/Connect
lifecycle; Compose startup, cleanup, broker/topic inspection and capacity evidence;
standalone local resource-budget, image publication/import and 15-owner reports;
or Shell partial profiles and per-phase aggregate results. The surviving native,
Java, image, dependency, and Kubernetes owners verify only their stated assertions.
No Compose-only PASS or full-local result is inferred from their tests.

See the [operator runbook](local-production-like-certification.md),
[S5 disposition](../plans/shell-test-reduction-s5.md), and dated evidence.
