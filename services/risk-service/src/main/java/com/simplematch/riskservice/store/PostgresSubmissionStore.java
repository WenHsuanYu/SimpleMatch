package com.simplematch.riskservice.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplematch.contracts.common.v1.EventMetadata;
import com.simplematch.contracts.common.v1.OrderType;
import com.simplematch.contracts.common.v1.Side;
import com.simplematch.contracts.orders.v1.CommandType;
import com.simplematch.contracts.orders.v1.OrderCommand;
import com.simplematch.contracts.orders.v1.OrderRejected;
import com.simplematch.contracts.orders.v1.OrderValidated;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionTemplate;

public final class PostgresSubmissionStore implements SubmissionStore {
  private static final RowMapper<StoredSubmission> STORED_SUBMISSION_ROW_MAPPER = (resultSet, rowNum) ->
      new StoredSubmission(
          resultSet.getString("idempotency_key"),
          resultSet.getString("request_id"),
          resultSet.getString("order_id"),
          resultSet.getString("client_order_id"),
          resultSet.getString("original_client_order_id"),
          resultSet.getString("command_type"),
          resultSet.getBoolean("accepted"),
          resultSet.getString("reason_code"),
          resultSet.getString("reason_text"),
          resultSet.getLong("created_at_unix_ms"));

  private final JdbcTemplate jdbcTemplate;
  private final TransactionTemplate transactionTemplate;
  private final ObjectMapper objectMapper;
  private final String ordersValidatedTopic;

  public PostgresSubmissionStore(
      JdbcTemplate jdbcTemplate,
      TransactionTemplate transactionTemplate,
      ObjectMapper objectMapper,
      String ordersValidatedTopic) {
    this.jdbcTemplate = jdbcTemplate;
    this.transactionTemplate = transactionTemplate;
    this.objectMapper = objectMapper;
    this.ordersValidatedTopic = ordersValidatedTopic;
  }

  @Override
  public StoredSubmission persist(OrderCommand command) {
    final String idempotencyKey = idempotencyKey(command);
    final StoredSubmission validatedSubmission = validate(command, idempotencyKey);
    final String outboxEventId = outboxEventId(validatedSubmission);

    final StoredSubmission storedSubmission = transactionTemplate.execute(status -> {
      final StoredSubmission existing = findByIdempotencyKey(idempotencyKey);
      if (existing != null) {
        return existing;
      }

      try {
        insertSubmission(validatedSubmission, outboxEventId);
        insertOutbox(validatedSubmission, normalize(command, commandType(validatedSubmission)), outboxEventId);
        return validatedSubmission;
      } catch (DuplicateKeyException duplicateKeyException) {
        final StoredSubmission duplicate = findByIdempotencyKey(idempotencyKey);
        if (duplicate != null) {
          return duplicate;
        }
        throw duplicateKeyException;
      }
    });

    if (storedSubmission == null) {
      throw new IllegalStateException("risk submission transaction returned null");
    }
    return storedSubmission;
  }

  private StoredSubmission findByIdempotencyKey(String idempotencyKey) {
    return jdbcTemplate.query(
            """
                SELECT idempotency_key, request_id, order_id, client_order_id, original_client_order_id,
                       command_type, accepted, reason_code, reason_text, created_at_unix_ms
                FROM risk_submissions
                WHERE idempotency_key = ?
                """,
            STORED_SUBMISSION_ROW_MAPPER,
            idempotencyKey)
        .stream()
        .findFirst()
        .orElse(null);
  }

  private void insertSubmission(StoredSubmission submission, String outboxEventId) {
    jdbcTemplate.update(
        """
            INSERT INTO risk_submissions (
              idempotency_key,
              request_id,
              order_id,
              client_order_id,
              original_client_order_id,
              command_type,
              accepted,
              reason_code,
              reason_text,
              created_at_unix_ms,
              outbox_event_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
        submission.idempotencyKey(),
        submission.requestId(),
        submission.orderId(),
        submission.clientOrderId(),
        submission.originalClientOrderId(),
        submission.commandType(),
        submission.accepted(),
        submission.reasonCode(),
        submission.reasonText(),
        submission.createdAtUnixMs(),
        outboxEventId);
  }

  private void insertOutbox(StoredSubmission submission, OrderCommand command, String outboxEventId) {
    final byte[] payload = payloadBytes(submission, command, outboxEventId);
    jdbcTemplate.update(
        """
            INSERT INTO outbox (
              event_id,
              topic,
              message_key,
              payload,
              payload_type,
              headers_json,
              aggregate_type,
              aggregate_id,
              created_at_unix_ms
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
        outboxEventId,
        ordersValidatedTopic,
        messageKey(command),
        payload,
        payloadType(submission),
        headersJson(submission, outboxEventId),
        "risk_submission",
        submission.orderId(),
        submission.createdAtUnixMs());
  }

  private byte[] payloadBytes(StoredSubmission submission, OrderCommand command, String outboxEventId) {
    if (submission.accepted()) {
      return OrderValidated.newBuilder()
          .setMetadata(eventMetadata(outboxEventId, submission.createdAtUnixMs()))
          .setCommandId(submission.requestId())
          .setOrderId(submission.orderId())
          .setAccountId(command.getAccountId())
          .setSymbol(command.getSymbol())
          .build()
          .toByteArray();
    }

    return OrderRejected.newBuilder()
        .setMetadata(eventMetadata(outboxEventId, submission.createdAtUnixMs()))
        .setCommandId(submission.requestId())
        .setOrderId(submission.orderId())
        .setAccountId(command.getAccountId())
        .setSymbol(command.getSymbol())
        .setRejectReasonCode(submission.reasonCode())
        .setRejectReasonText(submission.reasonText())
        .build()
        .toByteArray();
  }

  private EventMetadata eventMetadata(String outboxEventId, long createdAtUnixMs) {
    return EventMetadata.newBuilder()
        .setSchemaVersion("v1")
        .setEventId(outboxEventId)
        .setCreatedAtUnixMs(createdAtUnixMs)
        .setSourceService("risk-service")
        .build();
  }

  private String payloadType(StoredSubmission submission) {
    return submission.accepted()
        ? OrderValidated.getDescriptor().getFullName()
        : OrderRejected.getDescriptor().getFullName();
  }

  private String headersJson(StoredSubmission submission, String outboxEventId) {
    try {
      return objectMapper.writeValueAsString(Map.of(
          "event_id", outboxEventId,
          "content_type", "application/x-protobuf",
          "payload_type", payloadType(submission)));
    } catch (JsonProcessingException jsonProcessingException) {
      throw new IllegalStateException("failed to serialize outbox headers", jsonProcessingException);
    }
  }

  private String messageKey(OrderCommand command) {
    if (command != null && !command.getSymbol().isBlank()) {
      return command.getSymbol();
    }
    if (command != null && !command.getOrderId().isBlank()) {
      return command.getOrderId();
    }
    return "UNKNOWN";
  }

  private StoredSubmission validate(OrderCommand command, String idempotencyKey) {
    final long now = Instant.now().toEpochMilli();
    if (command == null || OrderCommand.getDefaultInstance().equals(command)) {
      return rejected(idempotencyKey, "", "", "", "", CommandType.COMMAND_TYPE_UNSPECIFIED, now, "EMPTY_COMMAND", "risk command payload is required");
    }
    if (command.getClientOrderId().isBlank()) {
      return rejected(idempotencyKey, command.getCommandId(), command.getOrderId(), "", command.getOriginalClientOrderId(), command.getCommandType(), now, "MISSING_CLIENT_ORDER_ID", "client_order_id is required");
    }
    if (command.getOrderId().isBlank()) {
      return rejected(idempotencyKey, command.getCommandId(), "", command.getClientOrderId(), command.getOriginalClientOrderId(), command.getCommandType(), now, "MISSING_ORDER_ID", "order_id is required");
    }

    if (command.getCommandType() == CommandType.COMMAND_TYPE_NEW) {
      if (command.getAccountId().isBlank()) {
        return rejected(idempotencyKey, command.getCommandId(), command.getOrderId(), command.getClientOrderId(), command.getOriginalClientOrderId(), command.getCommandType(), now, "MISSING_ACCOUNT_ID", "account_id is required");
      }
      if (command.getSymbol().isBlank()) {
        return rejected(idempotencyKey, command.getCommandId(), command.getOrderId(), command.getClientOrderId(), command.getOriginalClientOrderId(), command.getCommandType(), now, "MISSING_SYMBOL", "symbol is required");
      }
      if (command.getQuantity().isBlank()) {
        return rejected(idempotencyKey, command.getCommandId(), command.getOrderId(), command.getClientOrderId(), command.getOriginalClientOrderId(), command.getCommandType(), now, "MISSING_QUANTITY", "quantity is required");
      }
      if (command.getSide() == Side.SIDE_UNSPECIFIED) {
        return rejected(idempotencyKey, command.getCommandId(), command.getOrderId(), command.getClientOrderId(), command.getOriginalClientOrderId(), command.getCommandType(), now, "MISSING_SIDE", "side is required");
      }
      if (command.getOrderType() == OrderType.ORDER_TYPE_LIMIT && command.getPrice().isBlank()) {
        return rejected(idempotencyKey, command.getCommandId(), command.getOrderId(), command.getClientOrderId(), command.getOriginalClientOrderId(), command.getCommandType(), now, "MISSING_PRICE", "price is required for limit orders");
      }
    }

    if (command.getCommandType() == CommandType.COMMAND_TYPE_CANCEL && command.getOriginalClientOrderId().isBlank()) {
      return rejected(idempotencyKey, command.getCommandId(), command.getOrderId(), command.getClientOrderId(), "", command.getCommandType(), now, "MISSING_ORIGINAL_CLIENT_ORDER_ID", "original_client_order_id is required for cancel requests");
    }

    return new StoredSubmission(
        idempotencyKey,
        command.getCommandId(),
        command.getOrderId(),
        command.getClientOrderId(),
        command.getOriginalClientOrderId(),
        command.getCommandType().name(),
        true,
        "",
        "",
        now);
  }

  private StoredSubmission rejected(
      String idempotencyKey,
      String requestId,
      String orderId,
      String clientOrderId,
      String originalClientOrderId,
      CommandType commandType,
      long createdAtUnixMs,
      String reasonCode,
      String reasonText) {
    return new StoredSubmission(
        idempotencyKey,
        requestId,
        orderId,
        clientOrderId,
        originalClientOrderId,
        commandType.name(),
        false,
        reasonCode,
        reasonText,
        createdAtUnixMs);
  }

  private String idempotencyKey(OrderCommand command) {
    if (command == null) {
      return "UNKNOWN|";
    }
    return command.getCommandType().name() + "|" + command.getClientOrderId();
  }

  private String outboxEventId(StoredSubmission submission) {
    final String source = submission.idempotencyKey()
        + "|"
        + submission.requestId()
        + "|"
        + submission.orderId()
        + "|"
        + submission.reasonCode()
        + "|"
        + submission.accepted();
    return UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8)).toString();
  }

  private CommandType commandType(StoredSubmission submission) {
    return CommandType.valueOf(submission.commandType());
  }

  private OrderCommand normalize(OrderCommand command, CommandType expectedType) {
    if (command == null || OrderCommand.getDefaultInstance().equals(command)) {
      return OrderCommand.newBuilder().setCommandType(expectedType).build();
    }
    if (command.getCommandType() == expectedType) {
      return command;
    }
    return command.toBuilder().setCommandType(expectedType).build();
  }
}