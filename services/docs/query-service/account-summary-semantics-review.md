# Account Summary Semantics and Revision Review

Status: source-backed review; **Phase 1 reservation-projection scope approved; future authority
and external account-status revision contract unresolved**.
Inspected HEAD: `9e68995233f49319974b76f826a59f88f14538cb`, including the existing dirty worktree.
No runtime/replay/concurrency tests were executed for this review. See
[OD-19](security-decision-register.md) and the [security specification](security-external-access-spec.md).

## 1. What the current data means

The strongest supported description is: **the last successfully applied reservation lifecycle
event's selected fields, stored in a row keyed by account ID**. It is not a demonstrated
aggregation of all reservations, account cash balance, positions or available trading capacity.
“Last applied” does not yet mean latest authoritative account state or a total business order.

| Evidence | Source observation | Consequence |
| --- | --- | --- |
| [AccountLifecycleEvent](../../../proto/account_v2.proto), message fields | Carries reservation_id, order_id, account_id, lifecycle state, reserved amounts/quantity and reasons; no per-account revision. | Event shape is reservation-related, not a full account balance snapshot. |
| [AccountReservationApplicationService](../../account-service/src/main/java/com/simplematch/accountservice/reservation/AccountReservationApplicationService.java), emit | Copies one reservation's reservedNotional and remainingQuantity; emits UUIDv7 event metadata. | Numbers describe that reservation's current allocation, not a sum over the account. |
| Same service, reserve/release/applyFill | Transactional operations update reservation/authority state and call emit/outbox within the application transaction. | Source intent is atomic state-plus-outbox, not proof that Query publishes an aggregate balance. |
| [AccountReservation](../../account-service/src/main/java/com/simplematch/accountservice/authority/AccountReservation.java), version | Exposes optimistic reservation lifecycle version. emit does not include it in AccountLifecycleEvent. | Reservation-local revision is neither exposed account revision nor a global order across reservations. |
| [JdbcQueryAccountProjection](../../query-service/src/main/java/com/simplematch/queryservice/store/JdbcQueryAccountProjection.java), project | Upserts by account_id and overwrites state, amounts, reason and source_event_id; uses observedAtUnixMs for update timestamp. | Last applied event wins; no aggregation or per-account revision comparison. Timestamp is projection observation time. |

Illustrative implication, not executed evidence: if account X has reservation A with 100 units
remaining and B with 200, applying A then B leaves the displayed number 200, not 300. A subsequent
release/fill of A may replace the row with A's remaining allocation while B still exists. A
REJECTED event for one reservation does not establish that the entire account is rejected.
The current summary drops reservation/order identity even though the event carries them;
external wording/schema must address that context loss rather than imply account-wide state.

Account Authority remains the owner of limits/positions/reservations and their transaction
rules under [CONTEXT](../../../CONTEXT.md). Query is eventually consistent and must not be used
as the admission authority merely because endpoint names contain “account summary”. This
revision does not alter protobuf, domain terminology, account arithmetic or the existing DTO.

## 2. Ordering and transport: what is and is not established

The emit method writes destination `account.lifecycle` with `reservation.accountId()` as the
message key and aggregate reference `account_reservation` / reservation ID.
[AccountLifecycleOutbox.Destination](../../account-service/src/main/java/com/simplematch/accountservice/authority/AccountLifecycleOutbox.java)
uses no explicit partition in that constructor. [JdbcAccountOutboxRepository](../../account-service/src/main/java/com/simplematch/accountservice/store/JdbcAccountOutboxRepository.java)
persists these values. The [checked connector configuration](../../../deploy/compose/account-service-outbox-connector.json)
routes event key from message_key and supports an optional partition field.

This supports intended account-keyed partition routing. It does not prove the live connector,
partition count/partitioner stability, snapshot/restart ordering, or per-account business
serialization. A fixed key's partition order is transport order under its configuration;
contiguous Kafka offsets do not validate the semantics of events from distinct reservations.
Topic recreation, partition changes or replay can invalidate simplistic offset-as-version claims.

[QueryProjectionKafkaConsumer.onAccountLifecycle](../../query-service/src/main/java/com/simplematch/queryservice/kafka/QueryProjectionKafkaConsumer.java)
parses the event, invokes the transactional projection application service and acknowledges
after success. Unlike its Matching path, this Account path does not explicitly validate the
Kafka key against the payload account ID. That is a missing verification boundary to assess,
not permission to change the producer/consumer contract in this documentation task.

## 3. Idempotency, atomicity and replay

[JdbcQueryProjectionStore.projectAccountLifecycle](../../query-service/src/main/java/com/simplematch/queryservice/store/JdbcQueryProjectionStore.java)
validates canonical event UUID text, claims an inbox entry, checks contiguous source position,
updates the account projection and advances the checkpoint. The
[application method](../../query-service/src/main/java/com/simplematch/queryservice/runtime/QueryProjectionApplicationService.java)
owns the Spring transaction. [JdbcQueryProjectionState](../../query-service/src/main/java/com/simplematch/queryservice/store/JdbcQueryProjectionState.java)
compares duplicate payload hashes and source compatibility, rejects incompatible duplicate
identity/position and marks gaps. A missing initial checkpoint does not itself require offset zero.

These are meaningful implemented safeguards for duplicate delivery and partition progress.
They do not reject a new event with an older business revision: the account event has no such
revision, and the account upsert has no revision predicate. The source event UUID and observed
timestamp cannot fill that gap. Inspected
[JDBC tests](../../query-service/src/test/java/com/simplematch/queryservice/store/JdbcQueryProjectionStoreTest.java)
cover duplicate/source-position behavior and transactional rollback; their presence is not a
new test result and does not establish client-level monotonic reads.

Reset deletes projection/inbox/checkpoints, then Redis clear occurs after the PostgreSQL
transaction in [QueryProjectionRebuildService](../../query-service/src/main/java/com/simplematch/queryservice/runtime/QueryProjectionRebuildService.java).
The [runner](../../../scripts/end-to-end/query-service/run-certification.sh) reduces replicas
and uses the operator reset/replay seam. Neither this count change nor a listener stop proves
all HTTP readers, cache fillers, old replicas and other projection writers are drained.
Replay of the same ordered input should be evaluated for equivalent payloads; observation
timestamps/checkpoints and any future revision may differ. Define stable identity versus new
rebuild epoch before comparing versions across reset.

## 4. Required review and test cases before stronger claims

| Question | Required evidence / decision |
| --- | --- |
| Reservation activity or account aggregate? | Product owner approves truthful field meanings, context identifiers and intended client use; no balance claim from reserved_notional. |
| Per-account order? | Trace source transaction serialization, outbox/CDC ordering and fixed partition routing; test concurrent distinct reservations for one account. |
| Comparable revision? | Define scope, origin, atomic advancement, duplicate handling, persistence and replay/epoch semantics; do not equate reservation version to account version. |
| Atomic writes? | Real PostgreSQL failure injection proves inbox/data/checkpoint all commit or all roll back through the production Spring proxy. |
| Replay? | Duplicate, conflict, gap, older-business-event/new-offset, reset/replay, partition/key mismatch and interrupted replay cases. |
| Sequential reads? | Client completes revision N, then starts another request against another replica; never claim monotonicity without testing cache eviction/miss, database topology and restore. |
| External freshness? | Payload/checkpoint snapshot association and approved public fields; disclose projection lag separately from cache age. |

Recommendation: keep the truthful reservation-activity interpretation as the bounded baseline
for security repair, subject to product approval. If clients need a real account aggregate,
design a separate approved projection/event contract with aggregation, authority and replay
semantics; do not silently convert Phase 1 into Account domain redesign. A Query-local counter
could order applied projection snapshots but cannot prove that business facts were applied in
the right order. Strict client minimum-revision tokens may require epoch handling and an
unavailable response when the floor cannot be met. All are candidates, not approved mechanisms.

Until these gates pass, Account Summary stays PostgreSQL-only and there is no external promise
of authoritative balance, immediate available capacity or complete client-observed monotonicity.

## 5. Phase 0 user decision and domain priority assessment

On 2026-10-23 the user approved protecting the existing reservation-related projection in
Phase 1. Round 2 selects authoritative available trading capacity as the future objective and
excludes cash-balance engineering from this roadmap. This is
approval of the bounded semantic scope, not new domain implementation. The focused authority
audit below was refreshed at `8362a165e9ce05dc91247b55c9587866840a32ce`; no runtime tests ran.

### Existing authority versus the proposed product

[AccountLimitLedger](../../account-service/src/main/java/com/simplematch/accountservice/authority/AccountLimitLedger.java)
enforces `availableNotional = limitTotalNotional - reservedNotional - utilizedNotional`.
[AccountAuthorityTransitions](../../account-service/src/main/java/com/simplematch/accountservice/reservation/AccountAuthorityTransitions.java)
checks and updates the account/day limit for buys and account/symbol long inventory for sells.
Its sell availability is `longQuantity - reservedLongQuantity`. Reserve, partial fill and
release run under the existing application transaction, with their respective limit/position
revisions. These are existing admission-capacity authorities, not a proven cash ledger.

The current application has internal limit/position read methods; the protobuf service exposes
Reserve, not a new public capacity-query RPC. Current lifecycle publication carries one
reservation's state and amounts without limit availability, aggregate positions or revision.
Consequently, Query cannot reconstruct an authoritative account snapshot merely by renaming
its current fields. Reuse the separate limit/position/reservation roots under
[CONTEXT](../../../CONTEXT.md); do not create authority in Query or merge roots solely to
obtain one version counter.

The approved product focus is available trading capacity. Cash balances, withdrawal eligibility,
funding and settlement are excluded from this roadmap, not prerequisites or deferred required
phases. Existing sellable-inventory rules remain source context; the user's choice does not
automatically authorize a new external inventory API. Preserve the Account Authority capacity
contract rather than introducing Query-side cash checks or a second admission decision.

### Selected priority and remaining domain design

| Order | Work | Dependency and rationale |
| --- | --- | --- |
| Now | Available trading capacity is selected; complete remaining Query Phase 0 decisions. | Define capacity dimensions and source responsibility before selecting a schema/event design. |
| First implementation | Approved Phase 1 authentication, grants, ownership and PostgreSQL-only reads. | Do not delay the existing disclosure fix for a broader account redesign. No claim of authoritative balance follows. |
| Next domain slice | Validate existing capacity authority and design source-owned snapshots, revisions and publication. | Required before Query publishes new capacity semantics. Reuse existing roots; decide exact external fields separately. |
| Then account data product | Approved Query-owned capacity projection and external schema/freshness contract. | Authority values at a source revision remain lagging snapshots in Query. No cross-schema reads or Query dependency in admission. |
| After semantics/evidence | Optional Account Redis and strict client monotonic-read design. | Source ordering/revision/snapshot/replay precede cache CAS. Cache need and benefit still require Phase 2 gates. |

The authority/revision/publication design blocks the new external account-data meaning and
Account Cache, not Order/Executions security or an independently approved Gateway/JWT lab.
Discovery can proceed alongside security planning; implementation of any domain slice requires
its own approval and tickets. Production publication retains all transport/security gates.

The user's statement that successful trading consumes capacity must be expressed through the
existing reservation lifecycle. Admission reserves capacity so concurrent unfilled orders
cannot all spend the same allowance. A fill converts the corresponding reservation into utilized
capacity, with the existing execution-price adjustment; it must not subtract the same allowance
twice. Cancellation/release restores only unused reserved capacity, not completed utilization.
For example, with total 1,000 and no prior use, reserving 200 leaves available 800. A complete
fill using exactly 200 leaves reserved 0, utilized 200 and available 800, not 600. This is an
illustration of the existing equation, not a newly executed test or an approved arithmetic change.
Precise partial-fill, price-difference, release and trading-day rules require the separate domain
review before a new capacity projection is implemented. Query only reports authorized snapshots.

Even an authoritative capacity read cannot promise a later order will fit: another order may
reserve between read and submission. Account Authority's atomic reserve remains the admission
decision. A stronger guarantee would require a separately designed reservation/quote contract;
displaying a fresher number or bypassing Redis cannot provide it.

Minimum evidence for a future domain slice includes concurrent same-account reservations,
aggregate amounts, partial fills and price differences, release without reversing completed
fills, duplicates/ordering/replay, trading-day transitions, coherent limit/position snapshots,
atomic source state/publication and equivalent Query rebuild results. Existing per-root
versions do not prove one comparable account-wide revision. These are required future tests,
not passing results from this review.
