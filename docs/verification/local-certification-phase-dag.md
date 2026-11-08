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
The selected 2026-11-06 Live result is historical, not a substitute for P6.
The fresh 2026-11-08 complete-local run against executable source commit
`1a3fa704f37873acacab54415ce794171fd4456a` passed `verifyAll` in
37m17s (194 Gradle tasks), including 81/81 Native CTest checks and the
run-owned dedicated Kubernetes `resting-buy` scenario. Run
`6364220d-c45a-43e2-9100-daf098e78046` records all four summary fields
as `PASS` in `out/verification/kubernetes/7458c622-11ad-4cdd-8f83-69bc519582ef/summary.json`;
the run-owned namespace was cleaned up. It does not certify production HA,
other separately selected cluster modes, or the remaining S6 hosted CI scope.

## Historical evidence and focused diagnostics

Older phase reports remain attributable to their original source, run, and image identities.
The historical schema version 2 records `run-context`, `source-revision`,
`phases/<phase-id>/result.json`, `evidence-manifest.json`, and image lock files.
The former `run-local-cdc-delivery-focused-diagnostic.sh` accepted only source-compatible
legacy evidence. P5b removed the aggregate and changed its runtime provenance. No
current runner produces compatible retained evidence. Issue #202 therefore retired the
focused continuation and its separate Risk CDC observer. These commands are not available.
`verifyCdc` retains exact publication and broker/connector recovery checks in Testcontainers.
It does not verify the old Kubernetes Risk CDC lag/Actuator recovery or observer cleanup.

## Intentional coverage loss

The retired graph no longer verifies a separate Compose Kafka/PostgreSQL/Redis/Connect
lifecycle; Compose startup, cleanup, broker/topic inspection and capacity evidence;
standalone local resource-budget, image publication/import and 15-owner reports;
or Shell partial profiles and per-phase aggregate results. The surviving native,
Java, image, dependency, and Kubernetes owners verify only their stated assertions.
No Compose-only PASS or full-local result is inferred from their tests.

See the [operator runbook](local-production-like-certification.md),
[S5 disposition](../plans/shell-test-reduction-s5.md), and dated evidence.
