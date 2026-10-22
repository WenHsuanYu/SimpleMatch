package com.simplematch.quickfixgateway.fix;

import com.simplematch.quickfixgateway.config.QuickFixGatewayRuntime;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Iterator;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.lang.NonNull;
import quickfix.ConfigError;
import quickfix.FieldConvertError;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionSettings;
import quickfix.SocketAcceptor;
import quickfix.mina.ssl.SSLSupport;

/** Owns the QuickFIX/J acceptor lifecycle for one gateway runtime. */
public final class QuickFixAcceptorLifecycle implements SmartLifecycle {
  private static final Logger logger = LoggerFactory.getLogger(QuickFixAcceptorLifecycle.class);

  private final QuickFixApplicationAdapter application;
  private final QuickFixGatewayRuntime runtime;
  private final QuickFixAcceptorFactory acceptorFactory;
  private final Properties variables;

  private volatile boolean running;
  private SocketAcceptor acceptor;

  /** Creates an acceptor lifecycle using JDBC-backed QuickFIX state and runtime paths. */
  public QuickFixAcceptorLifecycle(
      QuickFixApplicationAdapter application,
      QuickFixGatewayRuntime runtime,
      QuickFixAcceptorFactory acceptorFactory) {
    this(application, runtime, acceptorFactory, new Properties());
  }

  /**
   * Creates a lifecycle with secret variables for the explicitly configured FIX TLS stores.
   *
   * @param application authenticated FIX callback adapter
   * @param runtime runtime paths and process identity
   * @param acceptorFactory JDBC-backed acceptor factory
   * @param variables whitelisted TLS password variables resolved in the configuration
   */
  public QuickFixAcceptorLifecycle(
      QuickFixApplicationAdapter application,
      QuickFixGatewayRuntime runtime,
      QuickFixAcceptorFactory acceptorFactory,
      Properties variables) {
    this.application = application;
    this.runtime = runtime;
    this.acceptorFactory = acceptorFactory;
    this.variables = new Properties();
    this.variables.putAll(variables);
  }

  @Override
  public void start() {
    if (running) {
      return;
    }

    try (InputStream inputStream = Files.newInputStream(runtime.quickfixConfigPath())) {
      final SessionSettings settings = new SessionSettings(inputStream, variables);
      validateSessionIdentities(settings);
      validateSecureTransport(settings);
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

  /**
   * Prevents plaintext listeners, implicit trust-all TLS, and credential leaks from unknown peers.
   */
  private static void validateSecureTransport(SessionSettings settings)
      throws ConfigError, FieldConvertError {
    if (!settings.isSetting(Session.SETTING_LOG_MESSAGE_WHEN_SESSION_NOT_FOUND)
        || settings.getBool(Session.SETTING_LOG_MESSAGE_WHEN_SESSION_NOT_FOUND)) {
      throw new IllegalArgumentException(
          "FIX unknown-session raw logging must be explicitly disabled");
    }
    final Iterator<SessionID> sessions = settings.sectionIterator();
    while (sessions.hasNext()) {
      final SessionID session = sessions.next();
      if (!settings.isSetting(session, SSLSupport.SETTING_USE_SSL)
          || !settings.getBool(session, SSLSupport.SETTING_USE_SSL)
          || !SSLSupport.isNeedClientAuth(settings, session)) {
        throw new IllegalArgumentException("FIX requires SocketUseSSL=Y and NeedClientAuth=Y");
      }
      requireTlsStore(settings, session, SSLSupport.SETTING_KEY_STORE_NAME);
      requireTlsSetting(settings, session, SSLSupport.SETTING_KEY_STORE_PWD);
      requireTlsStore(settings, session, SSLSupport.SETTING_TRUST_STORE_NAME);
      requireTlsSetting(settings, session, SSLSupport.SETTING_TRUST_STORE_PWD);
    }
  }

  private static void requireTlsStore(SessionSettings settings, SessionID session, String name)
      throws ConfigError {
    final String value = requireTlsSetting(settings, session, name);
    final var path = java.nio.file.Path.of(value);
    if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
      throw new IllegalArgumentException("FIX requires readable file for " + name);
    }
  }

  private static String requireTlsSetting(SessionSettings settings, SessionID session, String name)
      throws ConfigError {
    final String value = settings.isSetting(session, name) ? settings.getString(session, name) : "";
    if (value.isBlank() || value.contains("${")) {
      throw new IllegalArgumentException("FIX requires explicit " + name);
    }
    return value;
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
