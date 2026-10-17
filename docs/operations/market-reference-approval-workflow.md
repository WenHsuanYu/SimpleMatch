# Daily Market Reference approval workflow

This is the repository-supported workflow for one Asia/Taipei trading day. A builder command always fails closed on source, eligibility, capacity, checksum, or approval violations.

## D-1 candidate

After the prior close, capture the five official documents or invoke the builder's live source mode, then create a review-only candidate:

```bash
./gradlew :tools:market-reference-builder:run --args='candidate \
  --trading-day 2026-08-11 \
  --source-dir /secure/captures/2026-08-11 \
  --previous-artifact config/market-reference/approved/2026-08-08/market_reference.json \
  --output-dir /secure/market-reference-review'
```

The candidate is retained outside the approved tree:

```text
/secure/market-reference-review/preliminary/2026-08-11/
├── preliminary_market_reference_candidate.json
├── candidate-content.sha256
└── candidate-review.json
```

It includes the instrument universe, eligibility, stable routing, route diff, and bounded summary, but strips final reference and limit prices. It has no ConfigMap or OCI delivery plan and cannot open the market.

## Final build and approval evidence

On the trading day, acquire a fresh source set, inspect the candidate/final review information, and perform the final command with the approving operator's identity:

```bash
./gradlew :tools:market-reference-builder:run --args='final \
  --trading-day 2026-08-11 \
  --fetch-live \
  --previous-artifact config/market-reference/approved/2026-08-08/market_reference.json \
  --approved-root config/market-reference/approved \
  --approved-by trading-operator'
```

The final command requires exactly one source mode, a trading day, an approved-artifact root, and `--approved-by`. It validates that the recorded approval time is not in the future, verifies the canonical bytes against the external checksum, and refuses to overwrite an approved directory for the same day.

`approval-report.json` records the artifact identity, operator and approval time, full source provenance, and a bounded review summary. That summary contains eligible/unsupported totals, additions/removals, eligibility and route changes, all partition loads, size, hash, selected delivery type, and validation results. It therefore makes a decision reviewable without asking an operator to inspect every instrument row.

## Delivery gate

Only a `FINAL` artifact with a generated delivery plan is deployable. Its ConfigMap or OCI data image must be applied to every future Risk and Matching workload at the same mount path. The workloads' startup readiness integration is intentionally not part of this builder: it is tracked by #126 and #127. A successful builder run alone does not open trading.
