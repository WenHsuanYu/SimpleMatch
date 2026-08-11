package com.simplematch.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class GrpcPropertiesTest {
  @Test
  void localDefaultsKeepPlaintextTransport() {
    final GrpcProperties properties = new GrpcProperties(null);

    assertThat(properties.security().tlsEnabled()).isFalse();
    assertThat(properties.targets().accountService()).isEqualTo("dns:///account-service:50051");
  }

  @Test
  void tlsRequiresCompleteCertificateMaterial() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () -> new GrpcProperties.SecurityProperties(true, "/tls.crt", "", "/ca.crt"))
        .withMessageContaining("private-key");
  }
}
