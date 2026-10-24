package com.simplematch.queryservice.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplematch.queryservice.model.QueryFreshness;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class QueryReadResponseTest {
  @ParameterizedTest
  @MethodSource("recordedStates")
  void summarizesOnlyRecordedStateWithoutDisclosingInternalCheckpoints(
      List<String> states, String expected) throws Exception {
    final QueryFreshness diagnostics = new QueryFreshness(IntStream.range(0, states.size())
        .mapToObj(i -> new QueryFreshness.PartitionFreshness(
            "internal.source.topic", i, 1234, states.get(i), 9000))
        .toList());
    assertEnvelope(diagnostics, expected);
  }

  @Test
  void absentDiagnosticsAreUnknownRatherThanReady() throws Exception {
    assertEnvelope(null, "UNKNOWN");
  }

  private void assertEnvelope(QueryFreshness diagnostics, String expected) throws Exception {
    final var response = QueryReadResponse.fromProjection(Map.of("state", "RESERVED"), diagnostics);
    final ObjectMapper json = new ObjectMapper();
    final JsonNode actual = json.valueToTree(response);
    assertThat(actual).isEqualTo(json.readTree(
        "{\"data\":{\"state\":\"RESERVED\"},\"freshness\":{\"projectionState\":\""
            + expected + "\"}}"));
  }

  static Stream<Arguments> recordedStates() {
    return Stream.of(
        Arguments.of(List.of(), "UNKNOWN"),
        Arguments.of(List.of("READY"), "NO_GAP_RECORDED"),
        Arguments.of(List.of("READY", "READY"), "NO_GAP_RECORDED"),
        Arguments.of(List.of("GAP_DETECTED"), "RECOVERY_REQUIRED"),
        Arguments.of(List.of("READY", "GAP_DETECTED"), "RECOVERY_REQUIRED"),
        Arguments.of(List.of("UNSUPPORTED", "GAP_DETECTED"), "RECOVERY_REQUIRED"),
        Arguments.of(List.of("GAP_DETECTED", "UNSUPPORTED"), "RECOVERY_REQUIRED"),
        Arguments.of(List.of("UNSUPPORTED"), "UNKNOWN"),
        Arguments.of(List.of("READY", "UNSUPPORTED"), "UNKNOWN"),
        Arguments.of(List.of("READY "), "UNKNOWN"),
        Arguments.of(Collections.singletonList(null), "UNKNOWN"));
  }
}
