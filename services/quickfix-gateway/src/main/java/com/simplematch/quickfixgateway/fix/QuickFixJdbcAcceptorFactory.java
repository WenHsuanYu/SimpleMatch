package com.simplematch.quickfixgateway.fix;

import java.util.Objects;
import java.util.function.Supplier;
import javax.sql.DataSource;
import quickfix.DefaultMessageFactory;
import quickfix.JdbcLogFactory;
import quickfix.JdbcStoreFactory;
import quickfix.MessageFactory;
import quickfix.SessionSettings;
import quickfix.SocketAcceptor;

/** Creates JDBC-backed QuickFIX/J acceptors from the Gateway's persistence capability. */
public final class QuickFixJdbcAcceptorFactory implements QuickFixAcceptorFactory {
  private final Supplier<DataSource> dataSourceSupplier;

  /**
   * Creates a factory that obtains its persistence resource only when an acceptor is started.
   *
   * @param dataSourceSupplier resolves the Gateway-owned JDBC resource at lifecycle start
   */
  public QuickFixJdbcAcceptorFactory(Supplier<DataSource> dataSourceSupplier) {
    this.dataSourceSupplier = dataSourceSupplier;
  }

  /** Creates an acceptor with JDBC-backed QuickFIX/J session and message stores. */
  @Override
  public SocketAcceptor create(QuickFixApplicationAdapter application, SessionSettings settings)
      throws Exception {
    final DataSource dataSource =
        Objects.requireNonNull(dataSourceSupplier.get(), "dataSource supplier returned null");
    final JdbcStoreFactory storeFactory = new JdbcStoreFactory(settings);
    storeFactory.setDataSource(dataSource);
    final JdbcLogFactory logFactory = new JdbcLogFactory(settings);
    logFactory.setDataSource(dataSource);
    final MessageFactory messageFactory = new DefaultMessageFactory();
    return new SocketAcceptor(application, storeFactory, settings, logFactory, messageFactory);
  }
}
