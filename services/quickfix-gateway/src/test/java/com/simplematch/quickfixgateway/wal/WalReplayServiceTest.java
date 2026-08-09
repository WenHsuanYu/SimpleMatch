package com.simplematch.quickfixgateway.wal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.simplematch.contracts.common.v1.OrderType;
import com.simplematch.contracts.common.v1.Side;
import com.simplematch.contracts.common.v1.TimeInForce;
import com.simplematch.contracts.orders.v1.OrderCommand;
import com.simplematch.quickfixgateway.fix.OrderSessionRegistry;
import com.simplematch.quickfixgateway.risk.RiskReconciliationClient;
import com.simplematch.quickfixgateway.risk.RiskReconciliationResult;
import com.simplematch.quickfixgateway.risk.RiskSubmissionClient;
import com.simplematch.quickfixgateway.risk.RiskSubmissionResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class WalReplayServiceTest {
  private static final String COMMAND_ID = "0194a8f0-7c77-7b38-9e2d-2a5fdd0f7c11";
  private static final String ORDER_ID = "O-C1";
  private static final String ACCOUNT_ID = "0194a8f0-7c77-7b38-9e2d-2a5fdd0f7c13";

  @TempDir Path tempDir;

  @Test
  void terminalAcceptedStateSkipsRemoteWorkAndRestoresSession() throws Exception {
    try (WalAppender wal = wal("accepted")) {
      final WalRecoveryJournal states = states(wal);
      final WalRecord record = newOrder();
      wal.appendAndFlush(record);
      states.appendAndFlush(COMMAND_ID, WalRecoveryState.ACCEPTED);
      final RiskSubmissionClient risk = mock(RiskSubmissionClient.class);
      final RiskReconciliationClient reconciliation = mock(RiskReconciliationClient.class);
      final OrderSessionRegistry registry = new OrderSessionRegistry();

      assertThat(replay(wal, states, risk, reconciliation, registry).replayAll()).isEqualTo(1);

      verifyNoInteractions(risk, reconciliation);
      assertThat(registry.find(ORDER_ID)).isPresent();
    }
  }

  @Test
  void pendingRiskStateIsRecordedWithoutResubmission() throws Exception {
    try (WalAppender wal = wal("pending")) {
      final WalRecoveryJournal states = states(wal);
      wal.appendAndFlush(newOrder());
      states.appendAndFlush(COMMAND_ID, WalRecoveryState.UNKNOWN);
      final RiskSubmissionClient risk = mock(RiskSubmissionClient.class);
      final RiskReconciliationClient reconciliation = mock(RiskReconciliationClient.class);
      when(reconciliation.lookup(COMMAND_ID))
          .thenReturn(reconciliation(RiskReconciliationResult.Outcome.PENDING));
      final OrderSessionRegistry registry = new OrderSessionRegistry();

      assertThat(replay(wal, states, risk, reconciliation, registry).replayAll()).isEqualTo(1);

      verifyNoInteractions(risk);
      assertThat(states.readLatest()).containsEntry(COMMAND_ID, WalRecoveryState.PENDING);
      assertThat(registry.find(ORDER_ID)).isPresent();
    }
  }

  @Test
  void acceptedRiskStateIsRecordedWithoutResubmission() throws Exception {
    try (WalAppender wal = wal("reconciled-accepted")) {
      final WalRecoveryJournal states = states(wal);
      wal.appendAndFlush(newOrder());
      states.appendAndFlush(COMMAND_ID, WalRecoveryState.UNKNOWN);
      final RiskSubmissionClient risk = mock(RiskSubmissionClient.class);
      final RiskReconciliationClient reconciliation = mock(RiskReconciliationClient.class);
      when(reconciliation.lookup(COMMAND_ID))
          .thenReturn(reconciliation(RiskReconciliationResult.Outcome.ACCEPTED));
      final OrderSessionRegistry registry = new OrderSessionRegistry();

      assertThat(replay(wal, states, risk, reconciliation, registry).replayAll()).isEqualTo(1);

      verifyNoInteractions(risk);
      assertThat(states.readLatest()).containsEntry(COMMAND_ID, WalRecoveryState.ACCEPTED);
      assertThat(registry.find(ORDER_ID)).isPresent();
    }
  }

  @Test
  void unmarkedCommandIsMarkedUnknownBeforeFirstSubmission() throws Exception {
    try (WalAppender wal = wal("unmarked")) {
      final WalRecoveryJournal states = states(wal);
      wal.appendAndFlush(newOrder());
      final RiskSubmissionClient risk = mock(RiskSubmissionClient.class);
      final RiskReconciliationClient reconciliation = mock(RiskReconciliationClient.class);
      when(risk.submitNewOrder(any(OrderCommand.class)))
          .thenAnswer(
              invocation -> {
                assertThat(states.readLatest())
                    .containsEntry(COMMAND_ID, WalRecoveryState.UNKNOWN);
                return new RiskSubmissionResult(
                    ORDER_ID, RiskSubmissionResult.Outcome.ACCEPTED, "", "");
              });
      final OrderSessionRegistry registry = new OrderSessionRegistry();

      assertThat(replay(wal, states, risk, reconciliation, registry).replayAll()).isEqualTo(1);

      final ArgumentCaptor<OrderCommand> command = ArgumentCaptor.forClass(OrderCommand.class);
      verify(risk).submitNewOrder(command.capture());
      verifyNoInteractions(reconciliation);
      assertThat(command.getValue().getCommandId()).isEqualTo(COMMAND_ID);
      assertThat(states.readLatest()).containsEntry(COMMAND_ID, WalRecoveryState.ACCEPTED);
      assertThat(registry.find(ORDER_ID)).isPresent();
    }
  }

  @Test
  void notFoundManagedCommandIsResubmittedWithSameCommandIdentity() throws Exception {
    try (WalAppender wal = wal("not-found")) {
      final WalRecoveryJournal states = states(wal);
      wal.appendAndFlush(newOrder());
      states.appendAndFlush(COMMAND_ID, WalRecoveryState.UNKNOWN);
      final RiskSubmissionClient risk = mock(RiskSubmissionClient.class);
      final RiskReconciliationClient reconciliation = mock(RiskReconciliationClient.class);
      when(reconciliation.lookup(COMMAND_ID))
          .thenReturn(reconciliation(RiskReconciliationResult.Outcome.NOT_FOUND));
      when(risk.submitNewOrder(any(OrderCommand.class)))
          .thenReturn(
              new RiskSubmissionResult(
                  ORDER_ID, RiskSubmissionResult.Outcome.ACCEPTED, "", ""));
      final OrderSessionRegistry registry = new OrderSessionRegistry();

      assertThat(replay(wal, states, risk, reconciliation, registry).replayAll()).isEqualTo(1);

      final ArgumentCaptor<OrderCommand> command = ArgumentCaptor.forClass(OrderCommand.class);
      verify(risk).submitNewOrder(command.capture());
      assertThat(command.getValue().getCommandId()).isEqualTo(COMMAND_ID);
      assertThat(command.getValue().getOrderId()).isEqualTo(ORDER_ID);
      assertThat(states.readLatest()).containsEntry(COMMAND_ID, WalRecoveryState.ACCEPTED);
    }
  }

  @Test
  void locallyPendingCommandMissingFromRiskFailsClosed() throws Exception {
    try (WalAppender wal = wal("pending-missing")) {
      final WalRecoveryJournal states = states(wal);
      wal.appendAndFlush(newOrder());
      states.appendAndFlush(COMMAND_ID, WalRecoveryState.PENDING);
      final RiskSubmissionClient risk = mock(RiskSubmissionClient.class);
      final RiskReconciliationClient reconciliation = mock(RiskReconciliationClient.class);
      when(reconciliation.lookup(COMMAND_ID))
          .thenReturn(reconciliation(RiskReconciliationResult.Outcome.NOT_FOUND));

      assertThatThrownBy(
              () ->
                  replay(
                          wal,
                          states,
                          risk,
                          reconciliation,
                          new OrderSessionRegistry())
                      .replayAll())
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("lost a locally pending admission");

      verifyNoInteractions(risk);
    }
  }

  @Test
  void notFoundManagedCancelRetainsOrderContextDuringResubmission() throws Exception {
    try (WalAppender wal = wal("cancel")) {
      final WalRecoveryJournal states = states(wal);
      final String cancelCommandId = UUID.randomUUID().toString();
      wal.appendAndFlush(cancel(cancelCommandId));
      states.appendAndFlush(cancelCommandId, WalRecoveryState.UNKNOWN);
      final RiskSubmissionClient risk = mock(RiskSubmissionClient.class);
      final RiskReconciliationClient reconciliation = mock(RiskReconciliationClient.class);
      when(reconciliation.lookup(cancelCommandId))
          .thenReturn(
              new RiskReconciliationResult(
                  cancelCommandId,
                  RiskReconciliationResult.Outcome.NOT_FOUND,
                  "",
                  "",
                  ""));
      when(risk.submitCancel(any(OrderCommand.class)))
          .thenReturn(
              new RiskSubmissionResult(
                  ORDER_ID, RiskSubmissionResult.Outcome.ACCEPTED, "", ""));

      replay(wal, states, risk, reconciliation, new OrderSessionRegistry()).replayAll();

      final ArgumentCaptor<OrderCommand> command = ArgumentCaptor.forClass(OrderCommand.class);
      verify(risk).submitCancel(command.capture());
      assertThat(command.getValue().getOrderId()).isEqualTo(ORDER_ID);
      assertThat(command.getValue().getSymbol()).isEqualTo("2330");
      assertThat(command.getValue().getSide()).isEqualTo(Side.SIDE_BUY);
    }
  }

  private WalAppender wal(String name) {
    return new WalAppender(tempDir.resolve(name + ".wal"), StandardCharsets.UTF_8);
  }

  private WalRecoveryJournal states(WalAppender wal) {
    return new WalRecoveryJournal(WalRecoveryJournal.pathFor(wal.walPath()));
  }

  private WalReplayService replay(
      WalAppender wal,
      WalRecoveryJournal states,
      RiskSubmissionClient risk,
      RiskReconciliationClient reconciliation,
      OrderSessionRegistry registry) {
    return new WalReplayService(wal, states, risk, reconciliation, registry);
  }

  private RiskReconciliationResult reconciliation(RiskReconciliationResult.Outcome outcome) {
    return new RiskReconciliationResult(COMMAND_ID, outcome, ORDER_ID, "", "");
  }

  private WalRecord newOrder() {
    return new WalRecord(
        new WalMetadata("v1", COMMAND_ID, 1L, "quickfix-gateway"),
        new FixSessionIdentity("CLIENT", "SIMPLEMATCH"),
        new WalOrderReference(ORDER_ID, "C1", "", ACCOUNT_ID),
        new WalCommand.NewOrder(
            new WalOrderTerms(
                "2330",
                Side.SIDE_BUY,
                "10",
                "100",
                OrderType.ORDER_TYPE_LIMIT,
                TimeInForce.TIME_IN_FORCE_ROD)),
        new RawFixMessage("raw"));
  }

  private WalRecord cancel(String commandId) {
    return new WalRecord(
        new WalMetadata("v1", commandId, 1L, "quickfix-gateway"),
        new FixSessionIdentity("CLIENT", "SIMPLEMATCH"),
        new WalOrderReference(ORDER_ID, "CXL-1", "C1", ACCOUNT_ID),
        new WalCommand.Cancel("2330", Side.SIDE_BUY),
        new RawFixMessage("8=FIX.4.4|35=F"));
  }
}
