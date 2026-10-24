package com.simplematch.queryservice.security;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/** Controls database failures and concurrent replies without replacing Query application modules. */
final class QueryReadTestDataSource extends DelegatingDataSource {
  private final ReadControl account = new ReadControl();
  private final ReadControl order = new ReadControl();
  private final ReadControl execution = new ReadControl();
  private volatile boolean unavailable;

  QueryReadTestDataSource(DataSource delegate) {
    super(delegate);
  }

  void unavailable(boolean value) {
    unavailable = value;
  }

  void afterAccountRead(Runnable action) {
    account.afterRead.set(action);
  }

  void afterOrderRead(Runnable action) {
    order.afterRead.set(action);
  }

  void afterExecutionsRead(Runnable action) {
    execution.afterRead.set(action);
  }

  /** Replaces one JDBC execution reply while retaining the other valid rows in the collection. */
  void returnedExecutionIdentity(UUID executionId, UUID orderId, UUID accountId) {
    execution.selectedExecution = executionId;
    execution.identities = Map.of("order_id", orderId, "account_id", accountId);
  }

  void returnedAccount(UUID accountId) {
    account.identities = Map.of("account_id", accountId);
  }

  void returnedOrderIdentity(UUID orderId, UUID accountId) {
    order.identities = Map.of("order_id", orderId, "account_id", accountId);
  }

  void resetControls() {
    unavailable = false;
    account.reset();
    order.reset();
    execution.reset();
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
              && arguments.length > 0 && arguments[0] instanceof String sql) {
            if (sql.contains("FROM query_service.account_summary_read_model")) {
              return observeRows(statement, account);
            }
            if (sql.contains("FROM query_service.order_read_model")) {
              return observeRows(statement, order);
            }
            if (sql.contains("FROM query_service.execution_read_model")) {
              return observeRows(statement, execution);
            }
          }
          return value;
        });
  }

  private PreparedStatement observeRows(PreparedStatement statement, ReadControl control) {
    return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
        new Class<?>[] {PreparedStatement.class}, (proxy, method, arguments) -> {
          final Object value = invoke(statement, method, arguments);
          if (value instanceof ResultSet rows) {
            final Runnable action = control.afterRead.getAndSet(null);
            if (action != null) {
              action.run();
            }
            return control.identities.isEmpty() ? rows : substituteIdentities(rows, control);
          }
          return value;
        });
  }

  private ResultSet substituteIdentities(ResultSet rows, ReadControl control) {
    return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
        new Class<?>[] {ResultSet.class}, (proxy, method, arguments) -> {
          if ("getObject".equals(method.getName()) && arguments != null
              && arguments[0] instanceof Integer column) {
            if (control.selectedExecution != null
                && !control.selectedExecution.equals(rows.getObject("execution_id", UUID.class))) {
              return invoke(rows, method, arguments);
            }
            final UUID replacement = control.identities.get(
                rows.getMetaData().getColumnLabel(column).toLowerCase(Locale.ROOT));
            if (replacement != null) {
              return replacement;
            }
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

  private static final class ReadControl {
    private final AtomicReference<Runnable> afterRead = new AtomicReference<>();
    private volatile Map<String, UUID> identities = Map.of();
    private volatile UUID selectedExecution;

    private void reset() {
      afterRead.set(null);
      identities = Map.of();
      selectedExecution = null;
    }
  }
}
