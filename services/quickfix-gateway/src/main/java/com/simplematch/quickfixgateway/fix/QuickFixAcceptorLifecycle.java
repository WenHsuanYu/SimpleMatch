package com.simplematch.quickfixgateway.fix;

import com.simplematch.quickfixgateway.config.QuickFixGatewayRuntime;
import java.io.InputStream;
import java.nio.file.Files;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.context.SmartLifecycle;
import quickfix.DefaultMessageFactory;
import quickfix.FileLogFactory;
import quickfix.FileStoreFactory;
import quickfix.MessageFactory;
import quickfix.SessionSettings;
import quickfix.SocketAcceptor;

public final class QuickFixAcceptorLifecycle implements SmartLifecycle {
  private static final Logger logger = LoggerFactory.getLogger(QuickFixAcceptorLifecycle.class);

  private final QuickFixApplicationAdapter application;
  private final QuickFixGatewayRuntime runtime;

  private volatile boolean running;
  private SocketAcceptor acceptor;

  public QuickFixAcceptorLifecycle(QuickFixApplicationAdapter application, QuickFixGatewayRuntime runtime) {
    this.application = application;
    this.runtime = runtime;
  }

  @Override
  public void start() {
    if (running) {
      return;
    }

    try (InputStream inputStream = Files.newInputStream(runtime.quickfixConfigPath())) {
      final SessionSettings settings = new SessionSettings(inputStream);
      final FileStoreFactory storeFactory = new FileStoreFactory(settings);
      final FileLogFactory logFactory = new FileLogFactory(settings);
      final MessageFactory messageFactory = new DefaultMessageFactory();
      acceptor = new SocketAcceptor(application, storeFactory, settings, logFactory, messageFactory);
      acceptor.start();
      running = true;
      logger.info(
          "quickfix-gateway acceptor started env={} quickfix_cfg={} wal={}",
          runtime.env(),
          runtime.quickfixConfigPath(),
          runtime.walPath());
    } catch (Exception e) {
      throw new IllegalStateException("failed to start QuickFix/J acceptor", e);
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
    logger.info("quickfix-gateway acceptor stopped");
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
    return 0;
  }
}