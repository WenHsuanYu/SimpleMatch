package com.simplematch.quickfixgateway.fix;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.function.BiConsumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import quickfix.Log;
import quickfix.LogFactory;
import quickfix.SessionID;

class CredentialRedactingFixLogFactoryTest {
  @ParameterizedTest
  @MethodSource("channelsAndMalformedMessages")
  void noLogChannelRetainsCredentials(BiConsumer<Log, String> channel, String message) {
    final Log destination = mock(Log.class);
    final Log wrapper = log(destination);

    channel.accept(wrapper, message);

    channel.accept(verify(destination), "[FIX credential-bearing message redacted]");
    verifyNoMoreInteractions(destination);
  }

  @Test
  void ordinaryOrderLogsAndClearStillReachTheExistingDestination() {
    final Log destination = mock(Log.class);
    final Log wrapper = log(destination);
    wrapper.onIncoming("8=FIX.4.4\u000135=D\u000111=AUTH-ORDER\u0001");
    wrapper.clear();
    verify(destination).onIncoming("8=FIX.4.4\u000135=D\u000111=AUTH-ORDER\u0001");
    verify(destination).clear();
    verifyNoMoreInteractions(destination);
  }

  private static Stream<Arguments> channelsAndMalformedMessages() {
    final Stream<BiConsumer<Log, String>> channels =
        Stream.of(
            Log::onIncoming, Log::onOutgoing, Log::onEvent, Log::onErrorEvent, Log::onWarnEvent);
    return channels.flatMap(
        channel ->
            Stream.of(
                    "8=FIX.4.4\u000135=A\u0001553=trader\u0001554=secret|with-pipe\u0001",
                    "Skipping invalid message: 554=secret without delimiters",
                    "Rejecting invalid field: 553=trader")
                .map(message -> Arguments.of(channel, message)));
  }

  private static Log log(Log destination) {
    final SessionID session = new SessionID("FIX.4.4", "SIMPLEMATCH", "CLIENT");
    final LogFactory factory = mock(LogFactory.class);
    when(factory.create(session)).thenReturn(destination);
    return new CredentialRedactingFixLogFactory(factory).create(session);
  }
}
