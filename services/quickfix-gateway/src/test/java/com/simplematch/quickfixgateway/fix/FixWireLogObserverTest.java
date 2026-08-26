package com.simplematch.quickfixgateway.fix;

import static org.assertj.core.api.Assertions.assertThat;

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

class FixWireLogObserverTest {
  @Test
  void observesResentExecutionReportBeforeApplicationDispatch() throws Exception {
    final RecordingLogFactory delegate = new RecordingLogFactory();
    final FixWireLogObserver observer = new FixWireLogObserver(delegate);
    final Log log = observer.create(new SessionID("FIX.4.4", "CLIENT", "SIMPLEMATCH"));

    log.onIncoming(
        fixMessage(
            "8=FIX.4.4",
            "35=8",
            "34=7",
            "49=SIMPLEMATCH",
            "56=CLIENT",
            "11=C-1",
            "17=EXEC-1",
            "37=ORDER-1",
            "150=0",
            "39=0"));
    observer.discardIncoming();

    final String retransmission =
        fixMessage(
            "8=FIX.4.4",
            "35=8",
            "34=7",
            "43=Y",
            "122=20260826-12:00:00.000",
            "49=SIMPLEMATCH",
            "56=CLIENT",
            "11=C-1",
            "17=EXEC-1",
            "37=ORDER-1",
            "150=0",
            "39=0");
    log.onIncoming(retransmission);

    final FixWireLogObserver.WireMessage observed =
        observer.awaitResentExecutionReport("C-1", 7, "EXEC-1", 1);

    assertThat(observed.requiredIntegerField(MsgSeqNum.FIELD)).isEqualTo(7);
    assertThat(observed.requiredField(ExecID.FIELD)).isEqualTo("EXEC-1");
    assertThat(observed.requiredField(PossDupFlag.FIELD)).isEqualTo("Y");
    assertThat(observed.requiredField(OrigSendingTime.FIELD))
        .isEqualTo("20260826-12:00:00.000");
    assertThat(delegate.incomingMessages()).hasSize(2).endsWith(retransmission);
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
