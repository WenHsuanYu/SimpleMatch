package com.simplematch.queryservice.runtime;

import com.simplematch.queryservice.config.QueryServiceProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Authenticated operator adapter for clearing only rebuildable query state. */
@RestController
@RequestMapping("/internal/query")
@ConditionalOnProperty(
    name = "simplematch.query-service.rebuild.http-enabled",
    havingValue = "true",
    matchIfMissing = false)
public final class QueryProjectionRebuildController {
  /** Header carrying the externally provisioned query rebuild token. */
  public static final String OPERATOR_TOKEN_HEADER = "X-SimpleMatch-Query-Token";

  private final QueryProjectionRebuildService rebuildService;
  private final QueryProjectionConsumerControl consumerControl;
  private final String operatorToken;

  /** Creates the authenticated HTTP adapter over the query-owned reset module. */
  public QueryProjectionRebuildController(
      QueryProjectionRebuildService rebuildService,
      QueryServiceProperties properties,
      QueryProjectionConsumerControl consumerControl) {
    this.rebuildService = rebuildService;
    this.consumerControl = consumerControl;
    this.operatorToken = properties.rebuild().operatorToken();
  }

  /** Stops both listeners and clears reconstructible state before Kafka offsets are reset. */
  @PostMapping("/rebuild")
  public RebuildResponse reset(
      @RequestHeader(value = OPERATOR_TOKEN_HEADER, required = false) String suppliedToken) {
    authorize(suppliedToken);
    consumerControl.stop();
    rebuildService.resetForReplay();
    return new RebuildResponse(
        "RESET_COMPLETE", "reset both query consumer groups, then restart query-service");
  }

  private void authorize(String suppliedToken) {
    if (suppliedToken == null
        || !MessageDigest.isEqual(
            operatorToken.getBytes(StandardCharsets.UTF_8),
            suppliedToken.getBytes(StandardCharsets.UTF_8))) {
      throw new ResponseStatusException(
          HttpStatus.UNAUTHORIZED, "query rebuild operator token is invalid");
    }
  }

  /** Response proving only that the query-owned local reset completed. */
  public record RebuildResponse(String status, String nextStep) {}
}
