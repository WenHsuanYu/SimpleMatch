package com.simplematch.queryservice.store;

import com.simplematch.marketreference.ArtifactIdentity;
import com.simplematch.marketreference.ArtifactInstrument;
import com.simplematch.marketreference.ArtifactReleaseState;
import com.simplematch.marketreference.InstrumentEligibility;
import com.simplematch.marketreference.RoutingAssignment;
import com.simplematch.marketreference.VerifiedMarketReferenceArtifact;
import java.sql.Date;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcOperations;

/** Replaces active market-reference rows from one verified final artifact. */
final class JdbcQueryMarketReferenceProjection {
  private static final String INSERT_COLUMNS =
      "(trading_day, artifact_id, venue_mic, symbol, market_rule_id, "
          + "reference_price_units, lower_price_limit_units, upper_price_limit_units, "
          + "routing_partition, updated_at_unix_ms)";
  private static final String VALUES = "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

  private JdbcQueryMarketReferenceProjection() {}

  static void install(
      JdbcOperations jdbcTemplate,
      VerifiedMarketReferenceArtifact verifiedArtifact,
      long installedAtUnixMs) {
    final ArtifactIdentity identity = verifiedArtifact.identity();
    if (verifiedArtifact.artifact().metadata().releaseState() != ArtifactReleaseState.FINAL) {
      throw new IllegalArgumentException("only a final market-reference artifact is queryable");
    }
    jdbcTemplate.update(
        "DELETE FROM query_service.active_market_reference WHERE trading_day = ?",
        Date.valueOf(identity.tradingDay()));
    final Map<com.simplematch.marketreference.InstrumentRef, Integer> assignments =
        verifiedArtifact.artifact().routingPolicy().assignments().stream()
            .collect(
                Collectors.toUnmodifiableMap(
                    RoutingAssignment::instrument, RoutingAssignment::partitionId));
    for (ArtifactInstrument instrument :
        verifiedArtifact.artifact().marketSnapshot().instruments()) {
      if (instrument.eligibility() != InstrumentEligibility.ELIGIBLE) {
        continue;
      }
      final Integer routingPartition = assignments.get(instrument.instrument());
      if (routingPartition == null) {
        throw new IllegalArgumentException(
            "eligible market-reference instrument has no routing assignment: "
                + instrument.instrument());
      }
      insert(jdbcTemplate, identity, instrument, routingPartition, installedAtUnixMs);
    }
  }

  private static void insert(
      JdbcOperations jdbcTemplate,
      ArtifactIdentity identity,
      ArtifactInstrument instrument,
      Integer routingPartition,
      long installedAtUnixMs) {
    final Object[] values = {
      Date.valueOf(identity.tradingDay()),
      identity.value(),
      instrument.instrument().venueMic(),
      instrument.instrument().symbol(),
      instrument.marketRuleId(),
      instrument.referencePriceUnits(),
      instrument.lowerPriceLimitUnits(),
      instrument.upperPriceLimitUnits(),
      routingPartition,
      installedAtUnixMs
    };
    if (isPostgres(jdbcTemplate)) {
      jdbcTemplate.update(
          "INSERT INTO query_service.active_market_reference "
              + INSERT_COLUMNS
              + " "
              + VALUES
              + " ON CONFLICT (trading_day, venue_mic, symbol) DO UPDATE SET "
              + "artifact_id = EXCLUDED.artifact_id, market_rule_id = EXCLUDED.market_rule_id, "
              + "reference_price_units = EXCLUDED.reference_price_units, "
              + "lower_price_limit_units = EXCLUDED.lower_price_limit_units, "
              + "upper_price_limit_units = EXCLUDED.upper_price_limit_units, "
              + "routing_partition = EXCLUDED.routing_partition, "
              + "updated_at_unix_ms = EXCLUDED.updated_at_unix_ms",
          values);
    } else {
      jdbcTemplate.update(
          "MERGE INTO query_service.active_market_reference "
              + INSERT_COLUMNS
              + " KEY(trading_day, venue_mic, symbol) "
              + VALUES,
          values);
    }
  }

  private static boolean isPostgres(JdbcOperations jdbcTemplate) {
    return Boolean.TRUE.equals(
        jdbcTemplate.execute(
            (ConnectionCallback<Boolean>)
                connection ->
                connection
                    .getMetaData()
                    .getDatabaseProductName()
                    .toLowerCase(java.util.Locale.ROOT)
                    .contains("postgresql")));
  }
}
