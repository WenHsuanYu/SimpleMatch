package com.simplematch.riskservice;

import static com.simplematch.riskservice.testsupport.H2TestDatabaseUrl.riskServiceUrl;
import static org.assertj.core.api.Assertions.assertThat;

import com.simplematch.config.EnvironmentProperties;
import com.simplematch.config.GrpcProperties;
import com.simplematch.config.PostgresProperties;
import com.simplematch.riskservice.admission.AdmissionOutboxFactory;
import com.simplematch.riskservice.bootstrap.RiskServiceRuntime;
import com.simplematch.riskservice.outbox.OutboxRepository;
import com.simplematch.riskservice.submission.SubmissionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
    properties = {
      "simplematch.risk-service.grpc.enabled=false",
      "simplematch.risk-service.scheduling-enabled=false",
      "spring.main.web-application-type=none"
    })
@ActiveProfiles("test")
class RiskServiceApplicationTest {
  @Autowired private ApplicationContext applicationContext;

  @Autowired private EnvironmentProperties environmentProperties;

  @Autowired private PostgresProperties postgresProperties;

  @Autowired private RiskServiceRuntime runtime;

  @DynamicPropertySource
  static void configureDataSource(DynamicPropertyRegistry registry) {
    registry.add("simplematch.postgres.dsn", () -> riskServiceUrl("riskcontext"));
  }

  // Verify that risk-service loads shared config, overrides the database DSN, and creates its
  // runtime on startup.
  // Scenario: start the Spring context with gRPC disabled and confirm the default environment, gRPC
  // port, and test database DSN.
  @DisplayName("risk-service loads shared config and runtime on startup")
  @Test
  void contextLoadsWithSharedConfig() {
    assertThat(environmentProperties.environment()).isEqualTo("test");
    assertThat(runtime.grpcPort()).isEqualTo(50052);
    assertThat(postgresProperties.dsn()).isEqualTo(riskServiceUrl("riskcontext"));
    assertThat(
            RiskServiceRuntime.from(
                    new GrpcProperties(
                        new GrpcProperties.GrpcTargetsProperties(
                            "dns:///account-service:50051", "dns:///risk-service:51052")))
                .grpcPort())
        .isEqualTo(51052);
  }

  @DisplayName("risk-service keeps admission and submission wiring on shared local infrastructure")
  @Test
  void contextWiresAdmissionAndSubmissionCollaborators() {
    assertThat(applicationContext.getBean(AdmissionOutboxFactory.class)).isNotNull();
    assertThat(applicationContext.getBean(SubmissionService.class)).isNotNull();
    assertThat(applicationContext.getBean(OutboxRepository.class)).isNotNull();
    assertThat(applicationContext.getBean(TransactionTemplate.class)).isNotNull();
  }
}
