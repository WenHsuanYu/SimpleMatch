package com.simplematch.contracts;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** Loads and derives checked-in Protobuf message-field inventories. */
final class ProtobufFieldInventory {
  private ProtobufFieldInventory() {}

  static Map<String, String> load(Class<?> resourceOwner, String resourcePath) throws IOException {
    final InputStream resourceStream = resourceOwner.getResourceAsStream(resourcePath);
    assertNotNull(resourceStream, "missing Protobuf compatibility inventory");
    final Properties inventory = new Properties();
    try (InputStream input = resourceStream) {
      inventory.load(input);
    }

    final Map<String, String> expected = new LinkedHashMap<>();
    for (String key : inventory.stringPropertyNames()) {
      expected.put(key, inventory.getProperty(key));
    }
    return expected;
  }

  static Map<String, String> fromDescriptors(List<FileDescriptor> fileDescriptors) {
    final Map<String, String> fields = new LinkedHashMap<>();
    for (FileDescriptor fileDescriptor : fileDescriptors) {
      for (Descriptor messageDescriptor : fileDescriptor.getMessageTypes()) {
        addMessageFields(messageDescriptor, fields);
      }
    }
    return fields;
  }

  static Map<String, String> enumValues(List<FileDescriptor> fileDescriptors) {
    final Map<String, String> values = new LinkedHashMap<>();
    for (FileDescriptor fileDescriptor : fileDescriptors) {
      for (EnumDescriptor enumDescriptor : fileDescriptor.getEnumTypes()) {
        enumDescriptor
            .getValues()
            .forEach(value -> values.put(value.getFullName(), Integer.toString(value.getNumber())));
      }
    }
    return values;
  }

  private static void addMessageFields(Descriptor messageDescriptor, Map<String, String> fields) {
    messageDescriptor
        .getFields()
        .forEach(field -> fields.put(field.getFullName(), Integer.toString(field.getNumber())));
    messageDescriptor.getNestedTypes().forEach(nested -> addMessageFields(nested, fields));
  }
}
