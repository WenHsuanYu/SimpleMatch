# Market Reference official-source contract

The offline Market Reference builder is the only component that contacts market-data sources. Risk,
Matching, and their Kubernetes workloads never make an exchange or finance-site request at runtime.
Yahoo Finance is not an authority in this workflow.

## Required official documents

Each build reads exactly these documents. `--fetch-live` downloads them from the listed official
endpoint; `--source-dir` instead reads the exact filename from a previously captured directory. In
both modes the artifact records the source URL, logical source date, retrieval time, and SHA-256 of
the exact input bytes.

| Source ID | Official endpoint | Required fields | Capture filename |
| --- | --- | --- | --- |
| `twse-listed-companies` | [TWSE listed-company registry](https://openapi.twse.com.tw/v1/opendata/t187ap03_L) | `出表日期`, `公司代號` | `twse-companies.json` |
| `tpex-listed-companies` | [TPEx listed-company registry](https://www.tpex.org.tw/openapi/v1/mopsfin_t187ap03_O) | `Date`, `SecuritiesCompanyCode` | `tpex-companies.json` |
| `twse-daily-price-limits` | [TWSE daily limits](https://openapi.twse.com.tw/v1/exchangeReport/TWT84U) | `LastTradingDay`, `Code`, `TodayOpeningRefPrice`, `TodayLimitDown`, `TodayLimitUp` | `twse-daily-limits.json` |
| `tpex-daily-price-limits` | [TPEx daily close quotes](https://www.tpex.org.tw/openapi/v1/tpex_mainboard_daily_close_quotes) | `Date`, `SecuritiesCompanyCode`, `NextReferencePrice`, `NextLimitDown`, `NextLimitUp` | `tpex-daily-limits.json` |
| `twse-trading-calendar` | [TWSE trading calendar](https://openapi.twse.com.tw/v1/holidaySchedule/holidaySchedule) | `Date`, `Name`, `Description` | `twse-trading-calendar.json` |

The unit fixtures use those filenames and the CLI accepts the same layout, so a captured source set
can be reproduced without a network dependency.

## Eligibility and reconciliation

The builder's universe is each venue's company registry; price/limit documents enrich and validate
that fixed universe. A price-only entry (for example, a warrant or derivative) is outside the
company-stock scope and is not emitted as an artifact row. A Phase 1 eligible instrument is a
company-registry symbol matching `[0-8][0-9]{3}` that has a usable official price band: it is routed
as an XTAI or ROCO regular-board common stock.

Known entries outside that set remain in the artifact as unsupported and never receive a route:

- a symbol beginning with `9` is recorded as `TDR`;
- a non-four-digit symbol is `NON_REGULAR_SYMBOL`;
- a four-digit company outside the accepted class is `UNSUPPORTED_SECURITY_CLASS`;
- a company with no current official price row is `NO_CURRENT_PRICE_FACTS`; and
- a company whose row has no usable lower/reference/upper price band is
  `NO_TRADABLE_PRICE_BAND`.

This rule is intentionally narrow. It is the repository's explicit Phase 1 definition, not an
attempt to infer all future Taiwan security classes from one exchange field.

## Fail-closed date contract

The official calendar must cover the requested year and identify the requested Asia/Taipei date as a
trading day. Both daily price documents must identify the calendar's immediately preceding trading
day. Company registries may be at most seven calendar days old and may not be dated after the target
day. Duplicate rows, inconsistent dates within one document, malformed prices, an incomplete
eligible price-source date contract, or a source-date mismatch stop the build. A known company with
no usable price facts is retained as explicitly unsupported, so it cannot silently receive a route
or be traded that day.

All final eligible price values are normalized to whole 1/10,000 TWD units. The final artifact
requires a positive lower limit below the reference price and a positive upper limit above it.

## Operational use

For a captured source set, run:

```bash
./gradlew :tools:market-reference-builder:run --args='candidate \
  --trading-day 2026-08-11 \
  --source-dir /secure/captures/2026-08-11 \
  --output-dir /secure/market-reference-review'
```

For an online acquisition, replace `--source-dir ...` with `--fetch-live`. The command is a
controlled offline operation; it does not add a scheduled service or a runtime polling dependency.

The source-contract tests use deterministic captures. A live build is an operator/integration check:
it should be run for the intended trading day and retained with the review evidence, because live
documents naturally change after the exchange updates them.
