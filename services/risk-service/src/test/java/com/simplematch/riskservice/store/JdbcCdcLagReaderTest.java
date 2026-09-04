package com.simplematch.riskservice.store;

import static com.simplematch.riskservice.admission.AdmissionBackpressureException.Reason.METRIC_INVALID;
import static com.simplematch.riskservice.admission.AdmissionBackpressureException.Reason.METRIC_MISSING;
import static com.simplematch.riskservice.admission.AdmissionBackpressureException.Reason.METRIC_UNAVAILABLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.simplematch.riskservice.admission.AdmissionBackpressureException;
import com.simplematch.riskservice.admission.CdcLagSnapshot;
import java.sql.ResultSet;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** Verifies JDBC mapping and stable CDC lag read failures. */
class JdbcCdcLagReaderTest {

  @Test
  void readsLagAndUpdateTimestamp() throws Exception {
    final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);

    try (ResultSet resultSet = mock(ResultSet.class)) {

      when(resultSet.getObject("lag_events", Long.class)).thenReturn(12L);

      when(resultSet.getObject("updated_at_unix_ms", Long.class)).thenReturn(1_000L);

      when(jdbcTemplate.queryForObject(
              anyString(),
              ArgumentMatchers.<RowMapper<CdcLagSnapshot>>any(),
              eq("matching.commands")))
          .thenAnswer(
              invocation -> {
                final RowMapper<CdcLagSnapshot> mapper = invocation.getArgument(1);

                return mapper.mapRow(resultSet, 0);
              });
    }

    final JdbcCdcLagReader reader = new JdbcCdcLagReader(jdbcTemplate);

    final CdcLagSnapshot snapshot = reader.read("matching.commands");

    assertThat(snapshot.lagEvents()).isEqualTo(12L);

    assertThat(snapshot.updatedAt()).isEqualTo(Instant.ofEpochMilli(1_000L));
  }

  @Test
  void rejectsNullLagValue() throws Exception {
    final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);

    try (ResultSet resultSet = mock(ResultSet.class)) {

      when(resultSet.getObject("lag_events", Long.class)).thenReturn(null);

      when(resultSet.getObject("updated_at_unix_ms", Long.class)).thenReturn(1_000L);

      when(jdbcTemplate.queryForObject(anyString(), getAny(), eq("matching.commands")))
          .thenAnswer(
              invocation -> {
                final RowMapper<CdcLagSnapshot> mapper = invocation.getArgument(1);

                return mapper.mapRow(resultSet, 0);
              });
    }

    final JdbcCdcLagReader reader = new JdbcCdcLagReader(jdbcTemplate);

    assertThatThrownBy(() -> reader.read("matching.commands"))
        .isInstanceOf(AdmissionBackpressureException.class)
        .satisfies(
            error -> {
              final AdmissionBackpressureException failure = (AdmissionBackpressureException) error;

              assertThat(failure.reason()).isEqualTo(METRIC_INVALID);
            });
  }

  @Test
  void mapsMissingMetricToStableFailure() {
    final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);

    when(jdbcTemplate.queryForObject(anyString(), getAny(), eq("matching.commands")))
        .thenThrow(new EmptyResultDataAccessException(1));

    final JdbcCdcLagReader reader = new JdbcCdcLagReader(jdbcTemplate);

    assertThatThrownBy(() -> reader.read("matching.commands"))
        .isInstanceOf(AdmissionBackpressureException.class)
        .satisfies(
            error -> {
              final AdmissionBackpressureException failure = (AdmissionBackpressureException) error;

              assertThat(failure.reason()).isEqualTo(METRIC_MISSING);
            });
  }

  private static <T> RowMapper<T> getAny() {
    return ArgumentMatchers.any();
  }

  @Test
  void mapsDatabaseFailureToUnavailable() {
    final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);

    when(jdbcTemplate.queryForObject(anyString(), getAny(), eq("matching.commands")))
        .thenThrow(new DataAccessResourceFailureException("database unavailable"));

    final JdbcCdcLagReader reader = new JdbcCdcLagReader(jdbcTemplate);

    assertThatThrownBy(() -> reader.read("matching.commands"))
        .isInstanceOf(AdmissionBackpressureException.class)
        .satisfies(
            error -> {
              final AdmissionBackpressureException failure = (AdmissionBackpressureException) error;

              assertThat(failure.reason()).isEqualTo(METRIC_UNAVAILABLE);
            });
  }
}
