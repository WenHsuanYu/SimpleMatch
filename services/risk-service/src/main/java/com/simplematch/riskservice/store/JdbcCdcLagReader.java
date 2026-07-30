package com.simplematch.riskservice.store;

import static com.simplematch.riskservice.admission.AdmissionBackpressureException.Reason.METRIC_INVALID;
import static com.simplematch.riskservice.admission.AdmissionBackpressureException.Reason.METRIC_MISSING;
import static com.simplematch.riskservice.admission.AdmissionBackpressureException.Reason.METRIC_UNAVAILABLE;

import com.simplematch.riskservice.admission.AdmissionBackpressureException;
import com.simplematch.riskservice.admission.CdcLagReader;
import com.simplematch.riskservice.admission.CdcLagSnapshot;
import java.time.Instant;
import java.util.Objects;
import lombok.NonNull;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** JDBC adapter that reads CDC delivery-lag metrics owned by risk-service. */
public class JdbcCdcLagReader implements CdcLagReader {

  private static final String READ_LAG_SQL =
      """
                SELECT lag_events, updated_at_unix_ms
                FROM risk_service.cdc_delivery_lag
                WHERE metric_name = ?
            """;

  private final @NonNull JdbcTemplate jdbcTemplate;

  /**
   * Creates a reader backed by the risk-service data source.
   *
   * @param jdbcTemplate risk-service JDBC access
   */
  public JdbcCdcLagReader(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
  }

  @Override
  public CdcLagSnapshot read(String metricName) {
    final String requiredMetricName = requireMetricName(metricName);

    try {
      return jdbcTemplate.queryForObject(
          READ_LAG_SQL, getCdcLagSnapshotRowMapper(requiredMetricName), requiredMetricName);
    } catch (EmptyResultDataAccessException missingMetric) {
      throw new AdmissionBackpressureException(
          METRIC_MISSING, "CDC lag metric does not exist: " + requiredMetricName, missingMetric);
    } catch (IncorrectResultSizeDataAccessException invalidSize) {
      throw new AdmissionBackpressureException(
          METRIC_INVALID,
          "CDC lag metric query returned an invalid row count: " + requiredMetricName,
          invalidSize);
    } catch (DataAccessException unavailable) {
      throw new AdmissionBackpressureException(
          METRIC_UNAVAILABLE, "CDC lag metric cannot be read: " + requiredMetricName, unavailable);
    }
  }

  private static @org.jspecify.annotations.NonNull RowMapper<CdcLagSnapshot>
      getCdcLagSnapshotRowMapper(String requiredMetricName) {
    return (resultSet, rowNumber) -> {
      final Long lagEvents = resultSet.getObject("lag_events", Long.class);
      final Long updatedAtUnixMs = resultSet.getObject("updated_at_unix_ms", Long.class);

      if (lagEvents == null || updatedAtUnixMs == null) {
        throw new AdmissionBackpressureException(
            METRIC_INVALID, "CDC lag metric contains null fields: " + requiredMetricName);
      }

      if (lagEvents < 0 || updatedAtUnixMs < 0) {
        throw new AdmissionBackpressureException(
            METRIC_INVALID, "CDC lag metric contains negative fields: " + requiredMetricName);
      }

      return new CdcLagSnapshot(lagEvents, Instant.ofEpochMilli(updatedAtUnixMs));
    };
  }

  private String requireMetricName(String metricName) {
    if (metricName == null || metricName.isBlank()) {
      throw new IllegalArgumentException("metricName must not be blank");
    }

    return metricName;
  }
}
