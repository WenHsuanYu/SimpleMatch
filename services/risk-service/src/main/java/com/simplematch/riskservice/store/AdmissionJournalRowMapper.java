package com.simplematch.riskservice.store;

import com.simplematch.marketreference.ArtifactIdentity;
import com.simplematch.riskservice.admission.AdmissionCommand;
import com.simplematch.riskservice.admission.AdmissionDecision;
import com.simplematch.riskservice.admission.AdmissionDeliveryRoute;
import com.simplematch.riskservice.admission.AdmissionFailure;
import com.simplematch.riskservice.admission.AdmissionFixIdentity;
import com.simplematch.riskservice.admission.AdmissionIdentity;
import com.simplematch.riskservice.admission.AdmissionJournalEntry;
import com.simplematch.riskservice.admission.AdmissionLifecycle;
import com.simplematch.riskservice.admission.AdmissionOrder;
import com.simplematch.riskservice.admission.AdmissionRoutingReference;
import com.simplematch.riskservice.admission.AdmissionState;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;

/** Rehydrates semantic admission values from the durable journal row. */
final class AdmissionJournalRowMapper {
  static final RowMapper<AdmissionJournalEntry> MAPPER = (resultSet, row) -> fromRow(resultSet);

  private AdmissionJournalRowMapper() {}

  private static AdmissionJournalEntry fromRow(ResultSet resultSet) throws SQLException {
    final AdmissionCommand command = command(resultSet);
    final AdmissionDecision decision = decision(resultSet, command);
    return new AdmissionJournalEntry(
        command,
        new AdmissionDeliveryRoute(
            resultSet.getObject("routing_partition", Integer.class),
            artifactIdentity(resultSet),
            resultSet.getString("routing_algorithm_version")),
        new AdmissionLifecycle(
            decision,
            resultSet.getLong("version"),
            resultSet.getLong("created_at_unix_ms"),
            resultSet.getLong("updated_at_unix_ms")));
  }

  private static ArtifactIdentity artifactIdentity(ResultSet resultSet) throws SQLException {
    final LocalDate tradingDay = resultSet.getObject("artifact_trading_day", LocalDate.class);
    final String checksum = resultSet.getString("artifact_content_sha256");
    if (tradingDay == null && checksum == null) {
      return null;
    }
    if (tradingDay == null || checksum == null) {
      throw new IllegalArgumentException("admission artifact identity is incomplete");
    }
    return new ArtifactIdentity(tradingDay, checksum);
  }

  private static AdmissionCommand command(ResultSet resultSet) throws SQLException {
    return new AdmissionCommand(
        new AdmissionIdentity(
            new AdmissionIdentity.CommandId(resultSet.getObject("command_id", UUID.class)),
            new AdmissionIdentity.OrderId(resultSet.getObject("order_id", UUID.class)),
            new AdmissionIdentity.AccountId(resultSet.getObject("account_id", UUID.class))),
        new AdmissionOrder(
            new AdmissionOrder.Instrument(
                new AdmissionOrder.Symbol(resultSet.getString("symbol")),
                new AdmissionOrder.VenueMic(resultSet.getString("venue_mic"))),
            new AdmissionOrder.Characteristics(
                new AdmissionOrder.SideCode(resultSet.getString("side")),
                new AdmissionOrder.Quantity(resultSet.getLong("quantity")),
                new AdmissionOrder.LimitPriceUnits(
                    resultSet.getObject("limit_price_units", Long.class)),
                new AdmissionOrder.OrderTypeCode(resultSet.getString("order_type")),
                new AdmissionOrder.TimeInForceCode(resultSet.getString("tif"))),
            resultSet.getObject("trading_day", LocalDate.class)),
        new AdmissionFixIdentity(
            new AdmissionFixIdentity.SenderCompId(resultSet.getString("sender_comp_id")),
            new AdmissionFixIdentity.TargetCompId(resultSet.getString("target_comp_id")),
            new AdmissionFixIdentity.ClOrdId(resultSet.getString("cl_ord_id"))),
        new AdmissionRoutingReference(
            new AdmissionRoutingReference.RoutingSnapshotId(
                resultSet.getObject("routing_snapshot_id", UUID.class))));
  }

  private static AdmissionDecision decision(ResultSet resultSet, AdmissionCommand command)
      throws SQLException {
    final AdmissionState state = AdmissionState.valueOf(resultSet.getString("state"));
    return switch (state) {
      case PENDING -> new AdmissionDecision.Pending();
      case ACCEPTED -> acceptedDecision(command, resultSet.getObject("reservation_id", UUID.class));
      case REJECTED ->
          new AdmissionDecision.Rejected(
              new AdmissionFailure(
                  new AdmissionFailure.ReasonCode(resultSet.getString("reason_code")),
                  new AdmissionFailure.Detail(resultSet.getString("reason_detail"))));
    };
  }

  private static AdmissionDecision acceptedDecision(AdmissionCommand command, UUID reservationId) {
    if (command.order().isCancellation()) {
      if (reservationId != null) {
        throw new IllegalArgumentException("accepted cancel admission must not have a reservation");
      }
      return new AdmissionDecision.AcceptedCancel();
    }
    if (reservationId == null) {
      throw new IllegalArgumentException("accepted new admission requires a reservation");
    }
    return new AdmissionDecision.AcceptedNew(reservationId);
  }
}
