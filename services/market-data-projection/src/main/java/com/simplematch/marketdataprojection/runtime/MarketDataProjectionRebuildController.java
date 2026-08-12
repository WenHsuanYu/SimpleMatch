package com.simplematch.marketdataprojection.runtime;

import com.simplematch.marketdataprojection.config.MarketDataProjectionProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Authenticated operator adapter for clearing only rebuildable market-data state. */
@RestController
@RequestMapping("/internal/market-data")
@ConditionalOnProperty(
    name = "simplematch.market-data-projection.rebuild.http-enabled",
    havingValue = "true",
    matchIfMissing = false)
public final class MarketDataProjectionRebuildController {
  /** Header carrying the externally provisioned projection operator token. */
  public static final String OPERATOR_TOKEN_HEADER = "X-SimpleMatch-Projection-Token";

  private final MarketDataProjectionRebuildService rebuildService;
  private final MarketDataProjectionConsumerControl consumerControl;
  private final String operatorToken;

  /** Creates the authenticated HTTP adapter over the projection-owned reset service. */
  public MarketDataProjectionRebuildController(
      MarketDataProjectionRebuildService rebuildService,
      MarketDataProjectionProperties properties,
      MarketDataProjectionConsumerControl consumerControl) {
    this.rebuildService = rebuildService;
    this.operatorToken = properties.rebuild().operatorToken();
    this.consumerControl = consumerControl;
  }

  /** Clears reconstructible projection state before an operator resets the Kafka group offsets. */
  @PostMapping("/rebuild")
  public RebuildResponse reset(
      @RequestHeader(value = OPERATOR_TOKEN_HEADER, required = false) String suppliedToken) {
    authorize(suppliedToken);
    consumerControl.stop();
    rebuildService.resetForReplay();
    return new RebuildResponse(
        "RESET_COMPLETE", "reset Kafka consumer offsets, then restart the projection");
  }

  private void authorize(String suppliedToken) {
    if (suppliedToken == null
        || !MessageDigest.isEqual(
            operatorToken.getBytes(StandardCharsets.UTF_8),
            suppliedToken.getBytes(StandardCharsets.UTF_8))) {
      throw new ResponseStatusException(
          HttpStatus.UNAUTHORIZED, "projection operator token is invalid");
    }
  }

  /** Response proving that the service-owned reset transaction completed. */
  public record RebuildResponse(String status, String nextStep) {}
}
