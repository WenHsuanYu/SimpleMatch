package com.simplematch.contracts;

import com.simplematch.contracts.account.v2.AccountV2;
import com.simplematch.contracts.common.v2.CommonV2;
import com.simplematch.contracts.matching.v2.MatchingV2;
import com.simplematch.contracts.orders.v2.OrdersV2;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class V2ProtobufEnumCompatibilityInventoryTest {
    private static final String INVENTORY_RESOURCE = "/v2-protobuf-enum-values.properties";

    @DisplayName("generated v2 enum values match the checked-in compatibility inventory")
    @Test
    void generatedDescriptorsMatchCheckedInV2EnumInventory() throws IOException {
        assertEquals(
                ProtobufFieldInventory.load(getClass(), INVENTORY_RESOURCE),
                ProtobufFieldInventory.enumValues(List.of(
                        AccountV2.getDescriptor(),
                        CommonV2.getDescriptor(),
                        MatchingV2.getDescriptor(),
                        OrdersV2.getDescriptor())));
    }
}
