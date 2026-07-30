package com.simplematch.quickfixgateway.health;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.lang.NonNull;

/** Runs startup recovery before the QuickFIX acceptor starts accepting client traffic. */
public final class QuickFixGatewayStartupLifecycle implements SmartLifecycle {
  private static final Logger logger =
      LoggerFactory.getLogger(QuickFixGatewayStartupLifecycle.class);

  private final String ownerId;
  private final QuickFixGatewayStartupRecovery startupRecovery;
  private final QuickFixGatewayStartupState startupState;

  private volatile boolean running;

  /** Creates the lifecycle that coordinates owner-scoped startup recovery. */
  public QuickFixGatewayStartupLifecycle(
      String ownerId,
      QuickFixGatewayStartupRecovery startupRecovery,
      QuickFixGatewayStartupState startupState) {
    this.ownerId = ownerId;
    this.startupRecovery = startupRecovery;
    this.startupState = startupState;
  }

  @Override
  public void start() {
    if (running) {
      return;
    }

    startupState.markRecovering();
    try {
      final int replayedRecords = startupRecovery.recover();
      startupState.markReady(replayedRecords);
      running = true;
      logger.info(
          "quickfix-gateway startup recovery completed owner_id={} replayed_wal_records={}",
          ownerId,
          replayedRecords);
    } catch (Exception e) {
      startupState.markFailed(e);
      throw new IllegalStateException(
          "quickfix-gateway startup recovery failed owner_id=" + ownerId, e);
    }
  }

  @Override
  public void stop() {
    running = false;
  }

  @Override
  public void stop(@NonNull Runnable callback) {
    stop();
    callback.run();
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  @Override
  public boolean isAutoStartup() {
    return true;
  }

  @Override
  public int getPhase() {
    return -100;
  }
}
