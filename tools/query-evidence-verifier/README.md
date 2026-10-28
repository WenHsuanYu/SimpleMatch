# Query evidence verifier

This executable evaluates retained Query certification evidence.
It preserves the twelve checks and the `query-security-phase1/2` verdict contract.
It does not contact services or change deployment resources.

## Evaluate evidence

1. Build the executable with Java 25:

   ```bash
   ./gradlew -q :tools:query-evidence-verifier:installDist
   ```

2. Provide the evidence directory and the verdict destination:

   ```bash
   tools/query-evidence-verifier/build/install/query-evidence-verifier/bin/query-evidence-verifier \
     /path/to/evidence /path/to/verdict.json
   ```

The Query certification runner builds this executable before it changes the environment.
The runner publishes a successful verdict only after environment restoration.

| Exit status | Meaning |
| --- | --- |
| `0` | All twelve checks pass. The executable writes a PASS verdict. |
| `1` | At least one check fails. The executable writes a FAIL verdict. |
| `2` | Arguments, input files, or output operations fail. The caller must reject the evaluation. |

The executable removes an existing destination before evaluation.
Invalid input therefore cannot preserve a previous PASS at that destination.
Missing active-processing evidence produces FAIL with `activeProcessingLiveness.status` set to `NOT_PROVEN`.

## Evidence interface

The directory must contain these JSON objects:

- `critical-before.json` and `critical-during-query-outage.json`
- `query-outage.json` and `critical-query-isolation-probe.json`
- `baseline.json`, `redis-outage.json`, and `rebuilt.json`
- `restoration.json`
- `read-matrix.json`, `legacy-cache.json`, and `operator-boundary.json`

`active-processing-liveness.json` must prove the public IOC path for the verdict to pass.
Each input file contains one object without duplicate keys or trailing JSON documents.
Other retained files do not affect evaluation.
Observation timestamps within business views do not affect rebuild equality.

The [Query certification contract](../../services/docs/query-service/certification-integration.md) defines the requirements and their evidence limits.
The evaluator cannot establish that supplied evidence describes the current environment.
The runner owns current source, namespace, and image validation.

## Tests and dependencies

```bash
./gradlew -q :tools:query-evidence-verifier:check
```

The tests exercise evidence acceptance, rejection, and command exit status.
They use the existing synthetic contract examples as JSON data.
They do not reproduce temporary debugging probes or test each Jackson parsing rule.
Jackson and JUnit use the existing version catalog and Spring Boot BOM.
The tool requires no Spring service runtime, database, Docker, Testcontainers, or additional waiting library.
