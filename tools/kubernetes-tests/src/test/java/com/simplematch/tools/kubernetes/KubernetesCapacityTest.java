package com.simplematch.tools.kubernetes;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class KubernetesCapacityTest {
  private static final long GIB = 1_073_741_824L;
  private static final long REQUIRED = 12 * GIB + GIB / 2;

  @Test
  void desktopUsesAvailableGuestMemoryAndSeparateHostHeadroom() {
    assertDoesNotThrow(() -> new KubernetesCapacity.Snapshot(2 * GIB, 35 * GIB,
        37 * GIB, 16).require(REQUIRED, true));
  }

  @Test
  void nativeDockerRequiresTheCompleteBudgetInHostAvailableMemory() {
    assertThrows(IllegalStateException.class, () -> new KubernetesCapacity.Snapshot(
        8 * GIB, 8 * GIB, 38 * GIB, 16).require(REQUIRED, false));
    assertDoesNotThrow(() -> new KubernetesCapacity.Snapshot(20 * GIB, 20 * GIB,
        38 * GIB, 16).require(REQUIRED, false));
  }

  @Test
  void desktopStillRejectsInsufficientGuestHostAndConfiguredCapacity() {
    for (var snapshot : java.util.List.of(
        new KubernetesCapacity.Snapshot(8 * GIB, 10 * GIB, 37 * GIB, 16),
        new KubernetesCapacity.Snapshot(GIB / 2, 35 * GIB, 37 * GIB, 16),
        new KubernetesCapacity.Snapshot(8 * GIB, 35 * GIB, 10 * GIB, 16),
        new KubernetesCapacity.Snapshot(8 * GIB, 35 * GIB, 37 * GIB, 4))) {
      assertThrows(IllegalStateException.class, () -> snapshot.require(REQUIRED, true));
    }
  }
}
