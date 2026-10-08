package com.simplematch.tools.riskmatchinge2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplematch.contracts.matching.runtime.v1.FinalMatchingEventEnvelope;
import com.simplematch.contracts.matching.runtime.v1.MatchingEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;

/** Observes one exact Matching Event from a known Kafka partition and starting offset. */
public final class MatchingEventObservationMain {
  private static final Duration POLL_INTERVAL = Duration.ofMillis(250);

  private MatchingEventObservationMain() {}

  /** Runs one bounded exact-event observation and writes machine-readable evidence. */
  public static void main(String[] args) throws Exception {
    final ObservationArguments arguments = ObservationArguments.parse(args);
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    Files.createDirectories(arguments.evidenceDir());

    try {
      observeCommandIfRequested(arguments, json);
      final Observation observation = observe(arguments);
      json.writerWithDefaultPrettyPrinter()
          .writeValue(
              arguments.evidenceDir().resolve("matching-event-observation.json").toFile(),
              observation);
      json.writerWithDefaultPrettyPrinter()
          .writeValue(
              arguments.evidenceDir().resolve("matching-event-observer-verdict.json").toFile(),
              Map.of(
                  "status", "PASS",
                  "commandId", arguments.commandId(),
                  "orderId", arguments.orderId(),
                  "partition", arguments.partition(),
                  "offset", observation.offset(),
                  "eventId", observation.eventId(),
                  "eventType", observation.eventType()));
    } catch (Exception failure) {
      final Map<String, Object> verdict = new LinkedHashMap<>();
      verdict.put("status", "FAIL");
      verdict.put("commandId", arguments.commandId());
      verdict.put("orderId", arguments.orderId());
      verdict.put("partition", arguments.partition());
      verdict.put(
          "reason",
          failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
      json.writerWithDefaultPrettyPrinter()
          .writeValue(
              arguments.evidenceDir().resolve("matching-event-observer-verdict.json").toFile(),
              verdict);
      throw failure;
    }
  }

  private static void observeCommandIfRequested(
      ObservationArguments arguments, ObjectMapper json) throws Exception {
    if (arguments.commandsBefore() == null) {
      return;
    }
    final var positions = json.readValue(arguments.commandsBefore().toFile(),
        KafkaObservationSession.TopicEndPositions.class);
    if (!"matching.commands".equals(positions.topic())) {
      throw new IllegalArgumentException("command boundary must describe matching.commands");
    }
    final Map<Integer, Long> offsets = positions.partitions().stream().collect(Collectors.toMap(
        KafkaObservationSession.PartitionEndOffset::partition,
        KafkaObservationSession.PartitionEndOffset::offset));
    try (var probe = new KafkaMatchingCommandProbe(
        arguments.bootstrap(), positions.topic(), "resting-buy-" + UUID.randomUUID())) {
      final var command = probe.awaitCommand(
          arguments.commandId(), arguments.partition(), offsets, arguments.timeout());
      json.writerWithDefaultPrettyPrinter().writeValue(
          arguments.evidenceDir().resolve("matching-command-observation.json").toFile(),
          commandEvidence(command));
    }
  }

  /** Selects necessary order facts and physical location without serializing raw command bytes. */
  static Map<String, Object> commandEvidence(KafkaMatchingCommandProbe.ProbeResult result) {
    final var command = result.command();
    final var header = command.getHeader();
    if ((!command.hasNewOrder() && !command.hasCancelOrder())
        || !result.key().equals(header.getCommandId())
        || result.partition() != header.getPartitionId()) {
      throw new IllegalStateException("observed command is not a correlated order command");
    }
    if (command.hasCancelOrder()) {
      final var cancel = command.getCancelOrder();
      return Map.ofEntries(
          Map.entry("topic", "matching.commands"), Map.entry("partition", result.partition()),
          Map.entry("offset", result.offset()),
          Map.entry("physicalDeliveryCount", result.physicalDeliveryCount()),
          Map.entry("payloadSha256", result.payloadSha256()),
          Map.entry("commandType", "CANCEL_ORDER"),
          Map.entry("commandId", header.getCommandId()), Map.entry("orderId", cancel.getOrderId()),
          Map.entry("accountId", cancel.getAccountId()),
          Map.entry("venueMic", cancel.getInstrument().getVenueMic()),
          Map.entry("symbol", cancel.getInstrument().getSymbol()),
          Map.entry("side", cancel.getSide().name()),
          Map.entry("tradingDay", header.getArtifactIdentity().getTradingDay()),
          Map.entry("tradingSessionId", header.getTradingSessionId()),
          Map.entry("artifactContentSha256", header.getArtifactIdentity().getContentSha256()),
          Map.entry("routingAlgorithmVersion", header.getRoutingAlgorithmVersion()));
    }
    final var order = command.getNewOrder();
    return Map.ofEntries(
        Map.entry("topic", "matching.commands"), Map.entry("partition", result.partition()),
        Map.entry("offset", result.offset()),
        Map.entry("physicalDeliveryCount", result.physicalDeliveryCount()),
        Map.entry("payloadSha256", result.payloadSha256()),
        Map.entry("commandId", header.getCommandId()), Map.entry("orderId", order.getOrderId()),
        Map.entry("accountId", order.getAccountId()),
        Map.entry("venueMic", order.getInstrument().getVenueMic()),
        Map.entry("symbol", order.getInstrument().getSymbol()),
        Map.entry("side", order.getSide().name()),
        Map.entry("quantityShares", order.getQuantityShares()),
        Map.entry("priceUnits", order.getLimitPriceUnits()),
        Map.entry("orderType", order.getOrderType().name()),
        Map.entry("timeInForce", order.getTimeInForce().name()),
        Map.entry("tradingDay", header.getArtifactIdentity().getTradingDay()),
        Map.entry("tradingSessionId", header.getTradingSessionId()),
        Map.entry("artifactContentSha256", header.getArtifactIdentity().getContentSha256()),
        Map.entry("routingAlgorithmVersion", header.getRoutingAlgorithmVersion()));
  }

  private static Observation observe(ObservationArguments arguments) {
    final TopicPartition topicPartition =
        new TopicPartition(arguments.topic(), arguments.partition());
    final long deadlineNanos = System.nanoTime() + arguments.timeout().toNanos();

    try (KafkaConsumer<byte[], byte[]> consumer =
        new KafkaConsumer<>(consumerProperties(arguments))) {
      consumer.assign(List.of(topicPartition));
      consumer.seek(topicPartition, arguments.startOffset());

      while (System.nanoTime() < deadlineNanos) {
        final ConsumerRecords<byte[], byte[]> records = consumer.poll(POLL_INTERVAL);
        for (ConsumerRecord<byte[], byte[]> record : records.records(topicPartition)) {
          final Observation observation = matchingObservation(record, arguments);
          if (observation != null) {
            return observation;
          }
        }
      }
    }

    throw new IllegalStateException(
        "matching.events did not contain the expected command/order "
            + "before the observation deadline");
  }

  /**
   * Correlates one consumed record with the requested command and order.
   *
   * <p>The returned evidence preserves the configured Kafka seek lower bound as {@code
   * startOffset}; a non-matching record returns {@code null} so callers can continue observing.
   *
   * @param record consumed Kafka record to inspect
   * @param arguments requested observation boundary and correlation identifiers
   * @return correlated evidence, or {@code null} when the record is outside the requested range or
   *     does not match
   * @throws IllegalStateException when a record contains an invalid Matching Event payload
   */
  static Observation matchingObservation(
      ConsumerRecord<byte[], byte[]> record, ObservationArguments arguments) {
    if (record.offset() < arguments.startOffset()) {
      return null;
    }
    final FinalMatchingEventEnvelope envelope =
        parse(record, arguments.topic(), arguments.partition());
    final MatchingEvent event = envelope.event();
    final boolean matchesCommand = event.getSourceCommandId().equals(arguments.commandId());
    final boolean matchesExpectedOrder = matchesOrder(event, arguments.orderId());
    if (!matchesCommand || !matchesExpectedOrder) {
      return null;
    }
    return new Observation(
        record.topic(),
        record.partition(),
        arguments.startOffset(),
        record.offset(),
        envelope.eventIdHex(),
        envelope.payloadSha256Hex(),
        event.getEventType().name(),
        event.getSourceCommandId(),
        arguments.orderId(),
        new EventContext(event.getSourceInputOffset(), event.getArtifactIdentity().getTradingDay(),
            event.getTradingSessionId(), event.getArtifactIdentity().getContentSha256(),
            event.getRoutingAlgorithmVersion()),
        restedOrderEvidence(event), terminalOrderEvidence(event));
  }

  private static TerminalOrderEvidence terminalOrderEvidence(MatchingEvent event) {
    if (!event.hasOrderCancelled() && !event.hasOrderExpired()) {
      return null;
    }
    final var order = event.hasOrderCancelled()
        ? event.getOrderCancelled() : event.getOrderExpired();
    return new TerminalOrderEvidence(order.getOrderId(), order.getAccountId(),
        order.getInstrument().getVenueMic(), order.getInstrument().getSymbol(),
        order.getSide().name(), order.getLeavesQuantityShares(), order.getReason().name());
  }

  private static RestedOrderEvidence restedOrderEvidence(MatchingEvent event) {
    if (!event.hasOrderRested()) {
      return null;
    }
    final var order = event.getOrderRested();
    return new RestedOrderEvidence(order.getOrderId(), order.getAccountId(),
        order.getInstrument().getVenueMic(),
        order.getInstrument().getSymbol(), order.getSide().name(),
        order.getLeavesQuantityShares(), order.getRestingPriceUnits());
  }

  private static FinalMatchingEventEnvelope parse(
      ConsumerRecord<byte[], byte[]> record, String topic, int partition) {
    try {
      return FinalMatchingEventEnvelope.parse(record.value());
    } catch (Exception invalid) {
      throw new IllegalStateException(
          "matching.events contains an invalid final event at "
              + topic
              + "-"
              + partition
              + "@"
              + record.offset(),
          invalid);
    }
  }

  static boolean matchesOrder(MatchingEvent event, String orderId) {
    return switch (event.getEventType()) {
      case MATCHING_EVENT_TYPE_ORDER_RESTED ->
          event.getOrderRested().getOrderId().equals(orderId);
      case MATCHING_EVENT_TYPE_TRADE_EXECUTED ->
          event.getTradeExecuted().getMaker().getOrderId().equals(orderId)
              || event.getTradeExecuted().getTaker().getOrderId().equals(orderId);
      case MATCHING_EVENT_TYPE_ORDER_CANCELLED ->
          event.getOrderCancelled().getOrderId().equals(orderId);
      case MATCHING_EVENT_TYPE_ORDER_EXPIRED ->
          event.getOrderExpired().getOrderId().equals(orderId);
      case MATCHING_EVENT_TYPE_UNSPECIFIED, UNRECOGNIZED -> false;
    };
  }

  static Properties consumerProperties(ObservationArguments arguments) {
    final Properties properties = new Properties();
    properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, arguments.bootstrap());
    properties.put(ConsumerConfig.GROUP_ID_CONFIG, "matching-event-observer-" + UUID.randomUUID());
    properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
    properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "none");
    properties.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, "false");
    properties.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
    properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
    properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
    return properties;
  }

  /** Evidence written for one correlated Matching Event record. */
  record Observation(
      String topic,
      int partition,
      long startOffset,
      long offset,
      String eventId,
      String payloadSha256,
      String eventType,
      String sourceCommandId,
      String orderId,
      EventContext context,
      RestedOrderEvidence restedOrder,
      TerminalOrderEvidence terminalOrder) {}

  /** Immutable input position and deployed session/artifact identities of the event. */
  record EventContext(long sourceInputOffset, String tradingDay, String tradingSessionId,
      String artifactContentSha256, String routingAlgorithmVersion) {}

  /** Only the business fields necessary to establish that an admitted order really rested. */
  record RestedOrderEvidence(String orderId, String accountId, String venueMic, String symbol,
      String side,
      long leavesQuantityShares, long restingPriceUnits) {}

  /** Unfilled quantity cancelled or expired, not a claim that those shares were filled. */
  record TerminalOrderEvidence(String orderId, String accountId, String venueMic, String symbol,
      String side, long leavesQuantityShares, String reason) {}

  /** Parsed observer inputs used to establish the event correlation boundary. */
  record ObservationArguments(
      String bootstrap,
      String topic,
      int partition,
      long startOffset,
      String commandId,
      String orderId,
      Duration timeout,
      Path evidenceDir,
      Path commandsBefore) {
    static ObservationArguments parse(String[] args) {
      final Map<String, String> values = argumentValues(args);
      final int partition = rangedInt(values, "--partition", 0, 14);
      final long startOffset = nonNegativeLong(values, "--start-offset");
      final int timeoutSeconds = rangedInt(values, "--timeout-seconds", 1, 300);
      final String commandId = uuid(values, "--command-id");
      final String orderId = uuid(values, "--order-id");
      return new ObservationArguments(
          required(values, "--bootstrap"),
          required(values, "--topic"),
          partition,
          startOffset,
          commandId,
          orderId,
          Duration.ofSeconds(timeoutSeconds),
          Path.of(required(values, "--evidence-dir")).toAbsolutePath().normalize(),
          values.containsKey("--commands-before")
              ? Path.of(required(values, "--commands-before")) : null);
    }

    private static Map<String, String> argumentValues(String[] args) {
      if (args.length % 2 != 0) {
        throw new IllegalArgumentException(
            "matching event observer arguments must be name/value pairs");
      }
      final Map<String, String> values = new LinkedHashMap<>();
      for (int index = 0; index < args.length; index += 2) {
        final String name = args[index];
        if (!name.startsWith("--")) {
          throw new IllegalArgumentException(
              "unexpected matching event observer argument: " + name);
        }
        if (values.put(name, args[index + 1]) != null) {
          throw new IllegalArgumentException("duplicate matching event observer argument: " + name);
        }
      }
      return values;
    }

    private static String required(Map<String, String> values, String name) {
      final String value = values.get(name);
      if (value == null || value.isBlank()) {
        throw new IllegalArgumentException(name + " is required");
      }
      return value;
    }

    private static String uuid(Map<String, String> values, String name) {
      final String value = required(values, name);
      UUID.fromString(value);
      return value;
    }

    private static int rangedInt(
        Map<String, String> values, String name, int minimum, int maximum) {
      final int value = parseInt(required(values, name), name);
      if (value < minimum || value > maximum) {
        throw new IllegalArgumentException(
            name + " must be between " + minimum + " and " + maximum);
      }
      return value;
    }

    private static long nonNegativeLong(Map<String, String> values, String name) {
      final long value = parseLong(required(values, name), name);
      if (value < 0) {
        throw new IllegalArgumentException(name + " must be non-negative");
      }
      return value;
    }

    private static int parseInt(String value, String name) {
      try {
        return Integer.parseInt(value);
      } catch (NumberFormatException invalid) {
        throw new IllegalArgumentException(name + " must be an integer", invalid);
      }
    }

    private static long parseLong(String value, String name) {
      try {
        return Long.parseLong(value);
      } catch (NumberFormatException invalid) {
        throw new IllegalArgumentException(name + " must be an integer", invalid);
      }
    }
  }
}
