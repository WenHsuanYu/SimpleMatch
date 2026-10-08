package com.simplematch.tools.riskmatchinge2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplematch.contracts.matching.runtime.v1.CancellationReason;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.CloseOptions;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;

/** Controlled redelivery of one observed cancellation; this is not natural Matching replay. */
public final class MatchingEventRedeliveryMain {
  private MatchingEventRedeliveryMain() {}

  /** Reads, republishes and independently observes exact bytes under one deadline. */
  public static void main(String[] args) throws Exception {
    final var arguments = MatchingEventObservationMain.ObservationArguments.parse(args);
    final var consumer = new KafkaConsumer<byte[], byte[]>(
        MatchingEventObservationMain.consumerProperties(arguments));
    final var producer = new KafkaProducer<byte[], byte[]>(producerProperties(arguments));
    try {
      final var evidence = redeliver(arguments, consumer, producer);
      Files.createDirectories(arguments.evidenceDir());
      new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(
          arguments.evidenceDir().resolve("redelivery.json").toFile(), evidence);
    } finally {
      producer.close(Duration.ofSeconds(5));
      consumer.close(CloseOptions.timeout(Duration.ofSeconds(5)));
    }
  }

  /** Kafka interfaces are the external test seam; acknowledgements alone are not evidence. */
  static Map<String, Object> redeliver(MatchingEventObservationMain.ObservationArguments arguments,
      Consumer<byte[], byte[]> consumer, Producer<byte[], byte[]> producer) throws Exception {
    if (!"matching.events".equals(arguments.topic())) {
      throw new IllegalArgumentException("controlled redelivery is restricted to matching.events");
    }
    final long deadline = System.nanoTime() + arguments.timeout().toNanos();
    final TopicPartition partition = new TopicPartition(arguments.topic(), arguments.partition());
    consumer.assign(List.of(partition));
    final var original = readExact(consumer, partition, arguments.startOffset(), deadline);
    final var observation = requireCancellation(original, arguments);
    final var repeatedRecord = new ProducerRecord<>(arguments.topic(), arguments.partition(),
        original.key(), original.value());
    final var published = producer.send(repeatedRecord)
        .get(remainingNanos(deadline), TimeUnit.NANOSECONDS);
    requireNewPhysicalRecord(original, published);
    final var repeated = readExact(consumer, partition, published.offset(), deadline);
    if (!Arrays.equals(original.key(), repeated.key())
        || !Arrays.equals(original.value(), repeated.value())) {
      throw new IllegalStateException("independently read redelivery contains conflicting bytes");
    }
    return redeliveryEvidence(original, repeated, observation);
  }

  private static MatchingEventObservationMain.Observation requireCancellation(
      ConsumerRecord<byte[], byte[]> original,
      MatchingEventObservationMain.ObservationArguments arguments) {
    final var observation = MatchingEventObservationMain.matchingObservation(original, arguments);
    if (observation == null
        || !"MATCHING_EVENT_TYPE_ORDER_CANCELLED".equals(observation.eventType())
        || observation.terminalOrder() == null
        || !CancellationReason.CANCELLATION_REASON_USER_REQUEST.name().equals(
            observation.terminalOrder().reason()) || original.key() == null) {
      throw new IllegalStateException("original record is not the correlated user cancellation");
    }
    return observation;
  }

  private static void requireNewPhysicalRecord(
      ConsumerRecord<byte[], byte[]> original, RecordMetadata published) {
    if (!published.topic().equals(original.topic())
        || published.partition() != original.partition()
        || published.offset() <= original.offset()) {
      throw new IllegalStateException("redelivery did not create a new same-partition record");
    }
  }

  private static Map<String, Object> redeliveryEvidence(ConsumerRecord<byte[], byte[]> original,
      ConsumerRecord<byte[], byte[]> repeated, MatchingEventObservationMain.Observation observation)
      throws Exception {
    final Map<String, Object> evidence = new LinkedHashMap<>();
    evidence.put("topic", repeated.topic());
    evidence.put("partition", repeated.partition());
    evidence.put("originalOffset", original.offset());
    evidence.put("publishedOffset", repeated.offset());
    evidence.put("observedOffset", repeated.offset());
    evidence.put("eventId", observation.eventId());
    evidence.put("payloadSha256", observation.payloadSha256());
    evidence.put("keySha256", HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(repeated.key())));
    evidence.put("keyBytesEqual", true);
    evidence.put("valueBytesEqual", true);
    return Map.copyOf(evidence);
  }

  private static ConsumerRecord<byte[], byte[]> readExact(Consumer<byte[], byte[]> consumer,
      TopicPartition partition, long offset, long deadline) {
    consumer.seek(partition, offset);
    while (System.nanoTime() < deadline) {
      final Duration wait = Duration.ofNanos(Math.min(remainingNanos(deadline), 250_000_000));
      for (var record : consumer.poll(wait).records(partition)) {
        if (record.offset() == offset) {
          return record;
        }
        if (record.offset() > offset) {
          throw new IllegalStateException("requested physical event offset was not observed");
        }
      }
    }
    throw new IllegalStateException("exact physical event observation exceeded its deadline");
  }

  private static long remainingNanos(long deadline) {
    final long remaining = deadline - System.nanoTime();
    if (remaining <= 0) {
      throw new IllegalStateException("controlled redelivery exceeded its single deadline");
    }
    return remaining;
  }

  private static Properties producerProperties(
      MatchingEventObservationMain.ObservationArguments arguments) {
    final Properties properties = new Properties();
    properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, arguments.bootstrap());
    properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
    properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
    properties.put(ProducerConfig.ACKS_CONFIG, "all");
    properties.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
    properties.put(ProducerConfig.MAX_BLOCK_MS_CONFIG,
        Math.min(arguments.timeout().toMillis(), 10_000));
    properties.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 10_000);
    properties.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 30_000);
    return properties;
  }
}
