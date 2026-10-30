package com.simplematch.tools.dependencies;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.classfile.ClassFile;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.Utf8Entry;
import java.lang.constant.ClassDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Rejects producer dependencies in compiled owner code rather than Java source formatting. */
class CompiledOutboxPublisherGuardTest {
  private static final Pattern SPRING_PRODUCER =
      Pattern.compile("org/springframework/kafka/core/[^;/]*(?:KafkaTemplate|ProducerFactory)");

  @Test
  void compiledOwnersPublishThroughTheirTransactionalOutbox() throws Exception {
    final var owners = new ObjectMapper().readTree(System.getProperty("dependencyOutboxClasses"));
    for (var owner : owners.properties()) {
      int examined = 0;
      for (var directory : owner.getValue()) {
        final Path root = Path.of(directory.asText());
        if (!Files.isDirectory(root)) {
          continue;
        }
        try (var paths = Files.walk(root)) {
          for (Path file : paths.filter(path -> path.toString().endsWith(".class")).toList()) {
            assertTrue(
                producerReferences(Files.readAllBytes(file)).isEmpty(),
                () ->
                    "Direct Kafka producer dependency in "
                        + owner.getKey()
                        + ": "
                        + root.relativize(file));
            examined++;
          }
        }
      }
      assertTrue(examined > 0, "Owner classes were not compiled");
    }
  }

  @Test
  void guardRejectsProducerFieldDescriptors() throws Exception {
    try (var input =
        ForbiddenProducer.class.getResourceAsStream(
            "CompiledOutboxPublisherGuardTest$ForbiddenProducer.class")) {
      assertFalse(producerReferences(input.readAllBytes()).isEmpty());
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "KafkaTemplate",
        "RoutingKafkaTemplate",
        "ProducerFactory",
        "DefaultKafkaProducerFactory"
      })
  void guardRejectsEveryRetainedSpringProducerFamily(String type) {
    final byte[] owner =
        ClassFile.of()
            .build(
                ClassDesc.of("example.InvalidOutboxPublisher"),
                builder ->
                    builder.withField(
                        "publisher",
                        ClassDesc.of("org.springframework.kafka.core." + type),
                        ClassFile.ACC_PRIVATE));
    assertFalse(producerReferences(owner).isEmpty(), type);
  }

  private List<String> producerReferences(byte[] bytes) {
    final List<String> references = new ArrayList<>();
    for (var entry : ClassFile.of().parse(bytes).constantPool()) {
      final String value =
          switch (entry) {
            case ClassEntry type -> type.asInternalName();
            case Utf8Entry text -> text.stringValue();
            default -> "";
          };
      if (value.startsWith("org/apache/kafka/clients/producer/")
          || value.contains("Lorg/apache/kafka/clients/producer/")
          || SPRING_PRODUCER.matcher(value).find()) {
        references.add(value);
      }
    }
    return references;
  }

  private record ForbiddenProducer(
      org.apache.kafka.clients.producer.ProducerRecord<String, String> record) {}
}
