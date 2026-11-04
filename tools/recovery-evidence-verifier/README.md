# Recovery evidence verifier

This executable owns the data rules for dependency recovery and Market Data rebuild evidence.
It uses the project's existing Jackson and JUnit libraries.
The existing diagnostic commands collect live observations, enforce resource ownership, and restore their resources.

## Build and test

```shell
./gradlew :tools:recovery-evidence-verifier:test :tools:recovery-evidence-verifier:installDist
```

The Java conventions select the JDK and quality checks.
Java CI includes this module in its normal test and static-analysis tasks.
The Lifecycle CI collection runs its tests once.

## Check evidence

```shell
verifier=tools/recovery-evidence-verifier/build/install/recovery-evidence-verifier/bin/recovery-evidence-verifier
"$verifier" dependency postgresql REPORT_FILE
"$verifier" dependency redis REPORT_FILE
"$verifier" dependency kafka REPORT_FILE
"$verifier" market-data EVIDENCE_DIRECTORY VERDICT_FILE
```

Successful commands print their checked local scope and exit with zero.
Rejected evidence and invalid arguments exit with one.
Inputs must contain one JSON object. Duplicate fields and trailing JSON are invalid.
Linked files must remain inside the evidence directory. Symlinks and path traversal are invalid.

Dependency reports use schema version 2.
Keep earlier reports as historical records. Create new evidence for the current source instead of changing old reports.

## Responsibility boundaries

| Operation | Necessary rules | Runtime owner |
| --- | --- | --- |
| Dependency | Pod or worker fault identity, PostgreSQL durable storage and marker, Redis disposable state, Kafka storage, ISR and catch-up | `run-local-resilience-dependencies.sh` |
| Market Data | Seven named isolation, deterministic-rebuild, repair, and restoration checks from five retained JSON files | `end-to-end/market-data/run-certification.sh` |

Exact claim sentences, copied summary fields, and unused report readers are absent.
The focused Kubernetes Connect worker-loss verifier and its exclusive commands
were retired by Issue #202. Their original reports remain historical
evidence, not inputs supported by this executable.

The standalone Query placement verifier and its report-shape fixtures were
retired under Issue #202. The Query deployment runner now observes Pod health,
identity and authenticated serving during the existing Redis outage.

## Market Data verdict

The Market Data command reads `critical-before.json`, `critical-after.json`, `baseline-snapshot.json`,
`rebuilt-snapshot.json`, and `restoration.json` from the supplied evidence directory.
It writes the existing `status` and seven-entry `checks` JSON contract.
The check names remain `criticalConsumerIsolation`, `deterministicRebuild`, `redisRepair`,
`projectionRestored`, `streamerRestored`, `matchingIsolation`, and `criticalConsumersRestored`.

Successful evaluation exits zero. Failed checks write a FAIL verdict and exit one.
Missing or malformed input and output errors exit one.
The command removes an earlier destination before reading evidence, so invalid input cannot preserve an earlier PASS there.
The destination cannot use one of the five input names.
Required snapshot fields and consumer progress arrays must exist. Equal missing facts cannot establish success.
Numeric JSON representations compare by value. Array order and business values remain significant.
Snapshot observation fields outside the ten business fields do not affect deterministic equality.

The live runner builds the executable before environment changes and evaluates a pending verdict.
It publishes the final PASS only after successful environment restoration.
The evaluator does not independently prove current source, image, namespace identity, or live recovery.

## Scope

Fixture tests establish local regression coverage.
They do not establish a new deployed recovery, aggregate certification, or production HA result.
The [E assessment](../../docs/plans/category-e-reassessment.md) records each migration, deletion, and retained responsibility.
