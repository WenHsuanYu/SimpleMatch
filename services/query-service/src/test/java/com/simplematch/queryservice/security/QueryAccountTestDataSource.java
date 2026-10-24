package com.simplematch.queryservice.security;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/** Controls database failures and concurrent replies without replacing Query application modules. */
final class QueryAccountTestDataSource extends DelegatingDataSource {
  private final AtomicReference<Runnable> afterAccountRead = new AtomicReference<>();
  private volatile boolean unavailable;
  private volatile UUID returnedAccount;

  QueryAccountTestDataSource(DataSource delegate) {
    super(delegate);
  }

  void unavailable(boolean value) {
    unavailable = value;
  }

  void afterAccountRead(Runnable action) {
    afterAccountRead.set(action);
  }

  void returnedAccount(UUID accountId) {
    returnedAccount = accountId;
  }

  void resetControls() {
    unavailable = false;
    returnedAccount = null;
    afterAccountRead.set(null);
  }

  /** Opens the real fixture connection unless this scenario disables the dependency. */
  @Override
  public Connection getConnection() throws SQLException {
    requireAvailable();
    return observe(super.getConnection());
  }

  /** Preserves the credentialed datasource contract for the same failure scenario. */
  @Override
  public Connection getConnection(String username, String password) throws SQLException {
    requireAvailable();
    return observe(super.getConnection(username, password));
  }

  private Connection observe(Connection connection) {
    return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
        new Class<?>[] {Connection.class}, (proxy, method, arguments) -> {
          final Object value = invoke(connection, method, arguments);
          if (value instanceof PreparedStatement statement && arguments != null
              && arguments.length > 0 && arguments[0] instanceof String sql
              && sql.contains("FROM query_service.account_summary_read_model")) {
            return observeAccount(statement);
          }
          return value;
        });
  }

  private PreparedStatement observeAccount(PreparedStatement statement) {
    return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
        new Class<?>[] {PreparedStatement.class}, (proxy, method, arguments) -> {
          final Object value = invoke(statement, method, arguments);
          if (value instanceof ResultSet rows) {
            final Runnable action = afterAccountRead.getAndSet(null);
            if (action != null) {
              action.run();
            }
            return returnedAccount == null ? rows : substituteAccount(rows, returnedAccount);
          }
          return value;
        });
  }

  private ResultSet substituteAccount(ResultSet rows, UUID accountId) {
    return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
        new Class<?>[] {ResultSet.class}, (proxy, method, arguments) -> {
          if ("getObject".equals(method.getName()) && arguments != null
              && arguments[0] instanceof Integer column
              && "account_id".equalsIgnoreCase(rows.getMetaData().getColumnLabel(column))) {
            return accountId;
          }
          return invoke(rows, method, arguments);
        });
  }

  private static Object invoke(Object delegate, Method method, Object[] arguments) throws Throwable {
    try {
      return method.invoke(delegate, arguments);
    } catch (InvocationTargetException failure) {
      throw failure.getCause();
    }
  }

  private void requireAvailable() throws SQLException {
    if (unavailable) {
      throw new SQLException("injected owned test database unavailable");
    }
  }
}
