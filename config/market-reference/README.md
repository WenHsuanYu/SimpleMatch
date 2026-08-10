# Approved Market Reference artifacts

The offline builder writes one immutable final directory at `approved/YYYY-MM-DD/`. Each contains the canonical JSON, its external checksum, approval report, and generated delivery manifest. Do not place preliminary candidates in this tree: they are non-deployable review output and belong under the explicitly selected candidate output directory.

No sample final artifact is committed here. A checked-in sample could be mistaken for an approved trading-day authority; tests instead use the shared fixture under `shared-java/market-reference-contract/src/test/resources/market-reference-fixtures/`.
