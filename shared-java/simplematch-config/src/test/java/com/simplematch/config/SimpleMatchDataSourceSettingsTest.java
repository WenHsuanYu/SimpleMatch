package com.simplematch.config;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class SimpleMatchDataSourceSettingsTest {
  @Test
  void rejectsInvalidServicePoolPolicy() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new SimpleMatchDataSourceSettings(" ", 4, "account"));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new SimpleMatchDataSourceSettings("account_service", 0, "account"));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new SimpleMatchDataSourceSettings("account_service", 4, " "));
  }
}
