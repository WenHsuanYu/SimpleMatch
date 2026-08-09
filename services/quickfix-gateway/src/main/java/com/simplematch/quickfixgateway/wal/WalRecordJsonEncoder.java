package com.simplematch.quickfixgateway.wal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.simplematch.contracts.common.v1.OrderType;
import com.simplematch.contracts.common.v1.TimeInForce;

/** Encodes semantic WAL records into the stable flat JSON shape. */
final class WalRecordJsonEncoder {
  private final ObjectMapper objectMapper;

  WalRecordJsonEncoder(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  String encode(WalRecord record) {
    try {
      final ObjectNode json = objectMapper.createObjectNode();
      final WalMetadata metadata = record.metadata();
      final FixSessionIdentity session = record.session();
      final WalOrderReference reference = record.orderReference();
      json.put("schemaVersion", metadata.schemaVersion());
      json.put("recordId", metadata.recordId());
      json.put("createdAtUnixMs", metadata.createdAtUnixMs());
      json.put("sourceService", metadata.sourceService());
      json.put("senderCompId", session.senderCompId());
      json.put("targetCompId", session.targetCompId());
      json.put("messageType", record.command().messageType());
      json.put("orderId", reference.orderId());
      json.put("clOrdId", reference.clOrdId());
      json.put("origClOrdId", reference.origClOrdId());
      json.put("accountId", reference.accountId());
      writeCommandFields(json, record.command());
      json.put("rawFix", record.rawFix());
      return objectMapper.writeValueAsString(json);
    } catch (JsonProcessingException exception) {
      throw new WalRecordCodecException("failed to encode WAL record", exception);
    }
  }

  private void writeCommandFields(ObjectNode json, WalCommand command) {
    if (command instanceof WalCommand.NewOrder newOrder) {
      final WalOrderTerms terms = newOrder.terms();
      json.put("symbol", terms.symbol());
      json.put("side", terms.side().name());
      json.put("quantity", terms.quantity());
      json.put("price", terms.price());
      json.put("orderType", terms.orderType().name());
      json.put("tif", terms.tif().name());
      json.put("commandType", newOrder.commandType().name());
      return;
    }
    final WalCommand.Cancel cancel = (WalCommand.Cancel) command;
    json.put("symbol", cancel.symbol());
    json.put("side", cancel.side().name());
    json.put("quantity", "");
    json.put("price", "");
    json.put("orderType", OrderType.ORDER_TYPE_UNSPECIFIED.name());
    json.put("tif", TimeInForce.TIME_IN_FORCE_UNSPECIFIED.name());
    json.put("commandType", cancel.commandType().name());
  }
}
