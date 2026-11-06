#!/usr/bin/env bash

# Legacy SQL fixture-selection contract retained for QueryCollectorPostgresContractTest.
# Current deployed Query verification is owned by the Jupiter resting-buy scenario.
query_postgres_json() {
  local sql="$1"
  local destination="$2"
  local postgres
  postgres="$(postgres_pod)"
  [[ -n "$postgres" ]] || return 1
  kns exec "$postgres" -c postgres -- psql -U simplematch -d simplematch -At \
    -v ON_ERROR_STOP=1 -c "$sql" >"$destination"
  jq -e . "$destination" >/dev/null
}

select_query_fixture() {
  local fixture_order fixture_account fixture_day fixture_venue fixture_symbol selectors
  # Select only this run's known public FIX order; never broaden grants for arbitrary database rows.
  selectors="$(jq -er -s \
    --slurpfile identities "${query_fixture_directory:?approved Query fixture bundle is required}/fixture-identities.json" '
      def canonical_uuid: type == "string" and
        test("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
      select(length == 1 and ($identities | length) == 1) | .[0]
      | select(.fixtureType == "PUBLIC_FIX_CROSS")
      | select(.buy.accountId == $identities[0].accounts.X
          and .sell.accountId == $identities[0].accounts.Y
          and .buy.accountId != .sell.accountId)
      | select([.buy.accountId,.sell.accountId,.buy.orderId,.sell.orderId] | all(.[]; canonical_uuid))
      | select(.tradingDay | type == "string" and test("^[0-9]{4}-[0-9]{2}-[0-9]{2}$"))
      | select(.venueMic == "XTAI" or .venueMic == "ROCO")
      | select(.symbol | type == "string" and test("^[A-Z0-9]{1,12}$"))
      | [.buy.orderId,.buy.accountId,.tradingDay,.venueMic,.symbol] | @tsv
    ' "$evidence_dir/matching-fixture/fixture.json")" || return 1
  IFS=$'\t' read -r fixture_order fixture_account fixture_day fixture_venue fixture_symbol \
    <<<"$selectors" || return 1
  query_postgres_json "
    SELECT json_build_object(
      'orderId', execution.order_id,
      'accountId', execution.account_id,
      'tradingDay', reference.trading_day,
      'artifactId', reference.artifact_id,
      'venueMic', TRIM(execution.venue_mic),
      'symbol', execution.symbol
    )
    FROM query_service.execution_read_model execution
    JOIN query_service.order_read_model orders
      ON orders.order_id = execution.order_id
     AND orders.account_id = execution.account_id
     AND orders.venue_mic = execution.venue_mic
     AND orders.symbol = execution.symbol
    JOIN query_service.account_summary_read_model account
      ON account.account_id = execution.account_id
    JOIN query_service.active_market_reference reference
      ON reference.venue_mic = execution.venue_mic
     AND reference.symbol = execution.symbol
    WHERE execution.order_id = '$fixture_order'
      AND execution.account_id = '$fixture_account'
      AND execution.venue_mic = '$fixture_venue'
      AND execution.symbol = '$fixture_symbol'
      AND reference.trading_day = '$fixture_day'
    ORDER BY execution.executed_at_unix_ms DESC, execution.execution_id
    LIMIT 1;
  " "$evidence_dir/selected-fixture.json" || return 1
  jq -e -s 'length == 1 and (.[0] | type == "object")' \
    "$evidence_dir/selected-fixture.json" >/dev/null
}
