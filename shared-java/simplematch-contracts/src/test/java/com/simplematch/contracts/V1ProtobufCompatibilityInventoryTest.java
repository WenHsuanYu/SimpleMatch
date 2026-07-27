package com.simplematch.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.simplematch.contracts.account.v1.AccountServiceOuterClass;
import com.simplematch.contracts.common.v1.Common;
import com.simplematch.contracts.marketdata.v1.Marketdata;
import com.simplematch.contracts.marketdata.v1.MarketdataService;
import com.simplematch.contracts.matching.v1.Matching;
import com.simplematch.contracts.orders.v1.Orders;
import com.simplematch.contracts.risk.v1.RiskServiceOuterClass;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class V1ProtobufCompatibilityInventoryTest {
  private static final String INVENTORY_RESOURCE = "/v1-protobuf-field-numbers.properties";

  @DisplayName("generated v1 descriptors match the checked-in message field inventory")
  @Test
  void generatedDescriptorsMatchCheckedInV1FieldInventory() throws IOException {
    assertEquals(loadExpectedInventory(), generatedFieldInventory());
  }

  private Map<String, String> loadExpectedInventory() throws IOException {
    final InputStream resourceStream = getClass().getResourceAsStream(INVENTORY_RESOURCE);
    assertNotNull(resourceStream, "missing v1 Protobuf compatibility inventory");
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

  private Map<String, String> generatedFieldInventory() {
    final Map<String, String> fields = new LinkedHashMap<>();
    for (FileDescriptor fileDescriptor : List.of(
        AccountServiceOuterClass.getDescriptor(),
        Common.getDescriptor(),
        Marketdata.getDescriptor(),
        MarketdataService.getDescriptor(),
        Matching.getDescriptor(),
        Orders.getDescriptor(),
        RiskServiceOuterClass.getDescriptor())) {
      for (Descriptor messageDescriptor : fileDescriptor.getMessageTypes()) {
        addMessageFields(messageDescriptor, fields);
      }
    }
    return fields;
  }

  private void addMessageFields(Descriptor messageDescriptor, Map<String, String> fields) {
    messageDescriptor.getFields().forEach(field -> fields.put(field.getFullName(), Integer.toString(field.getNumber())));
    messageDescriptor.getNestedTypes().forEach(nested -> addMessageFields(nested, fields));
  }
}
