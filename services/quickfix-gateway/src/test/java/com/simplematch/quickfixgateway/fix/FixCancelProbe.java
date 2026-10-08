package com.simplematch.quickfixgateway.fix;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;
import quickfix.FieldNotFound;
import quickfix.Session;
import quickfix.field.Account;
import quickfix.field.ClOrdID;
import quickfix.field.ExecType;
import quickfix.field.OrdStatus;
import quickfix.field.OrderID;
import quickfix.field.OrderQty;
import quickfix.field.OrigClOrdID;
import quickfix.field.Symbol;
import quickfix.field.TransactTime;
import quickfix.fix44.ExecutionReport;
import quickfix.fix44.NewOrderSingle;
import quickfix.fix44.OrderCancelRequest;

/** Keeps the original real FIX session alive for one post-Matching-recovery cancel. */
final class FixCancelProbe {
  private FixCancelProbe() {}

  /** Releases a new cancel only after the runner observes actual Matching recovery and reopen. */
  static void completeIfRequested(
      FixRecoveryProbe.Exchange exchange, TerminalReportObserver observer, int timeoutSeconds)
      throws Exception {
    if (!"true".equals(System.getenv("SIMPLEMATCH_RETAINED_FIX_CANCEL"))) {
      return;
    }
    final Path release = environmentPath("SIMPLEMATCH_RETAINED_FIX_CANCEL_RELEASE");
    final long waitingDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
    while (!Files.exists(release) && System.nanoTime() < waitingDeadline) {
      Thread.sleep(20L);
    }
    assertThat(release).as("Matching recovery cancel release").exists();
    final long remaining = Long.parseLong(Files.readString(release).strip()) - System.currentTimeMillis();
    assertThat(remaining).as("post-recovery cancel deadline").isPositive();
    final String originalId = exchange.order().getString(ClOrdID.FIELD);
    final OrderCancelRequest request = cancelRequest(exchange.order(), "CAN-" + originalId);
    final long sentAtEpochMs = System.currentTimeMillis();
    assertThat(Session.sendToTarget(request, exchange.sessionId())).isTrue();
    // Final Matching reports address the original FIX order, not the cancel's admission identity.
    final ExecutionReport report = observer.await(originalId, Math.toIntExact((remaining + 999) / 1000));
    final Path path = environmentPath("SIMPLEMATCH_RETAINED_FIX_CANCEL_EVIDENCE");
    Files.createDirectories(path.getParent());
    final Path temporary = Files.createTempFile(path.getParent(), "cancel-", ".json");
    new ObjectMapper().writerWithDefaultPrettyPrinter()
        .writeValue(temporary.toFile(), evidence(request, report, sentAtEpochMs));
    Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
  }

  /** Uses a distinct ClOrdID without changing the original order's account or terms. */
  static OrderCancelRequest cancelRequest(NewOrderSingle order, String cancelId) throws FieldNotFound {
    final var request = new OrderCancelRequest();
    request.setString(ClOrdID.FIELD, cancelId);
    request.setString(OrigClOrdID.FIELD, order.getString(ClOrdID.FIELD));
    request.setString(Account.FIELD, order.getString(Account.FIELD));
    request.setString(Symbol.FIELD, order.getString(Symbol.FIELD));
    request.setString(OrderQty.FIELD, order.getString(OrderQty.FIELD));
    request.setChar(quickfix.field.Side.FIELD, order.getChar(quickfix.field.Side.FIELD));
    request.setField(new TransactTime(LocalDateTime.now(ZoneOffset.UTC)));
    return request;
  }

  /** Records request and report identities separately; a Pending Cancel is never success. */
  static ObjectNode evidence(OrderCancelRequest request, ExecutionReport report, long sentAtEpochMs)
      throws FieldNotFound {
    assertThat(report.getChar(ExecType.FIELD)).isEqualTo('4');
    assertThat(report.getChar(OrdStatus.FIELD)).isEqualTo('4');
    assertThat(report.getString(ClOrdID.FIELD)).isEqualTo(request.getString(OrigClOrdID.FIELD));
    return new ObjectMapper().createObjectNode()
        .put("clOrdId", request.getString(ClOrdID.FIELD))
        .put("origClOrdId", request.getString(OrigClOrdID.FIELD))
        .put("reportClOrdId", report.getString(ClOrdID.FIELD))
        .put("accountId", request.getString(Account.FIELD))
        .put("orderId", report.getString(OrderID.FIELD))
        .put("execType", report.getString(ExecType.FIELD))
        .put("ordStatus", report.getString(OrdStatus.FIELD))
        .put("sentAtEpochMs", sentAtEpochMs);
  }

  /** Selects only terminal cancellation reports, ignoring interim order acknowledgements. */
  static boolean isCancelled(ExecutionReport report) {
    try {
      return report.getChar(ExecType.FIELD) == '4' && report.getChar(OrdStatus.FIELD) == '4';
    } catch (FieldNotFound incomplete) {
      return false;
    }
  }

  private static Path environmentPath(String name) {
    final String value = System.getenv(name);
    assertThat(value).as(name).isNotBlank();
    return Path.of(value).toAbsolutePath().normalize();
  }

  /** Observes a terminal report for the original FIX order on the retained session. */
  @FunctionalInterface
  interface TerminalReportObserver {
    ExecutionReport await(String originalClOrdId, int timeoutSeconds) throws Exception;
  }
}
