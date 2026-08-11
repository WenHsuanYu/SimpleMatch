package com.simplematch.quickfixgateway.fix;

import quickfix.SessionSettings;
import quickfix.SocketAcceptor;

/** Creates a QuickFIX/J acceptor after the Gateway lifecycle has loaded its session settings. */
@FunctionalInterface
public interface QuickFixAcceptorFactory {
  /**
   * Creates an acceptor bound to the supplied application and already-validated session settings.
   *
   * @param application Gateway callback adapter for the acceptor
   * @param settings settings loaded by the owning lifecycle
   * @return a stopped acceptor ready for the lifecycle to start
   * @throws Exception when QuickFIX/J cannot construct the configured acceptor
   */
  SocketAcceptor create(QuickFixApplicationAdapter application, SessionSettings settings)
      throws Exception;
}
