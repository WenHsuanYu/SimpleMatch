# quickfix-gateway

Java + Spring Boot + QuickFix/J based FIX 4.4 gateway.

This service is the target external FIX entrypoint in the current SimpleMatch architecture. It sits
in front of the event-driven trading pipeline, while `matching-engine` remains the native C++
matching core.

The cross-service admission, recovery, identity, and error-message rules are defined by
[Consistency, Recovery, Identity, and Error Boundaries](../docs/platform/consistency-recovery-identity-and-errors.md).
This README describes the Gateway-specific implementation of that policy.

## Role In The System

`quickfix-gateway` is responsible for:

- accepting FIX 4.4 sessions through QuickFix/J;
- validating and normalizing inbound FIX order flow;
- validating canonical UUID `Account(1)` before durable order admission;
- appending valid commands to the local command WAL before Risk submission;
- durably recording recovery state in a sidecar journal;
- mapping durable WAL commands directly to typed Risk v2 new/cancel commands;
- synchronously submitting those commands to the production Risk v2 admission service;
- reconciling indeterminate Risk outcomes against Risk's durable admission journal;
- returning terminal FIX acceptance or rejection only when the authoritative Risk outcome is known;
- keeping unknown new-order outcomes non-terminal rather than fabricating a business rejection; and
- consuming final `matching.events` through a durable delivery ledger and mapping them to outbound
  FIX responses.

The former `orders.commands` compatibility publisher is retired. The Gateway has no runtime switch
that can re-enable that Kafka publication path.

## Live trading admission observation

When `simplematch.quickfix-gateway.live-observation.enabled=true`, the Gateway gathers the Risk
daily identity, all 15 Matching runtime documents, Kafka topic ends and consumer-group commits,
and the three critical consumers' quarantine and pending-age status. Kafka commits are the durable
progress authority; a consumer's process-local offset cache may be empty after restart. One HTTP
request has a one-second timeout and the complete sample has a three-second deadline. A failed or
partial sample is never published, so the existing five-second stale-status monitor pauses an open
Gateway. The collector never issues an operator `open` command: three qualifying complete samples
make opening eligible, and an authenticated operator must still explicitly request it.
An expired reporting gap discards the earlier qualifying streak: recovery needs three newly
reported healthy samples, not three status queries, and never opens admission automatically.

Trading identity is compared between Risk and Matching. Kafka and critical-consumer observations
contain no trading identity and never inherit Risk's identity: they report their own availability,
topology, progress, freshness, and quarantine facts as applicable. Consumer event validation and
conflict handling remain required; full trading identity attestation for these components is outside
the admission scope.

The live adapter is disabled by default in generic local runs and enabled in production and the
Kubernetes Gateway ConfigMap. See [configuration](../../docs/reference/runtime-configuration.md) for endpoint and timing
overrides. The [completion specification](../../docs/verification/gateway-admission-verification.md#deployed-admission-smoke)
records the source-specific local deployed PRE_OPEN-to-open smoke and its limits. The later
stale-recovery streak fix has separate local controller regression evidence, not a rerun of that
deployment smoke. Unit tests or rendered-manifest checks alone are not deployment evidence.

The runtime QuickFIX session config defaults to `config/quickfix/acceptor.cfg`, which uses
`../../config/quickfix/fix-spec/FIX44.xml` as the shared FIX dictionary.

## FIX authentication and account authorization

FIX listeners require TLS with client certificates and explicit PKCS12 key and trust stores.
The local configuration reads `config/quickfix/tls/server.p12` and `clients.p12`; Kubernetes mounts
the required `quickfix-gateway-fix-tls` Secret's `server.p12` and `clients.p12` at
`/etc/simplematch/fix-tls`. Provision these identities and trust only approved client issuers.
The lifecycle rejects plaintext, disabled client authentication, absent store settings, and raw
unknown-session logging before constructing the acceptor. QuickFIX's normal log destination is
wrapped to redact credential-bearing incoming, outgoing, event, warning, and error entries.

Each counterparty must also supply `Username(553)` and `Password(554)` on every Logon. Provision
`simplematch.quickfix-gateway.fix-security` in an external Spring configuration file or the existing
`quickfix-gateway-secrets` Secret's `application.yaml`:

```yaml
simplematch:
  quickfix-gateway:
    fix-security:
      key-store-password: ${FIX_TLS_KEY_PASSWORD}
      trust-store-password: ${FIX_TLS_TRUST_PASSWORD}
      counterparties:
        - session-id: "FIX.4.4:SIMPLEMATCH->CLIENT"
          username: ${FIX_CLIENT_USERNAME}
          password: ${FIX_CLIENT_PASSWORD}
          account-ids:
            - "0194a8f0-7c77-7b38-9e2d-2a5fdd0f7c13"
```

Use each session's complete acceptor-side `SessionID`, including configured SubIDs, LocationIDs,
and qualifier. Accounts form an immutable UUID allowlist; equivalent UUID spellings use the same
canonical account identity as the WAL. Missing configuration denies all Logons. The adapter checks
the authenticated session and account before invoking order handling, so unauthorized new orders
cannot append WAL or call Risk. Cancellations check the retained order's account against the current
allowlist, including when the client omits Account. Malformed accounts retain the existing rejection.
Logout and failed reauthentication revoke access; a later Logon requires fresh credentials.
Policy or credential rotation requires a gateway restart, which disconnects existing sessions.

The `simplematch-fix-ingress` policy selects gateway pods by name and denies ingress except
TCP 5001 from same-namespace pods labelled `simplematch.io/fix-client: "true"` and management
TCP 8080 from SimpleMatch pods. Restrict who may set the client label. This ingress policy preserves
Kubernetes API bootstrap and dependency egress. Enforcing it requires a NetworkPolicy-capable CNI.

Historical WAL records cannot retroactively prove that their originating peer authenticated.
Before upgrading a previously exposed installation, audit and quarantine untrusted pre-fix WAL
records; the v1 cancellation replay restriction described below remains in effect.

Regression acceptance covers anonymous/invalid Logon, session aliases, logout/failed reconnect,
unauthorized accounts with canonical UUID variants, unchanged authorized multi-account admission,
malformed-account rejection, and credential redaction. Run `:services:quickfix-gateway:test`,
`:services:quickfix-gateway:certificationTest`, `staticAnalysis`, and
`bash scripts/test-kubernetes-overlays.sh`. Live certification clients additionally require
`SIMPLEMATCH_LIVE_FIX_USERNAME`, `SIMPLEMATCH_LIVE_FIX_PASSWORD`, `SIMPLEMATCH_LIVE_FIX_KEY_STORE`,
`SIMPLEMATCH_LIVE_FIX_KEY_STORE_PASSWORD`, `SIMPLEMATCH_LIVE_FIX_TRUST_STORE`, and
`SIMPLEMATCH_LIVE_FIX_TRUST_STORE_PASSWORD`; stores must be PKCS12 and server names must match the
certificate. In an isolated deployment, verify that absent/untrusted client certificates fail and
an unlabelled pod cannot connect to TCP 5001. Unit and rendered-manifest checks do not establish
runtime TLS or NetworkPolicy enforcement.

Session-aware deployment baseline:

- Stateful deployments should use stable owner ids that match pod identity, such as
  `quickfix-gateway-0` and `quickfix-gateway-1`.
- Client session routing should target the owner service name, for example
  `quickfix-gateway-owner-0:5001`.
- Startup recovery completes before the QuickFIX acceptor starts, and readiness becomes healthy only
  after recovery succeeds.
- The default QuickFIX acceptor configuration uses continuity defaults
  (`ResetOnLogon/Logout/Disconnect=N`) for same-owner restart semantics.
- Standby promotion, fencing, and route transfer remain follow-up work documented in
  `docs/plans/quickfix-gateway-session-scale-out.md`.

## Durable Admission And Recovery

For a valid inbound command, the write-before-submit ordering is:

```text
1. force command WAL
2. force recovery sidecar UNKNOWN
3. map the WAL record to the typed Risk v2 command
4. start the Risk v2 RPC
```

The command WAL records what normalized command was received. The sidecar records what the Gateway
knows about Risk ownership or outcome. Sidecar states are `UNKNOWN`, `PENDING`, `ACCEPTED`, and
`REJECTED`.

The WAL is a Gateway-owned persistence model, not a serialized Risk protobuf. Its current local
`schemaVersion = v2` extends the stable flat JSON format with the complete authenticated FIX
`SessionID`; this Gateway-local schema version is independent of the Risk wire contract. Live submission and
restart resubmission both use the same `RiskCommandMapper` and `RiskCommandSubmitter` to produce
typed v2 `NewOrderCommand` or `CancelOrderCommand` messages directly from the durable WAL record.
The reader retains v1 compatibility, but v1 records cannot restore cancellation authority and v1
cancellations are never resubmitted because their original authenticated session was not durable.

Startup recovery is state-aware rather than a blind WAL replay:

- a WAL record with no sidecar state means the process stopped before Risk submission started;
  startup writes `UNKNOWN` and performs the first submission;
- `UNKNOWN` is reconciled with `GetAdmissionOutcome(command_id)` before any retry decision;
- `PENDING` proves Risk durably owns the command and is never retry permission;
- `ACCEPTED` and `REJECTED` are terminal and are not resubmitted;
- authoritative `NOT_FOUND` permits resubmission only after local `UNKNOWN`, reusing the original
  `command_id`; and
- local `PENDING` combined with authoritative `NOT_FOUND` is an ownership contradiction and prevents
  successful startup; and
- cancellation replay requires a v2 authenticated session plus a matching, previously accepted
  new-order record from the same session.

`simplematch.quickfix-gateway.replay-enabled` controls this startup recovery phase and defaults to
`true`.

## FIX Outcome Behavior

The Gateway distinguishes authoritative business outcomes from transport uncertainty.

- Risk `ACCEPTED`: the Gateway can emit the normal accepted/PendingNew FIX response and register the
  accepted order context.
- Risk `REJECTED`: the Gateway can emit the terminal business rejection with a stable client-facing
  reason.
- Transport timeout, connection loss, exhausted retry, or breaker-open conditions: the Gateway uses
  internal `UNKNOWN`; these conditions do not prove Risk rejected the command.

For a new order whose Risk outcome is unknown, the current FIX response remains non-terminal
`ExecutionReport(PendingNew)` with:

```text
SYSTEM_ERROR: order outcome is pending confirmation; no client action is required
```

For a cancel whose outcome is unknown, the Gateway does not fabricate an `OrderCancelReject`.
Background reconciliation does not currently guarantee a later FIX follow-up message after an
unknown outcome becomes terminal.

Operator logs retain the detailed transport or dependency reason needed for diagnosis; FIX client
text intentionally does not expose internal RPC, service, breaker, retry, database, or stack-trace
details.

## Identity

The external FIX/WAL order id remains deterministic `OrderID = O-<ClOrdID>`.

The Gateway accepts `ClOrdID` values up to 64 characters, so the durable FIX delivery ledger stores
the prefixed external `OrderID` in a `VARCHAR(66)` column. This keeps the WAL, FIX mapper, and
versioned Flyway schema aligned at the maximum supported identity length.

The complete QuickFIX/J `SessionID` is stored as `TEXT` in the delivery ledger because it combines
multiple components and can exceed 256 characters. Before creating the acceptor, loaded session
identities (including values inherited from `[DEFAULT]`) are checked against the existing JDBC
component capacities: `BeginString` has 8 characters and each sender/target CompID, SubID,
LocationID and session qualifier has 64. Optional components may be empty. These capacities describe
the Gateway-owned schema; they do not impose a 256-character limit on the combined identity.

At the Risk v2 boundary, `RiskOrderIdentityDeriver` derives a separate opaque internal order UUID
from FIX session identity, the deployment-owned trading day, and the original client `ClOrdID`.
That configured day must identify the same session as the approved Market Reference artifact; it is
not inferred from the wall clock or WAL timestamp. New and cancel commands for the same FIX order
on the same trading day therefore map to the same internal Risk order identity without changing the
FIX-facing `OrderID` contract.

Canonical account identity is owned by Account Service. FIX `Account(1)` carries the canonical UUID;
the Gateway validates and preserves it. The Gateway does not derive an account UUID from a human-
readable alias such as `ACC-1`.

## Kafka Boundary

QuickFIX Gateway no longer publishes accepted FIX commands to `orders.commands`. The authoritative
asynchronous order path begins after Risk durable admission:

```text
Gateway WAL
  -> RiskCommandMapper
  -> v2 NewOrderCommand / CancelOrderCommand
  -> Risk v2 admission journal
  -> Risk terminal journal + transactional outbox
  -> CDC / Kafka matching.commands
  -> matching-engine
```

The production Gateway-to-Risk admission path does not construct the retired v1 `OrderCommand`
message.

## Implemented Behavior

The Java gateway includes:

- QuickFix/J acceptor lifecycle wiring and session logging;
- capability configuration through Spring Config Data;
- `NewOrderSingle (35=D)` and cancel request ingestion;
- gateway-local validation before WAL append;
- command WAL plus append-only recovery sidecar;
- direct WAL-to-v2 Risk command mapping;
- v2 Risk submission with bounded retry and breaker protection;
- Risk admission reconciliation during startup recovery;
- canonical Account UUID validation before durable admission;
- session-owned cancellation resolution with authoritative account and routing context before WAL;
- deterministic FIX `OrderID = O-<ClOrdID>` plus a separate internal Risk order UUID;
- final `matching.events` consumption with durable FIX delivery planning and dispatch; and
- `/healthz`, `/healthz/liveness`, `/readyz`, and `/metrics` management endpoints.

Two intentional Java-specific differences from the historical C++ gateway baseline are documented
and tested:

- WAL is stored as one-line JSON records rather than the older plain-text format.
- Baseline `PendingNew` uses `ExecID = E-<recordId>` so outbound acknowledgements stay traceable to
  persisted WAL state.

## Configuration

Configuration is resolved only through Spring Config Data. Use canonical Spring properties or
relaxed environment names such as `SIMPLEMATCH_QUICKFIX_GATEWAY_OWNER_ID`; JSON config discovery and
legacy `SIMPLEMATCH_FIX_*` aliases are not supported.

Important Spring properties:

- `simplematch.quickfix-gateway.acceptor-enabled`: start or skip the QuickFix/J acceptor;
- `simplematch.quickfix-gateway.data-plane-enabled`: enable or skip final `matching.events`
  consumption and delivery wiring;
- `simplematch.quickfix-gateway.replay-enabled`: run state-aware WAL recovery before readiness;
  default is on;
- `simplematch.quickfix-gateway.owner-id`: stable logical gateway owner identity;
- `simplematch.quickfix-gateway.ingress.trading-day`: required deployment-owned trading day used by
  new orders, cancels, replay, and accepted-order session identity; production-like Kubernetes
  wiring reads it from the immutable `matching-session-config` shared with Risk and Matching;
- `simplematch.grpc.targets.risk-service`: gRPC target used for Risk submission and reconciliation;
  and
- `simplematch.quickfix-gateway.risk-client.*`: deadline, bounded retry, and breaker settings for
  Risk calls.

There is no `compatibility-publish-enabled` setting. Supplying a legacy value cannot restore the
retired publisher because production wiring no longer creates a Kafka command publisher.

Default paths from `QuickFixGatewayFileProperties` include:

- QuickFIX config: `config/quickfix/acceptor.cfg`;
- WAL path: `data/quickfix/wal/inbound.wal`; and
- the recovery sidecar is maintained beside the configured WAL by Gateway recovery wiring.

## Run

Start the service normally:

```bash
SIMPLEMATCH_QUICKFIX_GATEWAY_INGRESS_TRADING_DAY=YYYY-MM-DD \
  ./gradlew :services:quickfix-gateway:bootRun
```

Start it without opening the FIX acceptor socket:

```bash
SIMPLEMATCH_QUICKFIX_GATEWAY_INGRESS_TRADING_DAY=YYYY-MM-DD \
  ./gradlew :services:quickfix-gateway:bootRun --args='--spring.main.web-application-type=none --simplematch.quickfix-gateway.acceptor-enabled=false'
```

Start it with the acceptor and matching-execution consume path disabled for a dry run:

```bash
SIMPLEMATCH_QUICKFIX_GATEWAY_INGRESS_TRADING_DAY=YYYY-MM-DD \
  ./gradlew :services:quickfix-gateway:bootRun --args='--spring.main.web-application-type=none --simplematch.quickfix-gateway.acceptor-enabled=false --simplematch.quickfix-gateway.data-plane-enabled=false'
```

Disabling `replay-enabled` disables startup WAL recovery and should not be treated as a normal
production recovery mode.

## Verification

Run the full module test suite:

```bash
./gradlew :services:quickfix-gateway:test
```

Run the dedicated QuickFIX/J certification-style simulator evidence:

```bash
./gradlew :services:quickfix-gateway:certificationTest
```

The verification suite covers the normal FIX path together with durable WAL ordering, direct v2
command mapping, state-aware recovery, identity validation, and the separation between authoritative
Risk outcomes and transport uncertainty. Current CI evidence belongs in execution/certification
material rather than this README.

## Scope Boundary

This README describes the current Java target gateway in this repository.

- It does not claim venue-specific external certification.
- It does not claim a post-reconciliation FIX follow-up protocol that is not implemented.
- It does not expose `orders.commands` as a supported publication or recovery path.
- It is the target runtime architecture for the FIX boundary in this repo.
