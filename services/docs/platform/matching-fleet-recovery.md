# Matching fleet recovery

This runbook applies to one fixed Phase 1 Matching partition. It preserves the rule that
`matching-N` owns only partition `N`; no operator action may reroute an instrument, add an intraday
order book, or let another ordinal consume that partition.

## Preconditions

The operator first pauses new orders through the Gateway operational boundary. Cancels remain
durably accepted according to the Gateway state policy, but they wait in `matching.commands` until
the affected partition returns. Confirm the approved artifact identity and trading-session ID before
starting recovery.

The target cluster must support the StatefulSet pod-index label and use a CSI StorageClass that
enforces `ReadWriteOncePod`. A matching pod is Ready only after it has the correct ordinal, PVC,
artifact, Lease permit, replay state, and acceptable Kafka catch-up position.

## Normal restart

1. Record the affected ordinal, Pod UID, Lease name, artifact identity, trading-session ID, and
   latest committed Kafka offset.
2. Use a normal deletion or rollout action for `matching-N`; preserve its PVC and its named Lease.
   Do not change the StatefulSet replica count or partition mapping.
3. Wait for the replacement Pod with the same ordinal to attach its own `matching-baseline-N` PVC.
4. Wait for the old Lease to expire and for the replacement to acquire it with its own Pod UID,
   partition, and the approved trading session. The native permit is initially denied and cannot
   poll, replay, match, publish, or report Ready before this step.
5. The replacement replays from the retained Open Barrier through the committed boundary, then
   catches up normally. Its readiness command succeeds only after the permit and recovery gate are
   valid.
6. Verify all fifteen Matching statuses again. Recovery never reopens the market automatically; the
   operator performs the Gateway `open` command only after its complete readiness check succeeds.

## Lease uncertainty

The Lease adapter renews every two seconds. If it cannot confirm renewal, it reports uncertainty
immediately. After five continuous seconds it self-fences the local runtime: direct Kafka polling,
replay, the single-writer core, publication, and readiness stop. Pending commands and cancellations
remain retained in Kafka. A late renewal cannot resume a self-fenced process; it must be restarted
and acquire a fresh permit.

## Prohibited force recovery

Do not use `kubectl delete pod matching-N --force --grace-period=0` as a normal restart operation.
Do not delete the ordinal PVC or its Lease to accelerate replacement. Those actions can allow an old
process to continue while a replacement begins, defeating cooperative fencing. If a node is
unreachable, use the cluster provider's node-isolation and control-plane recovery procedure, keep
new orders paused, and let the documented Lease expiry and replacement sequence complete.
