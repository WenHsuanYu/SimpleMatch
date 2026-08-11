# Account Reservation v2 Live Verification

This runbook is the operational evidence supplement for [#139](https://github.com/WenHsuanYu/SimpleMatch/issues/139).
It proves the Risk-to-Account reservation seam without claiming that local tests are production
certification.

## What #139 must prove

The same typed v2 reservation command must preserve command, order, account, reservation, venue,
quantity, and fixed-point price identity across Risk, gRPC, Account Authority, PostgreSQL, and the
Account outbox. The following outcomes are distinct:

- an Account business rejection is a rejected reservation outcome;
- an equivalent retry returns the original reservation outcome and identity;
- changed reservation facts produce a stable conflict;
- validation failures remain validation failures;
- deadline and unavailable failures leave Risk admission recoverable; and
- unexpected Account failures remain internal dependency failures.

Risk writes `PENDING` before the Account RPC. The RPC is outside the Risk database transaction. A
later local transaction finalizes the Risk journal and outbox, and scheduled recovery retries only
old pending rows with the original command identity.

## Repository evidence

Run these commands from the repository root:

```bash
bash scripts/check-account-reservation-v2-cutover.sh

GRADLE_USER_HOME=/tmp/simplematch-gradle-home ./gradlew --no-daemon \
  :services:account-service:test \
  :services:risk-service:test

GRADLE_USER_HOME=/tmp/simplematch-gradle-home ./gradlew --no-daemon -q staticAnalysis
```

The caller guard intentionally permits the Account v1 server to remain until #119. It rejects v1
client construction in every other production service, requires Risk's v2 stub and Account's v2
server wiring, and checks the secure Kubernetes target contract.

## What you need before a live run

For the direct Risk-to-Account RPC proof, you need:

1. A disposable or staging Kubernetes namespace.
2. TLS-enabled PostgreSQL with the Account and Risk schemas migrated by their service-owned Flyway
   Jobs.
3. `account-service` and `risk-service` Deployments reachable by the service DNS names in
   `simplematch-platform-config`.
4. A shared gRPC CA plus server/client certificates whose SANs cover `account-service` and
   `risk-service`.
5. A provisioned Account limit for the test UUID and a test trading day.

Kafka and Debezium are not required to call the Account `Reserve` RPC directly. They are required
for the full Risk outbox/CDC production path and for proving that a finalized admission reaches the
Matching command topic. Do not treat a direct gRPC success as a complete Phase 1 release gate.

## Safe execution sequence

### 1. Validate before applying anything

Replace only environment-owned placeholders in a disposable overlay. Do not commit credentials,
private keys, DSNs, registry values, or real endpoint CIDRs.

```bash
bash scripts/test-kubernetes-overlays.sh
bash scripts/check-account-reservation-v2-cutover.sh
kubectl config current-context
kubectl auth can-i get secrets -n "$SIMPLEMATCH_NAMESPACE"
```

Provision Secrets through your organization’s approved secret manager or `kubectl` workflow. The
repository expects the keys documented in [`deploy/k8s/README.md`](../deploy/k8s/README.md): service
PostgreSQL DSNs, gRPC TLS material, PostgreSQL CA material, and—when Kafka is enabled—Kafka client
trust and SASL material.

### 2. Apply configuration and migrations

Apply ConfigMaps and Secrets first, then run the Account and Risk Flyway Jobs, and only then roll the
Deployments. Keep application startup migration disabled. Confirm that both services are Ready and
that the Account gRPC port is reachable before running a reservation.

```bash
kubectl -n "$SIMPLEMATCH_NAMESPACE" apply -k deploy/k8s/overlays/staging
kubectl -n "$SIMPLEMATCH_NAMESPACE" wait --for=condition=complete \
  job/account-service-flyway job/risk-service-flyway --timeout=10m
kubectl -n "$SIMPLEMATCH_NAMESPACE" rollout status deployment/account-service --timeout=10m
kubectl -n "$SIMPLEMATCH_NAMESPACE" rollout status deployment/risk-service --timeout=10m
```

The checked-in image names and external CIDRs are placeholders. A real promotion must replace them
with approved immutable image digests and reachable endpoints before apply.

### 3. Run the functional scenarios

Use a short-lived `grpcurl` client or an equivalent generated client inside the namespace so the
client can use the mounted CA, certificate, and key. For the direct Account seam, call:

```text
simplematch.account.v2.AccountReservationService/Reserve
```

Use one UUID set and record it in the evidence bundle. The request must contain:

- `metadata.schema_version = "v2"`, UUID `event_id`, positive `created_at_unix_ms`,
  `source_service`, and UUID `correlation_id`;
- the same UUID for `reservation_id` and `order_id`;
- the canonical Account UUID;
- `instrument.symbol` plus `instrument.venue_mic`;
- positive whole-share quantity; and
- `notional.units = quantity.shares * limit_price.units`.

Run the scenarios in this order:

1. Submit a valid reservation; record the typed response and Account rows/outbox identity.
2. Submit the exact same command again; require the same outcome and one reservation row.
3. Change the venue or quantity while keeping the command identity; require a typed conflict and no
   second reservation.
4. Send malformed metadata or identity; require `INVALID_ARGUMENT` and no Account mutation.
5. Stop or isolate Account, submit a new Risk admission, and verify that Risk keeps the journal
   `PENDING` rather than recording a business rejection.
6. Restore Account, wait for the bounded pending-recovery pass, and query Risk’s
   `GetAdmissionOutcome(command_id)`; require one terminal outcome and one Account reservation.

Capture command IDs, reservation IDs, Account journal/outbox identities, Risk state transitions,
timestamps, pod image digests, Kubernetes context/namespace, and the operator’s rollback decision.
Never place credentials or complete account payloads in the evidence bundle.

## Kafka and Debezium extension

After the direct seam passes, extend the run only if the environment has the production Kafka profile
and a reviewed Kafka Connect/Debezium deployment:

1. Verify the Kafka topic, TLS/SASL client properties, replication, and ISR profile.
2. Verify the connector worker can read only the owning Account/Risk outbox table.
3. Create or update the connector through the approved Kafka Connect/Strimzi control plane.
4. Confirm the Risk outbox record is delivered once to the expected topic and that a replay does not
   create a second Account reservation.
5. Exercise connector restart and PostgreSQL outage while retaining the outbox and Risk journal.

This repository currently contains connector configuration contracts, not a complete retained
KafkaConnect/KafkaConnector deployment object. Treat that deployment as an environment/platform
work item and do not mark #139 complete from a ConfigMap render alone.

## Learning path

- [Kubernetes production environment](https://kubernetes.io/docs/setup/production-environment/)
- [kind quick start](https://kind.sigs.k8s.io/docs/user/quick-start/) for a disposable cluster
- [Apache Kafka quick start](https://kafka.apache.org/quickstart/)
- [Debezium installation](https://debezium.io/documentation/reference/stable/install.html)
- [Debezium on Kubernetes with Strimzi](https://debezium.io/documentation/reference/3.5/operations/kubernetes.html)

The kind and single-broker Kafka paths are learning or integration environments. They cannot satisfy
the production replication, node, TLS, outage, or recovery evidence required by the Phase 1 release.
