package com.simplematch.quickfixgateway.store;

import com.simplematch.quickfixgateway.fix.FinalFixDeliveryIdentity;
import com.simplematch.quickfixgateway.fix.FinalFixDeliveryIntent;
import com.simplematch.quickfixgateway.fix.FinalFixDeliveryRecipient;
import com.simplematch.quickfixgateway.fix.FinalFixDeliveryReport;
import com.simplematch.quickfixgateway.fix.FixOrderSnapshot;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import quickfix.SessionID;

/** Maps one durable delivery-intent row back to the Gateway's FIX delivery value objects. */
final class FinalFixDeliveryIntentRowMapper implements RowMapper<FinalFixDeliveryIntent> {
  @Override
  public FinalFixDeliveryIntent mapRow(ResultSet resultSet, int rowNumber) throws SQLException {
    return new FinalFixDeliveryIntent(
        new FinalFixDeliveryIdentity(
            HexFormat.of().formatHex(resultSet.getBytes("delivery_id")),
            HexFormat.of().formatHex(resultSet.getBytes("event_id")),
            resultSet.getInt("delivery_index")),
        new FinalFixDeliveryRecipient(
            resultSet.getObject("recipient_order_id", UUID.class),
            new SessionID(resultSet.getString("session_id")),
            order(resultSet)),
        new FinalFixDeliveryReport(
            resultSet.getString("exec_id"),
            resultSet.getString("exec_type").charAt(0),
            resultSet.getString("ord_status").charAt(0),
            resultSet.getLong("last_quantity"),
            resultSet.getLong("last_price_units"),
            resultSet.getLong("cumulative_quantity"),
            resultSet.getLong("leaves_quantity"),
            resultSet.getLong("average_price_units"),
            resultSet.getString("text")),
        resultSet.getInt("source_partition"),
        resultSet.getLong("source_offset"),
        resultSet.getLong("created_at_unix_ms"));
  }

  private FixOrderSnapshot order(ResultSet resultSet) throws SQLException {
    return new FixOrderSnapshot(
        new FixOrderSnapshot.OrderId(resultSet.getString("order_id")),
        new FixOrderSnapshot.ClientOrderId(resultSet.getString("client_order_id")),
        new FixOrderSnapshot.Symbol(resultSet.getString("symbol")),
        FinalFixDeliverySqlValues.side(resultSet.getInt("side")),
        new FixOrderSnapshot.Quantity(resultSet.getString("order_quantity")));
  }
}
