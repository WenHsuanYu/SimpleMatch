package com.simplematch.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SimpleMatchUuidsTest {
  @DisplayName("uuidV7 returns version 7 UUID values")
  @Test
  void uuidV7ReturnsVersionSevenValues() {
    final UUID uuid = SimpleMatchUuids.uuidV7();

    assertEquals(7, uuid.version());
    assertEquals(2, uuid.variant());
  }

  @DisplayName("uuidV7 generates distinct values across calls")
  @Test
  void uuidV7GeneratesDistinctValuesAcrossCalls() {
    final UUID first = SimpleMatchUuids.uuidV7();
    final UUID second = SimpleMatchUuids.uuidV7();

    assertNotEquals(first, second);
  }
}