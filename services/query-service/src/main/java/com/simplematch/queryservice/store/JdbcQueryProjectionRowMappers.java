package com.simplematch.queryservice.store;

import com.simplematch.queryservice.model.QueryAccountSummaryView;
import com.simplematch.queryservice.model.QueryExecutionView;
import com.simplematch.queryservice.model.QueryFreshness;
import com.simplematch.queryservice.model.QueryMarketReferenceView;
import com.simplematch.queryservice.model.QueryOrderView;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Maps JDBC rows into immutable query read models. */
final class JdbcQueryProjectionRowMappers {
  private JdbcQueryProjectionRowMappers() {}

  static QueryOrderView order(ResultSet resultSet, int ignored) throws SQLException {
    return new QueryOrderView(
        resultSet.getString(1),
        resultSet.getString(2),
        resultSet.getString(3),
        resultSet.getString(4),
        resultSet.getString(5),
        resultSet.getString(6),
        resultSet.getLong(7),
        resultSet.getString(8),
        resultSet.getLong(9));
  }

  static QueryExecutionView execution(ResultSet resultSet, int ignored) throws SQLException {
    return new QueryExecutionView(
        resultSet.getString(1),
        resultSet.getString(2),
        resultSet.getString(3),
        resultSet.getString(4),
        resultSet.getString(5),
        resultSet.getString(6),
        resultSet.getLong(7),
        resultSet.getLong(8),
        resultSet.getLong(9),
        resultSet.getLong(10),
        resultSet.getLong(11),
        resultSet.getString(12),
        resultSet.getLong(13));
  }

  static QueryAccountSummaryView account(ResultSet resultSet, int ignored) throws SQLException {
    return new QueryAccountSummaryView(
        resultSet.getString(1),
        resultSet.getString(2),
        resultSet.getLong(3),
        resultSet.getLong(4),
        resultSet.getString(5),
        resultSet.getString(6),
        resultSet.getString(7),
        resultSet.getLong(8));
  }

  static QueryMarketReferenceView marketReference(ResultSet resultSet, int ignored)
      throws SQLException {
    return new QueryMarketReferenceView(
        resultSet.getDate(1).toLocalDate(),
        resultSet.getString(2),
        resultSet.getString(3),
        resultSet.getString(4),
        resultSet.getString(5),
        numberAsLong(resultSet.getObject(6)),
        numberAsLong(resultSet.getObject(7)),
        numberAsLong(resultSet.getObject(8)),
        numberAsInteger(resultSet.getObject(9)),
        resultSet.getLong(10));
  }

  static QueryFreshness.PartitionFreshness freshness(ResultSet resultSet, int ignored)
      throws SQLException {
    return new QueryFreshness.PartitionFreshness(
        resultSet.getString(1),
        resultSet.getInt(2),
        resultSet.getLong(3),
        resultSet.getString(4),
        resultSet.getLong(5));
  }

  private static Long numberAsLong(Object value) {
    return value == null ? null : ((Number) value).longValue();
  }

  private static Integer numberAsInteger(Object value) {
    return value == null ? null : ((Number) value).intValue();
  }
}
