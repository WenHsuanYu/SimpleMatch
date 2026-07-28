# Field Typing Phase 2 Gates

This document records the first Phase 2 profiling pass for field typing convergence across `risk-service`,
`account-service`, and `persistence`.

## Scope

This pass is repo-local, not production-data profiling.

Inputs used in this pass:

- current runtime producers and ingress validators in the workspace
- Flyway schema definitions checked into the repo
- checked-in tests and fixtures that still model legacy identifier shapes

Anything marked `conditional` or `blocked` still needs a live PostgreSQL profile before a type-changing migration is
allowed.

## Gate Summary

| Field family                                                                        | Current source of truth                                                                                                     | Repo-local finding                                                                                                                                          | Gate        |
|-------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------|
| `risk_service.outbox.event_id`, `risk_submissions.outbox_event_id`                  | `risk-service` outbox runtime already writes UUID values                                                                    | defined as native `UUID` in the clean-install typed `V1`                                                                                                    | pass        |
| `request_id` in `risk-service` and `account-service`                                | `quickfix-gateway` now generates `command_id` with UUIDv7, and both services reject non-UUID `request_id` values at ingress | new gateway-originated traffic fits UUID; repo-local positive fixtures now align, but historical-data migration is not yet proven                           | conditional |
| `order_id` in `risk-service`, `account-service`, and `persistence`                  | `quickfix-gateway` derives `order_id` as `O-<ClOrdID>`                                                                      | not UUID-backed today; with `ClOrdID` capped at 64, derived `order_id` can reach 66 chars, so a direct `VARCHAR(64)` migration would truncate valid traffic | fail        |
| `reservation_id` in `account-service`                                               | currently aligned with `order_id`                                                                                           | inherits the same non-UUID and >64 risk as `order_id`                                                                                                       | fail        |
| `sender_comp_id`, `target_comp_id`, `cl_ord_id`, `orig_cl_ord_id` accepted traffic  | `quickfix-gateway` ingress now rejects values over 64 chars before WAL                                                      | accepted-path traffic is ready for a 64-char bound                                                                                                          | pass        |
| rejected `sender_comp_id`, `target_comp_id`, `cl_ord_id` rows in `risk_submissions` | typed `V1` compacts surrogates to 64-char SHA-256 digests and persists `business_key_surrogated` alongside the business key | rejected business-key rows now fit a future `VARCHAR(64)` shrink without colliding with accepted rows                                                       | pass        |
| rejected `orig_cl_ord_id` rows in `risk_submissions`                                | new writes compact to a 64-char digest; the clean reset has no pre-reset rows                                               | retained-data environments still require live profiling/backfill before the reset                                                                           | conditional |
| `persistence.inbox.event_id`                                                        | consumer runtime is not implemented in this workspace                                                                       | clean-install schema is native `UUID`; runtime proof remains a separate consumer-delivery concern                                                           | pass        |
| `persistence.orders.last_command_id`                                                | intended to come from upstream command ids                                                                                  | runtime writer not implemented in this workspace; likely UUID-backed once populated from gateway command ids, but not yet proven end to end                 | blocked     |

## Concrete Findings

### 1. `request_id` is close to UUID-ready, but historical data is still unproven

`quickfix-gateway` now emits UUIDv7 command ids, and both `risk-service` and `account-service` now reject non-UUID
`request_id` values at ingress. That is enough to treat new write-lane traffic as UUID-backed.

It is not enough to declare a full migration gate pass yet, because the repo-local pass still does not prove what may
already exist in a real PostgreSQL environment.

Implication:

- app-layer validation can now assume UUID format for new `request_id` traffic
- native `UUID` migration still needs a live DB query before the gate is opened

### 2. `order_id` is the main blocker for Phase 4

`quickfix-gateway` still derives `order_id` as `O-<ClOrdID>`.

That creates two separate migration blockers:

- `order_id` is not UUID-backed today
- if `ClOrdID` is allowed up to 64 chars, `order_id` can grow to 66 chars because of the `O-` prefix

Implication:

- do not plan `order_id -> UUID` until the gateway adopts an opaque internal order identity
- do not plan `order_id -> VARCHAR(64)` while the `O-<ClOrdID>` derivation still exists

The same logic applies to `account_reservations.reservation_id`, because it is currently aligned with `order_id`.

#### UUIDv7 suitability for `order_id`

UUIDv7 is a viable target for the canonical internal `order_id`, but it is not a safe drop-in replacement for the
current `orderIdFor(clOrdId)` behavior.

The current gateway flow still assumes local deterministic derivation in three places:

- the new-order path derives `walRecord.orderId()` before WAL append and reuses that same local value for the risk RPC,
  pending-new FIX ack, and `OrderSessionRegistry`
- the cancel path reconstructs `order_id` from `OrigClOrdID` via `orderIdFor(origClOrdId)` instead of resolving an
  opaque stored order identity
- rejected and duplicate replay paths build FIX responses from `walRecord.orderId()` even though `risk-service` already
  returns a canonical `order_id` in `RiskSubmissionResult`

Implication:

- UUIDv7 fits the project only if `order_id` becomes an opaque internal identity generated once before WAL append and
  then treated as canonical across retries and replay
- the gateway must also maintain a recoverable lookup from FIX business identity such as
  `(session, trading_day, cl_ord_id)` to canonical `order_id`, otherwise cancel routing cannot find the original order
  once `O-<ClOrdID>` goes away
- the submit path must stop assuming the local pre-risk `order_id` is authoritative, because duplicate business-key
  replays would otherwise ACK one `order_id` to FIX while `risk-service` persists another

### 3. Risk rejected-path business-key surrogates no longer block 64-char tightening

`risk-service` now stores oversized rejected business-key values as plain 64-char SHA-256 hex digests and persists
`business_key_surrogated` to distinguish hashed keys from accepted raw keys.

Implication:

- rejected `sender_comp_id`, `target_comp_id`, and `cl_ord_id` rows are now compatible with a future `VARCHAR(64)`
  shrink
- accepted rows and surrogated rejected rows can coexist safely even when the persisted `cl_ord_id` text matches
- `orig_cl_ord_id` is no longer a new-write blocker, but retained-data environments still need profiling before reset

### 4. `persistence` gates are still partially blocked by missing runtime

`persistence` already has the target schema surface, but this workspace does not yet contain the Kafka consumer /
projection writer that would populate:

- `inbox.event_id`
- `orders.last_command_id`

Implication:

- schema-only evidence is not enough to open a UUID migration gate
- final gate status for those columns must wait for either live DB profiling or the missing runtime landing in the repo

## Live DB Queries To Run Before Opening Final Gates

Run these against each real PostgreSQL environment before any type-changing migration is approved.

### `risk-service`

```sql
SELECT
  COUNT(*) AS total_rows,
  COUNT(*) FILTER (
    WHERE request_id !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
  ) AS non_uuid_request_id_rows,
  MAX(char_length(request_id)) AS max_request_id_len,
  MAX(char_length(order_id)) AS max_order_id_len,
  MAX(char_length(sender_comp_id)) AS max_sender_comp_id_len,
  MAX(char_length(target_comp_id)) AS max_target_comp_id_len,
  MAX(char_length(cl_ord_id)) AS max_cl_ord_id_len,
  MAX(char_length(orig_cl_ord_id)) AS max_orig_cl_ord_id_len
FROM risk_service.risk_submissions;
```

### `account-service`

```sql
SELECT
  COUNT(*) AS total_rows,
  COUNT(*) FILTER (
    WHERE request_id !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
  ) AS non_uuid_request_id_rows,
  MAX(char_length(request_id)) AS max_request_id_len,
  MAX(char_length(order_id)) AS max_order_id_len,
  MAX(char_length(reservation_id)) AS max_reservation_id_len
FROM account_service.account_reservations;
```

### `persistence`

```sql
SELECT
  COUNT(*) AS total_rows,
  COUNT(*) FILTER (
    WHERE event_id::text !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
  ) AS non_uuid_event_id_rows,
  MAX(char_length(event_id::text)) AS max_event_id_len
FROM persistence.inbox;

SELECT
  COUNT(*) AS total_rows,
  COUNT(*) FILTER (
    WHERE last_command_id IS NOT NULL
      AND last_command_id !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
  ) AS non_uuid_last_command_id_rows,
  MAX(char_length(order_id)) AS max_order_id_len,
  MAX(char_length(last_command_id)) AS max_last_command_id_len,
  MAX(char_length(sender_comp_id)) AS max_sender_comp_id_len,
  MAX(char_length(target_comp_id)) AS max_target_comp_id_len,
  MAX(char_length(cl_ord_id)) AS max_cl_ord_id_len
FROM persistence.orders;
```

## Recommended Next Decisions

1. Keep `request_id` on the UUID migration path, but require live DB confirmation before changing column types.
2. Keep `order_id` / `reservation_id` on an opaque UUIDv7 path only after gateway replaces `orderIdFor(clOrdId)` with
   canonical-id generation plus recoverable FIX-business-key lookup.
3. Profile or backfill any legacy `orig_cl_ord_id` rows before shrinking persisted FIX identity columns in
   `risk-service`.
4. Keep `orders.last_command_id` in the `blocked` state until a writer runtime or real DB sample is available;
   `persistence.inbox.event_id` is already UUID in typed V1.
