package com.simplematch.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.simplematch.contracts.account.v1.AccountServiceOuterClass;
import com.simplematch.contracts.common.v1.Common;
import com.simplematch.contracts.marketdata.v1.Marketdata;
import com.simplematch.contracts.marketdata.v1.MarketdataService;
import com.simplematch.contracts.matching.v1.Matching;
import com.simplematch.contracts.orders.v1.Orders;
import com.simplematch.contracts.risk.v1.RiskServiceOuterClass;
import java.io.IOException;
import java.util.List;
import java.util.Map;
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
    return ProtobufFieldInventory.load(getClass(), INVENTORY_RESOURCE);
  }

  private Map<String, String> generatedFieldInventory() {
    return ProtobufFieldInventory.fromDescriptors(
        List.of(
            AccountServiceOuterClass.getDescriptor(),
            Common.getDescriptor(),
            Marketdata.getDescriptor(),
            MarketdataService.getDescriptor(),
            Matching.getDescriptor(),
            Orders.getDescriptor(),
            RiskServiceOuterClass.getDescriptor()));
  }
}
