# Daily Market Reference approval workflow

This is the repository-supported workflow for one Asia/Taipei trading day. A builder command always fails closed on source, eligibility, capacity, checksum, or approval violations.

## D-1 candidate

After the prior close, capture the five official documents, then create a review-only candidate.
Choose the trading day and actual source/output paths before running these examples. The values
below are placeholders, not a retained source set or an existing approved artifact:

```bash
trading_day=YYYY-MM-DD
source_dir=/absolute/path/to/captured-official-documents
previous_artifact=/absolute/path/to/previous-approved/market_reference.json
review_root=/absolute/path/to/market-reference-review

candidate_args="candidate --trading-day $trading_day"
candidate_args+=" --source-dir \"$source_dir\""
candidate_args+=" --previous-artifact \"$previous_artifact\""
candidate_args+=" --output-dir \"$review_root\""
./gradlew :tools:market-reference-builder:run --args="$candidate_args"
```

Select a real prior approved artifact when preserving stable routing. For an initial baseline
without one, omit the `--previous-artifact` argument. Source requirements and both source modes
are described in the [builder guide](../../config/market-reference/README.md).
The candidate is retained outside the approved tree, under the chosen review root:

```text
<review_root>/preliminary/YYYY-MM-DD/
├── preliminary_market_reference_candidate.json
├── candidate-content.sha256
└── candidate-review.json
```

It includes the instrument universe, eligibility, stable routing, route diff, and bounded summary, but strips final reference and limit prices. It has no ConfigMap or OCI delivery plan and cannot open the market.

## Final build and approval evidence

On the trading day, acquire a fresh source set, inspect the candidate/final review information,
and perform the final command with the approving operator's identity. Live acquisition must use
the trading day applicable to those live sources. To reproduce an earlier date, use its retained
complete source set via `--source-dir` instead of `--fetch-live`.

```bash
approved_root=config/market-reference/approved
approved_by=YOUR_OPERATOR_ID

final_args="final --trading-day $trading_day --fetch-live"
final_args+=" --previous-artifact \"$previous_artifact\""
final_args+=" --approved-root \"$approved_root\""
final_args+=" --approved-by \"$approved_by\""
./gradlew :tools:market-reference-builder:run --args="$final_args"
```

The final command requires exactly one source mode, a trading day, an approved-artifact root, and `--approved-by`. It validates that the recorded approval time is not in the future, verifies the canonical bytes against the external checksum, and refuses to overwrite an approved directory for the same day.

`approval-report.json` records the artifact identity, operator and approval time, full source provenance, and a bounded review summary. That summary contains eligible/unsupported totals, additions/removals, eligibility and route changes, all partition loads, size, hash, selected delivery type, and validation results. It therefore makes a decision reviewable without asking an operator to inspect every instrument row.

## Delivery gate

Only a `FINAL` artifact with a generated delivery plan is deployable. Apply its ConfigMap or OCI
data image to Risk and Matching at the same standard mount path before startup. Their startup
loaders already validate the shared artifact as described in the
[artifact contract](../reference/market-reference-artifact-contract.md); runtime readiness and full
[deployment verification](../verification/local-production-like-certification.md) belong to those
workloads and their runner. A successful builder run alone does not open trading.
