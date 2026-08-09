package com.simplematch.quickfixgateway.fix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.simplematch.contracts.common.v1.OrderType;
import com.simplematch.contracts.common.v1.Side;
import com.simplematch.contracts.common.v1.TimeInForce;
import com.simplematch.quickfixgateway.risk.RiskSubmissionClient;
import com.simplematch.quickfixgateway.risk.RiskSubmissionFailure;
import com.simplematch.quickfixgateway.risk.RiskSubmissionResult;
import com.simplematch.quickfixgateway.wal.FixSessionIdentity;
import com.simplematch.quickfixgateway.wal.RawFixMessage;
import com.simplematch.quickfixgateway.wal.WalCommand;
import com.simplematch.quickfixgateway.wal.WalMetadata;
import com.simplematch.quickfixgateway.wal.WalOrderReference;
import com.simplematch.quickfixgateway.wal.WalOrderTerms;
import com.simplematch.quickfixgateway.wal.WalRecord;
import com.simplematch.quickfixgateway.wal.WalRecoveryJournal;
import com.simplematch.quickfixgateway.wal.WalRecoveryState;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import quickfix.Message;
import quickfix.SessionID;
import quickfix.field.ExecType;
import quickfix.field.OrdStatus;
import quickfix.field.Text;

class RiskSubmissionResponderTest {
  private static final Instant NOW = Instant.parse("2024-03-27T08:09:10.123Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final SessionID SESSION_ID =
      new SessionID("FIX.4.4", "SIMPLEMATCH", "CLIENT1");
  private static final String UNKNOWN_OUTCOME_CLIENT_TEXT =
      "SYSTEM_ERROR: order outcome is pending confirmation; no client action is required";

  @TempDir Path tempDir;

  @DisplayName("new-order transport failure remains an unknown Risk outcome")
  @Test
  void newOrderTransportFailureRemainsUnknown() throws Exception {
    final RiskSubmissionClient risk = mock(RiskSubmissionClient.class);
    final FixSessionMessageSender sender = mock(FixSessionMessageSender.class);
    final WalRecord walRecord = newOrderRecord();
    when(risk.submitNewOrder(any())).thenThrow(RiskSubmissionFailure.circuitOpen());
    final WalRecoveryJournal recoveryJournal = recoveryJournal();
    final RiskSubmissionResponder responder = responder(risk, sender, recoveryJournal);

    final RiskSubmissionResult result =
        responder.submitNewOrder(walRecord.toOrderCommand(), SESSION_ID, walRecord, NOW);

    assertThat(result.outcome()).isEqualTo(RiskSubmissionResult.Outcome.UNKNOWN);
    assertThat(result.accepted()).isFalse();
    assertThat(result.rejected()).isFalse();
    assertThat(result.unknown()).isTrue();
    assertThat(recoveryJournal.readLatest())
        .containsEntry(walRecord.recordId(), WalRecoveryState.UNKNOWN);

    final ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
    verify(sender).send(eq(SESSION_ID), messageCaptor.capture());
    final Message message = messageCaptor.getValue();
    assertThat(message.getChar(ExecType.FIELD)).isEqualTo('A');
    assertThat(message.getChar(OrdStatus.FIELD)).isEqualTo('A');
    assertThat(message.getString(Text.FIELD)).isEqualTo(UNKNOWN_OUTCOME_CLIENT_TEXT);
    assertThat(message.getString(Text.FIELD))
        .doesNotContain("RISK_CIRCUIT_OPEN", "circuit breaker", "risk-service");
    assertThat(result.reasonCode()).isEqualTo("RISK_CIRCUIT_OPEN");
    assertThat(result.reasonText()).isEqualTo("risk-service circuit breaker is open");
  }

  @DisplayName("explicit Risk rejection remains a terminal business rejection")
  @Test
  void explicitRiskRejectionRemainsTerminal() throws Exception {
    final RiskSubmissionClient risk = mock(RiskSubmissionClient.class);
    final FixSessionMessageSender sender = mock(FixSessionMessageSender.class);
    final WalRecord walRecord = newOrderRecord();
    when(risk.submitNewOrder(any()))
        .thenReturn(
            new RiskSubmissionResult(
                walRecord.orderId(), false, "RISK_LIMIT", "available limit is insufficient"));
    final WalRecoveryJournal recoveryJournal = recoveryJournal();
    final RiskSubmissionResponder responder = responder(risk, sender, recoveryJournal);

    final RiskSubmissionResult result =
        responder.submitNewOrder(walRecord.toOrderCommand(), SESSION_ID, walRecord, NOW);

    assertThat(result.outcome()).isEqualTo(RiskSubmissionResult.Outcome.REJECTED);
    assertThat(result.rejected()).isTrue();
    assertThat(result.unknown()).isFalse();
    assertThat(recoveryJournal.readLatest())
        .containsEntry(walRecord.recordId(), WalRecoveryState.REJECTED);

    final ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
    verify(sender).send(eq(SESSION_ID), messageCaptor.capture());
    final Message message = messageCaptor.getValue();
    assertThat(message.getChar(ExecType.FIELD)).isEqualTo('8');
    assertThat(message.getChar(OrdStatus.FIELD)).isEqualTo('8');
    assertThat(message.getString(Text.FIELD))
        .isEqualTo("RISK_LIMIT: available limit is insufficient");
  }

  @DisplayName("cancel transport failure does not fabricate a terminal cancel rejection")
  @Test
  void cancelTransportFailureDoesNotFabricateRejection() {
    final RiskSubmissionClient risk = mock(RiskSubmissionClient.class);
    final FixSessionMessageSender sender = mock(FixSessionMessageSender.class);
    final WalRecord walRecord = cancelRecord();
    when(risk.submitCancel(any())).thenThrow(RiskSubmissionFailure.circuitOpen());
    final WalRecoveryJournal recoveryJournal = recoveryJournal();
    final RiskSubmissionResponder responder = responder(risk, sender, recoveryJournal);

    final RiskSubmissionResult result =
        responder.submitCancelOrder(walRecord.toOrderCommand(), SESSION_ID, walRecord, 'A');

    assertThat(result.outcome()).isEqualTo(RiskSubmissionResult.Outcome.UNKNOWN);
    assertThat(result.rejected()).isFalse();
    assertThat(result.unknown()).isTrue();
    assertThat(result.reasonCode()).isEqualTo("RISK_CIRCUIT_OPEN");
    assertThat(recoveryJournal.readLatest())
        .containsEntry(walRecord.recordId(), WalRecoveryState.UNKNOWN);
    verifyNoInteractions(sender);
  }

  private RiskSubmissionResponder responder(
      RiskSubmissionClient risk,
      FixSessionMessageSender sender,
      WalRecoveryJournal recoveryJournal) {
    return new RiskSubmissionResponder(risk, sender, new FixMessageMapper(CLOCK), recoveryJournal);
  }

  private WalRecoveryJournal recoveryJournal() {
    return new WalRecoveryJournal(tempDir.resolve("recovery-" + System.nanoTime()));
  }

  private WalRecord newOrderRecord() {
    return new WalRecord(
        new WalMetadata("v1", "record-new-1", NOW.toEpochMilli(), "quickfix-gateway"),
        new FixSessionIdentity("CLIENT1", "SIMPLEMATCH"),
        new WalOrderReference("O-C1", "C1", "", "ACC-1"),
        new WalCommand.NewOrder(
            new WalOrderTerms(
                "AAPL",
                Side.SIDE_BUY,
                "10",
                "101.25",
                OrderType.ORDER_TYPE_LIMIT,
                TimeInForce.TIME_IN_FORCE_ROD)),
        new RawFixMessage("35=D"));
  }

  private WalRecord cancelRecord() {
    return new WalRecord(
        new WalMetadata("v1", "record-cancel-1", NOW.toEpochMilli(), "quickfix-gateway"),
        new FixSessionIdentity("CLIENT1", "SIMPLEMATCH"),
        new WalOrderReference("O-C1", "CXL-1", "C1", "ACC-1"),
        new WalCommand.Cancel(),
        new RawFixMessage("35=F"));
  }
}
