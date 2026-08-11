# Approved Market Reference artifacts

The offline builder writes one immutable final directory at `approved/YYYY-MM-DD/`. Each contains the canonical JSON, its external checksum, approval report, and generated delivery manifest. Do not place preliminary candidates in this tree: they are non-deployable review output and belong under the explicitly selected candidate output directory.

No sample final artifact is committed here. A checked-in sample could be mistaken for an approved trading-day authority; tests instead use the shared fixture under `shared-java/market-reference-contract/src/test/resources/market-reference-fixtures/`.

## Run the builder

The entry point is `:tools:market-reference-builder:run`. The builder supports two commands:

- `candidate` creates a review-only D-1 artifact. It contains the instrument universe, eligibility,
  stable routing, and review summary, but is not deployable.
- `final` creates the approved trading-day artifact after source, checksum, eligibility, capacity,
  and operator-approval checks pass.

The builder accepts exactly one source mode: `--source-dir` for a previously captured source set, or
`--fetch-live` for the official TWSE and TPEx endpoints. A source directory must contain these five
files:

```text
twse-companies.json
tpex-companies.json
twse-daily-limits.json
tpex-daily-limits.json
twse-trading-calendar.json
```

### Candidate from a captured source set

```bash
./gradlew :tools:market-reference-builder:run \
  --args='candidate \
    --trading-day 2026-08-11 \
    --source-dir /secure/captures/2026-08-11 \
    --output-dir /secure/market-reference-review'
```

The candidate is written below:

```text
/secure/market-reference-review/preliminary/2026-08-11/
├── preliminary_market_reference_candidate.json
├── candidate-content.sha256
└── candidate-review.json
```

Use `--previous-artifact /secure/market-reference/approved/YYYY-MM-DD/market_reference.json` when
the route-diff review should compare against the previous approved artifact.

### Final artifact from official live sources

```bash
./gradlew :tools:market-reference-builder:run \
  --args='final \
    --trading-day 2026-08-11 \
    --fetch-live \
    --approved-root /secure/market-reference/approved \
    --approved-by trading-operator'
```

`final` requires `--approved-root` and `--approved-by`. Add `--previous-artifact` when a previous
approved artifact exists. If the final artifact exceeds 900 KiB, also provide a digest-pinned OCI
data image with `--oci-data-image registry.example.com/market-reference-data@sha256:<64-hex-digest>`.
The final command writes the canonical JSON, external checksum, approval report, and delivery plan
under the approved trading-day directory.

The builder prepares the artifact and delivery plan; it does not apply a Kubernetes ConfigMap or OCI
overlay and does not open the market. Apply the reviewed delivery to Risk and all Matching workloads
using the platform deployment procedure.

### Gradle cache setting

`GRADLE_USER_HOME=/tmp/simplematch-gradle-cache` is not required by the CLI. Omit it when the default
Gradle user home is writable:

```bash
./gradlew :tools:market-reference-builder:test
```

In a restricted environment where the default Gradle cache is read-only, prefix the command with a
writable cache location:

```bash
GRADLE_USER_HOME=/tmp/simplematch-gradle-cache \
./gradlew :tools:market-reference-builder:test
```

The same prefix can be used with the `run` commands above. This setting changes only Gradle's cache
location; it is not a Market Reference configuration value.
