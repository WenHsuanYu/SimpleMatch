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
        arguments.orderId());
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

  private static Properties consumerProperties(ObservationArguments arguments) {
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
      String orderId) {}

  /** Parsed observer inputs used to establish the event correlation boundary. */
  record ObservationArguments(
      String bootstrap,
      String topic,
      int partition,
      long startOffset,
      String commandId,
      String orderId,
      Duration timeout,
      Path evidenceDir) {
    private static ObservationArguments parse(String[] args) {
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
          Path.of(required(values, "--evidence-dir")).toAbsolutePath().normalize());
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
