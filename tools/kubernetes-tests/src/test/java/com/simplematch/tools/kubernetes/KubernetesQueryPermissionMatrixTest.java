package com.simplematch.tools.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Ensures the selected Jupiter matrix retains every Local Lab authorization contract. */
class KubernetesQueryPermissionMatrixTest {
  @Test
  void completeCYDAndFivePrincipalMatrixChangesOnlyTheSpecifiedGrantsAndStatus() {
    final var matrix = KubernetesQueryPermissionMatrix.class;
    assertEquals(5, KubernetesQueryPermissionMatrix.ALIASES.size());
    for (int version = 1; version <= 3; version++) {
      for (String alias : KubernetesQueryPermissionMatrix.ALIASES) {
        final boolean disabled = version == 3 && alias.equals("client-d");
        for (String account : List.of("X", "Y")) {
          final boolean granted = switch (alias) {
            case "client-a" -> account.equals("X");
            case "client-b" -> account.equals("Y");
            case "client-c" -> account.equals("X") || version == 1;
            default -> false;
          };
          for (String resource : List.of("account", "order", "executions")) {
            assertEquals(disabled ? 401 : granted ? 200 : 404,
                KubernetesQueryPermissionMatrix.expected(version, alias, account, resource),
                alias + " " + version + " " + resource + " " + account);
          }
        }
        assertEquals(disabled ? 401 : 404,
            KubernetesQueryPermissionMatrix.expected(version, alias, "none", "missing-parent"));
        assertEquals(disabled ? 401 : alias.equals("operations") ? 200 : 403,
            KubernetesQueryPermissionMatrix.expected(version, alias, "none", "freshness"));
        assertEquals(disabled ? 401 : 200,
            KubernetesQueryPermissionMatrix.expected(version, alias, "none", "market"));
      }
    }
    assertThrows(IllegalArgumentException.class,
        () -> KubernetesQueryPermissionMatrix.expected(4, "client-c", "X", "account"));
    assertThrows(IllegalArgumentException.class,
        () -> KubernetesQueryPermissionMatrix.expected(2, "outsider", "X", "account"));
    assertThrows(IllegalArgumentException.class,
        () -> KubernetesQueryPermissionMatrix.expected(2, "client-a", "Z", "account"));
  }

  @Test
  void matrixRejectsAmbiguousSyntheticOrIncompleteSourceFixtures() {
    final UUID x = UUID.randomUUID();
    final UUID y = UUID.randomUUID();
    final UUID missing = UUID.randomUUID();
    final var orders = List.of(
        new KubernetesQueryPermissionMatrix.OrderFixture("X", UUID.randomUUID(), false),
        new KubernetesQueryPermissionMatrix.OrderFixture("X", UUID.randomUUID(), true),
        new KubernetesQueryPermissionMatrix.OrderFixture("Y", UUID.randomUUID(), false),
        new KubernetesQueryPermissionMatrix.OrderFixture("Y", UUID.randomUUID(), true));
    final var resources = new KubernetesQueryPermissionMatrix.Resources(x, y, orders, missing,
        "2026-08-17", "XTAI", "1101", "approved-artifact", Map.of("X", 0L, "Y", 3L));
    assertEquals(4, resources.orders().size());
    assertThrows(IllegalArgumentException.class,
        () -> new KubernetesQueryPermissionMatrix.Resources(x, x, orders, missing,
            "2026-08-17", "XTAI", "1101", "artifact", Map.of("X", 0L, "Y", 3L)));
    assertThrows(IllegalArgumentException.class,
        () -> new KubernetesQueryPermissionMatrix.Resources(x, y,
            List.of(orders.get(0), orders.get(1), orders.get(2), orders.get(2)), missing,
            "2026-08-17", "XTAI", "1101", "artifact", Map.of("X", 0L, "Y", 3L)));
    assertThrows(AssertionError.class,
        () -> new KubernetesQueryPermissionMatrix.Resources(x, y,
            List.of(orders.get(0), orders.get(1), orders.get(2),
                new KubernetesQueryPermissionMatrix.OrderFixture("Y", UUID.randomUUID(), false)),
            missing, "2026-08-17", "XTAI", "1101", "artifact", Map.of("X", 0L, "Y", 3L)));
  }
}
