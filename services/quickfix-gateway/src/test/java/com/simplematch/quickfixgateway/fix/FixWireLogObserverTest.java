package com.simplematch.quickfixgateway.fix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import quickfix.Log;
import quickfix.LogFactory;
import quickfix.SessionID;
import quickfix.field.ExecID;
import quickfix.field.MsgSeqNum;
import quickfix.field.OrigSendingTime;
import quickfix.field.PossDupFlag;

/** Checks protocol responses through the same wire observer used by live clients. */
class FixWireLogObserverTest {
  @Test
  void observesResentExecutionReportBeforeApplicationDispatch() throws Exception {
    final RecordingLogFactory delegate = new RecordingLogFactory();
    final FixWireLogObserver observer = new FixWireLogObserver(delegate);
    final Log log = observer.create(new SessionID("FIX.4.4", "CLIENT", "SIMPLEMATCH"));

    log.onIncoming(
        fixMessage(
            "8=FIX.4.4", "35=8", "34=7", "49=SIMPLEMATCH", "56=CLIENT",
            "11=C-1", "17=EXEC-1", "37=ORDER-1", "150=0", "39=0"));
    observer.discardIncoming();
    final String retransmission =
        fixMessage(
            "8=FIX.4.4", "35=8", "34=7", "43=Y", "122=20260826-12:00:00.000",
            "49=SIMPLEMATCH", "56=CLIENT", "11=C-1", "17=EXEC-1", "37=ORDER-1", "150=0", "39=0");
    log.onIncoming(retransmission);

    final FixWireLogObserver.WireMessage observed =
        observer.awaitResentExecutionReport("C-1", 7, "EXEC-1", 1);
    assertThat(observed.requiredIntegerField(MsgSeqNum.FIELD)).isEqualTo(7);
    assertThat(observed.requiredField(ExecID.FIELD)).isEqualTo("EXEC-1");
    assertThat(observed.requiredField(PossDupFlag.FIELD)).isEqualTo("Y");
    assertThat(observed.requiredField(OrigSendingTime.FIELD)).isEqualTo("20260826-12:00:00.000");
    assertThat(delegate.incomingMessages()).hasSize(2).endsWith(retransmission);
  }

  @Test
  void observesOnlyTheHeartbeatForTheRequestedRecoveryProbe() throws Exception {
    final FixWireLogObserver observer =
        new FixWireLogObserver(new RecordingLogFactory());
    final quickfix.Log log = observer.create(new SessionID("FIX.4.4", "CLIENT", "SIMPLEMATCH"));
    log.onIncoming("35=0\u0001112=unrelated\u0001");
    log.onIncoming("35=1\u0001112=recovery-order\u0001");
    log.onIncoming("35=0\u0001112=recovery-order\u0001");

    assertThat(observer.awaitHeartbeat("recovery-order", 1).requiredField(112))
        .isEqualTo("recovery-order");
  }

  @Test
  void doesNotTreatARejectedRetryFollowedByAHeartbeatAsRecovery() {
    final FixWireLogObserver observer =
        new FixWireLogObserver(new RecordingLogFactory());
    final quickfix.Log log = observer.create(new SessionID("FIX.4.4", "CLIENT", "SIMPLEMATCH"));
    log.onIncoming("35=3\u000145=8\u0001");
    log.onIncoming("35=0\u0001112=recovery-order\u0001");

    assertThatThrownBy(() -> observer.awaitHeartbeat("recovery-order", 1))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("rejected");
  }

  @Test
  void observesTheExactOutgoingResendRangeAndItsWireTime() throws Exception {
    final FixWireLogObserver observer = new FixWireLogObserver(new RecordingLogFactory());
    final Log log = observer.create(new SessionID("FIX.4.4", "CLIENT", "SIMPLEMATCH"));
    log.onOutgoing(fixMessage("35=2", "7=6", "16=7"));
    log.onOutgoing(fixMessage("35=2", "7=7", "16=7"));

    final var request = observer.awaitSentResendRequest(7, 1);
    assertThat(request.requiredIntegerField(7)).isEqualTo(7);
    assertThat(request.requiredIntegerField(16)).isEqualTo(7);
    assertThat(request.observedAtEpochMs()).isPositive();
  }

  private String fixMessage(String... fields) {
    return String.join("\u0001", fields) + '\u0001';
  }

  private static final class RecordingLogFactory implements LogFactory {
    private final List<String> incomingMessages = new ArrayList<>();

    @Override
    public Log create(SessionID sessionId) {
      return new Log() {
        @Override
        public void clear() {
          incomingMessages.clear();
        }

        @Override
        public void onIncoming(String message) {
          incomingMessages.add(message);
        }

        @Override
        public void onOutgoing(String message) {}

        @Override
        public void onEvent(String text) {}

        @Override
        public void onErrorEvent(String text) {}
      };
    }

    List<String> incomingMessages() {
      return List.copyOf(incomingMessages);
    }
  }
}
