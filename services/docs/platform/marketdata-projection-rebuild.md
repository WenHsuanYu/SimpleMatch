# Market-data projection replay

`market-data-projection` is rebuildable; its PostgreSQL rows and Redis materialization are not
trading authority. A replay must reset the projection-owned state and the `matching.events`
consumer group as one operator procedure. Do not delete Matching, Persistence, Account, or
QuickFIX state to repair this read model.

The reset HTTP adapter is disabled by default. Staging and production enable it only with the
`market-data-projection-secrets.rebuild_operator_token` Secret key. It is protected by the
`X-SimpleMatch-Projection-Token` header and only clears the projection-owned tables; it does not
change Kafka offsets.

For a controlled replay:

1. Call the reset endpoint while the `market-data-projection` Pod is running. The authenticated
   endpoint first stops its own `matching.events` listener, then clears the projection-owned
   PostgreSQL tables and Redis namespace. Keep `marketdata-streamer` isolated; it has a separate
   group.

   ```bash
   curl --fail-with-body --request POST \
     --header "X-SimpleMatch-Projection-Token: ${PROJECTION_REBUILD_TOKEN}" \
     "${PROJECTION_URL}/internal/market-data/rebuild"
   ```

2. Verify the projection listener is stopped and then reset only the projection group:

   The Kafka owner
   supplies the TLS/SASL command-properties file; do not print it:

   ```bash
   kafka-consumer-groups.sh \
     --bootstrap-server "${KAFKA_BOOTSTRAP_SERVER}" \
     --command-config /secure/kafka/matching-client.properties \
     --group market-data-projection \
     --topic matching.events \
     --reset-offsets --to-earliest --execute
   ```

3. Restore or restart the Deployment and wait for readiness. Verify the projection catches up without
   changing any critical consumer group, then verify that `marketdata.events` contains complete
   snapshots and that Redis converges from PostgreSQL.

The endpoint response proves only that the local reset transaction completed. Kafka offset reset,
source retention, replay boundary, and final convergence remain operator-owned evidence.
