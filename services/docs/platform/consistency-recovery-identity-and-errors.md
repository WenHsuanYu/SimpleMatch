# Consistency, Recovery, Identity, and Error Boundaries

This is the canonical cross-service policy for synchronous admission, recovery, identity ownership,
and error-message audiences in SimpleMatch. Service documentation may add implementation detail but
must not weaken or contradict these rules.

The policy describes the current target/runtime contract established by the durable Risk admission,
reconciliation, state-aware Gateway recovery, and canonical Account identity work. Transaction
mechanics remain governed by
[`docs/cross-cutting-transaction-and-consistency-policy.md`](../../../docs/cross-cutting-transaction-and-consistency-policy.md).

## Core invariants

1. A missing remote response is not a business rejection.
2. Only an authoritative owner may declare its business outcome or canonical identity.
3. A retry is permitted only when the caller can prove that retry is safe for the same idempotency
   identity.
4. `PENDING` means the authoritative owner has durable ownership; it is never retry permission.
5. Client-facing messages and operator diagnostics are different contracts and must be produced
   separately.
6. Durable state and required outbox events commit atomically inside the owning service.
7. Retired compatibility publication must not be reintroduced as a second admission or recovery
   path.

## Order ingress and admission

The current FIX new-order path is:

```text
FIX client
  -> QuickFIX Gateway validation
  -> force command WAL
  -> force recovery sidecar UNKNOWN
  -> Risk v2 SubmitNewOrder
  -> Risk admission journal PENDING
  -> Account reservation RPC
  -> Risk terminal journal + outbox transaction
  -> Risk ACCEPTED or REJECTED response
  -> FIX response
```

Client-shape validation that can be decided without authoritative service state happens before the
command enters durable admission. This includes required FIX fields and canonical UUID shape for
`Account(1)`. Invalid client input is not recorded as transport uncertainty.

The Gateway command WAL records what normalized command was durably received. The recovery sidecar
records what the Gateway currently knows about Risk ownership or outcome. They have different
responsibilities and neither replaces the Risk admission journal.

Risk writes `PENDING` before the remote Account call. The Account call remains outside the Risk
database transaction. Risk later commits the terminal admission state and the required outbox event
atomically before returning the terminal result.

## Outcome semantics

The Gateway distinguishes three submission outcomes:

| Gateway outcome | Meaning | Permitted client interpretation |
|---|---|---|
| `ACCEPTED` | Risk explicitly confirmed durable acceptance | The system accepted ownership of the order |
| `REJECTED` | Risk explicitly returned an authoritative business rejection | The order was rejected for the reported business reason |
| `UNKNOWN` | The Gateway cannot prove the authoritative Risk result | The system is responsible for resolving an indeterminate outcome |

A deadline, connection loss, breaker-open condition, or other transport failure produces `UNKNOWN`,
not `REJECTED`.

For a new order with an unknown Risk result, the current FIX behavior is non-terminal
`ExecutionReport(PendingNew)` with the stable client-safe text:

```text
SYSTEM_ERROR: order outcome is pending confirmation; no client action is required
```

For a cancel with an unknown Risk result, the Gateway does not fabricate a terminal
`OrderCancelReject`. Background reconciliation does not currently guarantee an additional FIX
follow-up message after the outcome later becomes terminal; that is a separate protocol capability.

## Risk reconciliation contract

Risk is authoritative for durable admission state. `GetAdmissionOutcome(command_id)` returns:

| Risk state | Meaning |
|---|---|
| `NOT_FOUND` | Risk has no durable admission row for the command |
| `PENDING` | Risk durably owns the command and its admission saga is not terminal |
| `ACCEPTED` | Risk durably accepted the command |
| `REJECTED` | Risk durably rejected the command |

`NOT_FOUND` is a fact, not automatic retry permission. The caller combines it with its own durable
recovery state before deciding whether a first submission or resubmission is safe.

## Gateway WAL and recovery sidecar

The write-before-submit ordering is a recovery invariant:

```text
1. force command WAL
2. force sidecar UNKNOWN
3. start the Risk RPC
```

The repository is operated as a clean-install system with no historical production WAL migration
requirement across this recovery design. Because Risk is never called before step 2, a WAL record
with no sidecar entry means the process stopped after the command became durable and before Risk
submission started. Startup may persist `UNKNOWN` and perform the first submission using the same
`command_id`.

Sidecar states are:

- `UNKNOWN`: Risk may have seen the command, but the Gateway cannot prove its outcome.
- `PENDING`: reconciliation proved that Risk durably owns the command and has not finalized it.
- `ACCEPTED`: reconciliation or the live response proved terminal acceptance.
- `REJECTED`: reconciliation or the live response proved terminal rejection.

Startup recovery follows these rules:

| Local state | Startup action |
|---|---|
| no sidecar state | persist `UNKNOWN`, then perform the first Risk submission |
| `UNKNOWN` | reconcile with Risk before deciding whether to resubmit |
| `PENDING` | reconcile with Risk; never resubmit from local `PENDING` |
| `ACCEPTED` | terminal; do not submit again |
| `REJECTED` | terminal; do not submit again |

After reconciliation:

- Risk `ACCEPTED`: persist local `ACCEPTED`; do not resubmit.
- Risk `REJECTED`: persist local `REJECTED`; do not resubmit.
- Risk `PENDING`: persist local `PENDING`; leave completion to Risk recovery.
- Risk `NOT_FOUND` after local `UNKNOWN`: resubmit with the original `command_id`.
- Risk `NOT_FOUND` after local `PENDING`: fail startup because local and authoritative ownership
  disagree.
- An unresolved reconciliation failure prevents the Gateway from claiming readiness.

This design turns restart recovery into state reconciliation rather than blind WAL replay.

## Crash windows

The important crash windows are intentional and testable:

- Crash after WAL force but before sidecar `UNKNOWN`: Risk was not called; startup performs the first
  submission after restoring `UNKNOWN`.
- Crash after sidecar `UNKNOWN` but before or during the Risk RPC: startup reconciles before any
  retry decision.
- Crash after Risk commits but before the Gateway records the terminal sidecar state: startup
  recovers the authoritative Risk result through reconciliation.
- Terminal sidecar state: startup does not repeat the Risk submission.

A failure to persist a local terminal sidecar state after Risk has already returned a terminal
answer does not rewrite the authoritative Risk decision into a client failure. Operators diagnose
the local recovery-journal fault; later recovery can ask Risk again.

## Identity ownership

### Command identity

`command_id` identifies one admission attempt and is the idempotency and reconciliation identity.
Retries and recovery reuse the original value; they do not manufacture a new command identity.

### Client-facing and internal order identity

FIX-facing and WAL-facing `OrderID(37)` remains `O-<ClOrdID>`. The Gateway does not expose the Risk
internal identifier as a replacement FIX order id.

At the Risk v2 boundary, the Gateway derives a deterministic opaque UUID for internal order identity
from FIX session identity, the Asia/Taipei trading day, and the original client `ClOrdID`. New and
cancel commands for the same FIX order on the same trading day therefore map to the same Risk order
identity, while a later trading day may reuse the client `ClOrdID` without collision.

### Canonical Account identity

Account Service owns canonical account identity. The canonical `account_id` is UUID-backed from the
Account domain through service boundaries and persistence.

The current contract is:

```text
FIX Account(1) canonical UUID
  -> Gateway validates and preserves it
  -> Risk v2 preserves it
  -> Account gRPC validates it
  -> Account domain AccountId(UUID)
  -> account_service UUID columns
```

Gateway must not hash, alias, or otherwise derive an account UUID from a value such as `ACC-1`.
Risk must not translate the identifier. If a future product requires human-readable external
account codes, Account authority must own the explicit resolution from that external code to the
canonical UUID.

## Durable event path and retired compatibility publication

For accepted Risk admission, the durable cross-service publication path is the Risk journal and
transactional outbox followed by the configured CDC/Kafka delivery path on `orders.validated`.
QuickFIX Gateway no longer exposes a runtime path or configuration switch that can publish the
former `orders.commands` compatibility topic.

The v1 `OrderCommand` message may remain inside Gateway WAL/Risk adapter code while that internal
carrier is still useful. Retaining a wire type is not permission to restore a second Kafka ingress
path. If a future integration needs a new command stream, it requires an explicit architecture and
delivery contract rather than re-enabling the retired compatibility publisher.

Recovery always reconciles against Risk's durable admission journal; Kafka publication success is
not an admission or recovery source of truth.

## Error-message audience policy

SimpleMatch separates internal diagnostics from external client messages across every service and
protocol boundary.

### Operator-facing diagnostics

Operator, SRE, and developer diagnostics should be precise enough to identify and correlate the
fault. Where appropriate and safe, structured logs include:

- service and operation;
- trace/correlation identity;
- `command_id`, internal order identity, and execution identity;
- canonical account identity where operational policy permits it;
- FIX session identity for FIX-boundary incidents;
- reason code and detailed reason text;
- dependency, deadline, retry, and breaker context; and
- the exception or stack trace in internal logs.

Logs must still follow credential, secret, privacy, and retention policy. Diagnostic detail does not
justify logging passwords, tokens, secret configuration, or prohibited payload data.

### Client-facing messages

External FIX, REST, gRPC, or future client protocols expose only information the client needs to
understand responsibility and choose a safe action.

- Client input errors identify the invalid client-controlled field or business constraint when that
  information is safe and useful.
- Authoritative business rejections may expose a stable client-facing business reason.
- Infrastructure and transport failures use a stable system-side error message. They do not expose
  internal service names, RPC topology, database details, stack traces, breaker state, retry
  internals, or implementation-specific reason text.
- When the client must not retry or otherwise act, the message says so explicitly.
- Protocol status fields are the machine-readable state. Free-form text is explanatory and must not
  become the only representation of an outcome.

Internal service-to-service reason codes and details are not automatically client-safe. Every
external adapter explicitly maps internal state into an external protocol contract.

## Verification requirements

Changes to these boundaries require tests that prove the relevant invariant rather than only the
happy path. Depending on the change, verification includes:

- WAL is durable before sidecar `UNKNOWN`, and `UNKNOWN` is durable before Risk submission;
- crash/restart recovery for each sidecar state;
- `PENDING` is never treated as retry permission;
- `UNKNOWN + NOT_FOUND` reuses the original `command_id`;
- terminal outcomes do not produce duplicate Account reservations or Risk outbox events;
- new and cancel commands preserve the intended internal order identity relationship;
- malformed account identity is rejected before durable order admission;
- canonical account UUID is preserved through Gateway, Risk, Account gRPC, domain state, and JDBC;
- operator diagnostics retain actionable internal detail; and
- external protocol messages do not leak internal service, RPC, breaker, database, or stack-trace
  details.

See [Testing](testing.md) for the verification-layer model and
[Troubleshooting](troubleshooting.md) for first-line operational diagnosis.
