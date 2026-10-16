package com.simplematch.quickfixgateway.fix;

import com.simplematch.quickfixgateway.config.QuickFixGatewayRuntime;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Iterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.lang.NonNull;
import quickfix.SessionID;
import quickfix.SessionSettings;
import quickfix.SocketAcceptor;

/** Owns the QuickFIX/J acceptor lifecycle for one gateway runtime. */
public final class QuickFixAcceptorLifecycle implements SmartLifecycle {
  private static final Logger logger = LoggerFactory.getLogger(QuickFixAcceptorLifecycle.class);

  private final QuickFixApplicationAdapter application;
  private final QuickFixGatewayRuntime runtime;
  private final QuickFixAcceptorFactory acceptorFactory;

  private volatile boolean running;
  private SocketAcceptor acceptor;

  /** Creates an acceptor lifecycle using JDBC-backed QuickFIX state and runtime paths. */
  public QuickFixAcceptorLifecycle(
      QuickFixApplicationAdapter application,
      QuickFixGatewayRuntime runtime,
      QuickFixAcceptorFactory acceptorFactory) {
    this.application = application;
    this.runtime = runtime;
    this.acceptorFactory = acceptorFactory;
  }

  @Override
  public void start() {
    if (running) {
      return;
    }

    try (InputStream inputStream = Files.newInputStream(runtime.quickfixConfigPath())) {
      final SessionSettings settings = new SessionSettings(inputStream);
      validateSessionIdentities(settings);
      logger.info("{} starting...", runtime.quickfixConfigPath());

      acceptor = acceptorFactory.create(application, settings);
      acceptor.start();
      running = true;
      logger.info(
          "quickfix-gateway acceptor started env={} owner_id={} quickfix_cfg={} wal={} store=jdbc",
          runtime.env(),
          runtime.ownerId(),
          runtime.quickfixConfigPath(),
          runtime.walPath());
    } catch (Exception e) {
      throw new IllegalStateException("failed to start QuickFix/J acceptor", e);
    }
  }

  /** Checks the parsed, default-resolved identities before JDBC state or engine creation. */
  private static void validateSessionIdentities(SessionSettings settings) {
    final Iterator<SessionID> sessions = settings.sectionIterator();
    while (sessions.hasNext()) {
      final SessionID session = sessions.next();
      // Capacities belong to the Gateway-owned QuickFIX/J component columns, not FIX limits.
      validateRequiredComponent(session.getBeginString(), "BeginString", 8);
      validateRequiredComponent(session.getSenderCompID(), "SenderCompID", 64);
      validateRequiredComponent(session.getTargetCompID(), "TargetCompID", 64);
      validateComponent(session.getSenderSubID(), "SenderSubID", 64);
      validateComponent(session.getSenderLocationID(), "SenderLocationID", 64);
      validateComponent(session.getTargetSubID(), "TargetSubID", 64);
      validateComponent(session.getTargetLocationID(), "TargetLocationID", 64);
      validateComponent(session.getSessionQualifier(), "SessionQualifier", 64);
    }
  }

  private static void validateRequiredComponent(String value, String name, int capacity) {
    if (value.isEmpty()) {
      throw new IllegalArgumentException(name + " must not be empty");
    }
    validateComponent(value, name, capacity);
  }

  private static void validateComponent(String value, String name, int capacity) {
    if (value.codePointCount(0, value.length()) > capacity) {
      throw new IllegalArgumentException(
          name + " must contain at most " + capacity + " characters");
    }
  }

  @Override
  public void stop() {
    if (!running) {
      return;
    }

    if (acceptor != null) {
      try {
        acceptor.stop();
      } finally {
        acceptor = null;
      }
    }
    running = false;
    logger.info("quickfix-gateway acceptor stopped owner_id={}", runtime.ownerId());
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
  public int getPhase() {
    return 100;
  }
}
