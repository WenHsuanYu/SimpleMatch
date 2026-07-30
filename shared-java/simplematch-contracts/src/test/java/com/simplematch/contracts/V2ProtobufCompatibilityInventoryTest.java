package com.simplematch.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.simplematch.contracts.account.v2.AccountV2;
import com.simplematch.contracts.common.v2.CommonV2;
import com.simplematch.contracts.matching.v2.MatchingV2;
import com.simplematch.contracts.orders.v2.OrdersV2;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class V2ProtobufCompatibilityInventoryTest {
  private static final String INVENTORY_RESOURCE = "/v2-protobuf-field-numbers.properties";

  @DisplayName("generated v2 descriptors match the checked-in message field inventory")
  @Test
  void generatedDescriptorsMatchCheckedInV2FieldInventory() throws IOException {
    assertEquals(
        ProtobufFieldInventory.load(getClass(), INVENTORY_RESOURCE),
        ProtobufFieldInventory.fromDescriptors(
            List.of(
                AccountV2.getDescriptor(),
                CommonV2.getDescriptor(),
                MatchingV2.getDescriptor(),
                OrdersV2.getDescriptor())));
  }
}
