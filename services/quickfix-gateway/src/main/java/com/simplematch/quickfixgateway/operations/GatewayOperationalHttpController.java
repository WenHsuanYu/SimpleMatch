package com.simplematch.quickfixgateway.operations;

import com.simplematch.quickfixgateway.config.QuickFixGatewayOperationsProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Authenticated HTTP adapter for the single Gateway operational command boundary. */
@RestController
@RequestMapping("/operations")
@ConditionalOnProperty(
    name = "simplematch.quickfix-gateway.operations.http-enabled",
    havingValue = "true",
    matchIfMissing = false)
public final class GatewayOperationalHttpController {
  /** Header carrying the externally provisioned operator token. */
  public static final String OPERATOR_TOKEN_HEADER = "X-SimpleMatch-Operator-Token";

  private final GatewayOperationalCommandHandler commandHandler;
  private final GatewayOperationalController controller;
  private final String operatorToken;

  /** Creates the authenticated HTTP adapter over the transport-neutral Gateway boundary. */
  public GatewayOperationalHttpController(
      GatewayOperationalCommandHandler commandHandler,
      GatewayOperationalController controller,
      QuickFixGatewayOperationsProperties properties) {
    this.commandHandler = commandHandler;
    this.controller = controller;
    this.operatorToken = properties.operatorToken();
  }

  /** Returns the current fail-closed operational status. */
  @GetMapping("/status")
  public GatewayOperationResult status(
      @RequestHeader(value = OPERATOR_TOKEN_HEADER, required = false) String suppliedToken) {
    authorize(suppliedToken);
    return commandHandler.execute(
        new GatewayOperationalCommand(GatewayOperation.STATUS, "http-operator", "status"));
  }

  /** Executes one of the fixed operational commands. */
  @PostMapping("/{operation}")
  public GatewayOperationResult execute(
      @PathVariable String operation,
      @Valid @RequestBody OperatorCommandRequest request,
      @RequestHeader(value = OPERATOR_TOKEN_HEADER, required = false) String suppliedToken) {
    authorize(suppliedToken);
    final GatewayOperation parsedOperation = parseOperation(operation);
    return commandHandler.execute(
        new GatewayOperationalCommand(parsedOperation, request.actor(), request.reason()));
  }

  /** Accepts one complete infrastructure observation and reevaluates admission. */
  @PostMapping("/observations")
  public TradingSystemStatus report(
      @Valid @RequestBody TradingSystemObservation observation,
      @RequestHeader(value = OPERATOR_TOKEN_HEADER, required = false) String suppliedToken) {
    authorize(suppliedToken);
    return controller.report(observation);
  }

  private void authorize(String suppliedToken) {
    if (suppliedToken == null
        || !MessageDigest.isEqual(
            operatorToken.getBytes(StandardCharsets.UTF_8),
            suppliedToken.getBytes(StandardCharsets.UTF_8))) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "operator token is invalid");
    }
  }

  private static GatewayOperation parseOperation(String rawOperation) {
    try {
      return GatewayOperation.valueOf(
          rawOperation.trim().replace('-', '_').toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException invalid) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "unsupported Gateway operation: " + rawOperation, invalid);
    }
  }

  /** JSON request body for an explicit operator operation. */
  public record OperatorCommandRequest(@NotBlank String actor, @NotBlank String reason) {}
}
