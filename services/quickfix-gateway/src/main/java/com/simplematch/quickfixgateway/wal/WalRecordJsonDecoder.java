package com.simplematch.quickfixgateway.wal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.simplematch.contracts.common.v1.OrderType;
import com.simplematch.contracts.common.v1.Side;
import com.simplematch.contracts.common.v1.TimeInForce;
import com.simplematch.contracts.orders.v1.CommandType;

/** Rehydrates and validates semantic records from the flat v1 JSON shape. */
final class WalRecordJsonDecoder {
  private final ObjectReader strictJsonReader;

  WalRecordJsonDecoder(ObjectMapper objectMapper) {
    this.strictJsonReader =
        objectMapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  WalRecord decode(String line) {
    try {
      final JsonNode json = strictJsonReader.readTree(line);
      final WalJsonDocument document = new WalJsonDocument(json);
      final WalMetadata metadata =
          new WalMetadata(
              document.requiredText("schemaVersion"),
              document.requiredText("recordId"),
              document.requiredLong("createdAtUnixMs"),
              document.requiredText("sourceService"));
      final FixSessionIdentity session =
          new FixSessionIdentity(
              document.requiredText("senderCompId"), document.requiredText("targetCompId"));
      final WalOrderReference reference =
          new WalOrderReference(
              document.requiredText("orderId"),
              document.requiredText("clOrdId"),
              document.optionalText("origClOrdId"),
              document.optionalText("accountId"));
      final WalCommand command = decodeCommand(document, reference);
      return new WalRecord(
          metadata,
          session,
          reference,
          command,
          new RawFixMessage(document.requiredText("rawFix")));
    } catch (JsonProcessingException exception) {
      throw new WalRecordCodecException("WAL line is not valid JSON", exception);
    } catch (WalRecordCodecException exception) {
      throw exception;
    } catch (IllegalArgumentException exception) {
      throw new WalRecordCodecException(exception.getMessage(), exception);
    }
  }

  private WalCommand decodeCommand(WalJsonDocument document, WalOrderReference reference) {
    final String messageType = document.requiredText("messageType");
    return switch (document.enumValue("commandType", CommandType.class)) {
      case COMMAND_TYPE_NEW -> decodeNewOrder(document, reference, messageType);
      case COMMAND_TYPE_CANCEL -> decodeCancel(document, reference, messageType);
      default ->
          throw new WalRecordCodecException(
              "message_type and command_type do not form a permitted WAL command pair");
    };
  }

  private WalCommand decodeNewOrder(
      WalJsonDocument document, WalOrderReference reference, String messageType) {
    requireMessageType(messageType, quickfix.fix44.NewOrderSingle.MSGTYPE);
    if (!reference.origClOrdId().isEmpty()) {
      throw new WalRecordCodecException("new order must not have orig_cl_ord_id");
    }
    return new WalCommand.NewOrder(
        new WalOrderTerms(
            document.requiredText("symbol"),
            document.enumValue("side", Side.class),
            document.requiredText("quantity"),
            document.optionalText("price"),
            document.enumValue("orderType", OrderType.class),
            document.enumValue("tif", TimeInForce.class)));
  }

  private WalCommand decodeCancel(
      WalJsonDocument document, WalOrderReference reference, String messageType) {
    requireMessageType(messageType, quickfix.fix44.OrderCancelRequest.MSGTYPE);
    if (reference.origClOrdId().isEmpty()) {
      throw new WalRecordCodecException("cancel must have orig_cl_ord_id");
    }
    document.requirePlaceholder("symbol", "");
    document.requirePlaceholder("side", Side.SIDE_UNSPECIFIED.name());
    document.requirePlaceholder("quantity", "");
    document.requirePlaceholder("price", "");
    document.requirePlaceholder("orderType", OrderType.ORDER_TYPE_UNSPECIFIED.name());
    document.requirePlaceholder("tif", TimeInForce.TIME_IN_FORCE_UNSPECIFIED.name());
    return new WalCommand.Cancel();
  }

  private void requireMessageType(String actual, String expected) {
    if (!expected.equals(actual)) {
      throw new WalRecordCodecException(
          "message_type and command_type do not form a permitted WAL command pair");
    }
  }
}
