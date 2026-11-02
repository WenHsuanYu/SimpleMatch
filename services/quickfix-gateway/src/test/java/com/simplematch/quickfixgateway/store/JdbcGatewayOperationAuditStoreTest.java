package com.simplematch.quickfixgateway.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.simplematch.config.testsupport.PostgresTestDatabase;
import com.simplematch.quickfixgateway.fix.GatewayAdmissionGate;
import com.simplematch.quickfixgateway.operations.GatewayOperation;
import com.simplematch.quickfixgateway.operations.GatewayOperationAudit;
import com.simplematch.quickfixgateway.operations.GatewayOperationOutcome;
import com.simplematch.quickfixgateway.operations.TradingReadiness;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class JdbcGatewayOperationAuditStoreTest {
  @Test
  void retainsTheOperatorCommandAndResultInTheGatewaySchema() {
    final DriverManagerDataSource dataSource = newDataSource();
    migrate(dataSource);
    final JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
    final JdbcGatewayOperationAuditStore store = new JdbcGatewayOperationAuditStore(jdbcTemplate);
    final UUID auditId = UUID.randomUUID();

    store.append(
        new GatewayOperationAudit(
            auditId,
            GatewayOperation.OPEN,
            "operator-1",
            "pre-open review complete",
            GatewayOperationOutcome.ACCEPTED,
            GatewayAdmissionGate.State.OPEN,
            TradingReadiness.OPEN_ELIGIBLE,
            Instant.parse("2026-08-11T01:00:00Z")));

    assertThat(
            jdbcTemplate.queryForObject(
                """
                SELECT operation || ':' || actor || ':' || outcome || ':' || gate_state
                FROM quickfix_gateway.gateway_operation_audit
                WHERE audit_id = ?
                """,
                String.class,
                auditId))
        .isEqualTo("OPEN:operator-1:ACCEPTED:OPEN");
  }

  private DriverManagerDataSource newDataSource() {
    return PostgresTestDatabase.create("quickfix-gateway");
  }

  private void migrate(DriverManagerDataSource dataSource) {
    Flyway.configure()
        .dataSource(dataSource)
        .schemas("quickfix_gateway")
        .defaultSchema("quickfix_gateway")
        .locations("classpath:db/migration/quickfix-gateway")
        .load()
        .migrate();
  }
}
