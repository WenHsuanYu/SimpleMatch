# Critical consumer end-to-end certification

This directory contains deployed-system certification for the three critical
`matching.events` consumers: Persistence, Account, and QuickFIX Gateway.

The current S5 Java/Jupiter `resting-buy` scenario passed its deployed
business and recovery checks on 2026-11-04.
See `docs/plans/shell-test-reduction-s5.md` for the run identity and scope.
The #162, #164, and #168 Shell/Ruby entry points are retired after
their last-caller review. The contracts and older PASS reports below record
historical behavior; they are not current execution instructions or proof of
the Java run. The R11 failure and R12 terminal-close Shell entry points
are also retired under the reduced S5 requirements. Java/Jupiter now owns
the selected R11–R15 integration. The remaining Shell modules also serve
the active Query certification until its P4 cutover.

The scripts exercise real Kubernetes workloads, Kafka, PostgreSQL, Kafka
Connect, and an external FIX session. They are not unit-test helpers and do not
call consumer implementation methods directly.

## Normal resting-buy acceptance contract (#162)

This section records the former Shell contract. The current selected
deployed scenario is implemented in Java/Jupiter.

The smallest normal scenario submits one BUY / LIMIT / DAY order through a real
FIX session in a fresh, dedicated local namespace. It uses an eligible instrument
from the deployed daily artifact, one board lot, and a newly funded test account.
There is no opposing order, fault injection, replay, or session-close scenario.

`ORDER_RESTED` means the unfilled remainder entered the order book; it is not a
trade or merely an admission acknowledgement. A partially filled order can also
produce this event. This scenario therefore separately requires zero fills and
the complete original quantity still resting.

Acceptance requires one coherent chain of evidence:

1. The production live collector makes Gateway open-eligible; the operator calls
   the real `/operations/open` endpoint. No synthetic observation is posted and
   the independent freshness monitor and automatic close remain enabled.
2. The FIX `ClOrdID` resolves to exactly one accepted Risk admission, reservation,
   and new-order outbox row. Its stable command/order identities correlate with
   the actual `matching.commands` Kafka record and its admitted business fields.
   The Pending New ACK uses Gateway's `O-<ClOrdID>` WAL identity, not Risk's
   derived order UUID; correlation requires the same account and `ClOrdID`.
3. Matching publishes `ORDER_RESTED` for that order, account, instrument, side,
   price, quantity, session and artifact. Its source input offset identifies an
   observed byte-identical command delivery, not just an assumed topic position.
4. Persistence durably records `RESTING`, zero cumulative quantity, full leaves,
   the exact Matching event identity and payload digest, and no order fills.
5. Account durably holds exactly one accepted reservation for the same order:
   full remaining quantity, zero filled quantity, reserved notional equal to
   quantity times limit price, the same amount reserved in the daily account
   limit, and zero utilized notional. Both consumers process the exact event
   once, without quarantine. Kafka may physically redeliver identical bytes;
   this must not reserve or apply a business effect twice.
   The fresh namespace must have no active critical-consumer quarantine at all:
   an unrelated quarantine is also a real Gateway safety blocker, not ignored.
6. A single result contains only the necessary identifiers and business facts.
   No token, Secret value, raw FIX message, protobuf payload, or complete account
   payload is evidence. A PASS is published only after owned test overrides and
   helper processes/Pods are restored or removed successfully.

All workload and verifier images must come from the same committed source-aligned
deployment evidence. This proves one normal integration baseline, not a filled
trade, infrastructure-failure resilience, or a new `full-local` aggregate run.

## Same-owner Gateway recovery acceptance contract (#164)

The retired `--gateway-recovery` mode extended the resting-buy scenario with one normal Gateway
Pod replacement. The original order, client session store, and approved trading
day remain the test identities throughout recovery.

Acceptance requires:

1. A different Pod UID resumes the same logical owner, owner-specific Service,
   node and PVC/PV identities. Samples during replacement show at most one
   running owner and the stable Service targets only that owner.
2. The JDBC FIX session creation time and retained message identities survive;
   sender/target sequence counters continue forward. The original inbound WAL
   record and the append-only `inbound.wal.recovery` journal prefix remain on the
   same claim. The recovered journal retains the accepted outcome, and startup
   recovery completes before Ready.
3. The client reconnects with its existing store and explicitly resends a prior
   ExecutionReport, preserving its sequence, ExecID and original sending time.
   Outbound ResendRequest and incoming retransmission wire timestamps establish
   the observed ordering after reconnect.
4. After an authenticated operator open from production live observations, the
   client resubmits the original order. Risk still has one accepted admission,
   the original command/order/reservation identities and one new-order outbox
   row; Persistence and Account retain the original resting-buy outcome. A
   TestRequest/Heartbeat pair follows the retry, and any intervening FIX rejection
   fails the test; identical retries do not require a second Pending New ACK.
5. One recovery deadline bounds replacement, reconnect and business recovery.
   The final Gateway is healthy and OPEN. The result records protocol recovery,
   business correctness and successful environment restoration separately.

The external test interfaces are FIX messages, the existing operator endpoint,
Kubernetes owner/storage observations and read-only durable SQL evidence. The
recovery verifier is tested with independent checked-in observations and a
reviewable result baseline. This scenario exercises one Gateway restart.

## Matching business recovery acceptance contract (#168)

The retired `--matching-recovery` mode selected one recovery integration test: one real resting
BUY, replacement of its observed Matching partition owner, a new FIX cancel
command for the original order, and controlled byte-identical redelivery of its
final cancellation event. It is mutually exclusive with `--gateway-recovery`.

The four business checkpoints are:

1. The existing #162 contract passes for the original order. Its command/event
   identity, partition, full resting quantity and Account reservation are retained.
2. The original Matching Pod is actually deleted before a different UID becomes
   Ready on the same node/PVC/PV. Runtime evidence completes replay through the
   original input offset. Persistence and Account retain the original business
   state. Only the owner selected by the observed command partition is targeted.
   Runtime `owner_id` must equal `<Pod name>:<replacement Pod UID>`; the stable
   ordinal alone cannot identify the new execution or reject the old owner.
3. After production live observations allow authenticated Gateway open, a new
   cancel command succeeds for the original order. It has a distinct command
   identity, one Risk admission/outbox, and a correlated `ORDER_CANCELLED` event.
   Persistence has one cancelled projection and no fills; Account releases the
   original reservation, reserved notional becomes zero, utilized notional remains
   zero, and available notional returns exactly to the isolated account limit.
4. The helper reads the original cancellation record and republishes its exact
   key/value bytes to the same partition. An independent read observes the new
   physical offset with the same event ID and payload digest. All critical
   consumers advance through that offset, but their inbox and business state
   remain singular; Account's business revision does not advance a second time.

Matching reconstruction can suppress previously completed event publication.
The report therefore distinguishes actual Matching recovery from deliberately
controlled Kafka redelivery; it never claims the restart itself caused the
redelivery. The post-recovery cancel is the new representative operation, not a
retry of the original admission. No opposing order or filled-trade claim is needed.

Local negative fixtures must reject missing interruption/replay evidence,
identity/content conflicts, incorrect or duplicate Persistence outcomes,
incorrect or repeated Account effects, a failed post-recovery operation despite
Ready, and missing actual redelivery evidence. The final runner must propagate
these failures and cannot publish PASS before successful restoration. Evidence
contains necessary identifiers/business facts only, never raw FIX/Kafka payloads
or credentials. The approved deployment trading day is retained unchanged.

The retired runner used a completed source-aligned deployment. It read
`trading_day` from its retained `run-context` and rejected a conflicting
request. The old invocation is no longer available.

The evidence directory must be new/empty. The operator needs no direct Kafka
payload editing or manual FIX sequence manipulation. The runner creates an
isolated funded account, controls the real FIX client and performs authenticated
open using live observations. It retains normal automatic-close behavior and
does not change the artifact's trading day. `matching-recovery-result.json` is
the business result; `verdict.json` is the final verdict after cleanup restores
the Gateway's original configuration and proves Ready. Ready after restoration
is not a promise that trading is still OPEN.

### Recorded Matching recovery verification

Source `34500c4` passed this scenario on 2026-10-09 (Asia/Taipei), retaining
the approved `2026-08-27` artifact. The fresh deployment evidence is
`out/certification/issue-168-deployment-20260827-r3/report.md`: all 26
applicable phases passed (18 executed, 8 reused). Its 25 explicit build and
Compose skips keep this prerequisite workflow `PARTIAL`, not full-local.
Source-only image preparation is recorded separately in
`out/certification/issue-168-images-20260827-r2/report.md`.

The final business verdict is
`out/certification/issue-168-matching-recovery-20260827-r3/verdict.json`, PASS
with successful restoration. The real resting order routed to partition 12.
The observed `matching-12` interruption and replacement changed the Pod UID
while preserving its node, PVC/PV and image identity. Runtime evidence bound
the replacement incarnation and completed replay through the original input.
Recovery took 34,802 ms within the 180,000 ms budget; the complete recovery,
cancel and controlled-redelivery observation took 46,650 ms.

The new FIX cancel succeeded for the original order. Persistence retained one
`CANCELLED` projection with zero fills; Account released its one reservation,
with reserved and utilized notional zero and available notional equal to the
isolated limit. The cancellation event at Kafka offset 1 was republished with
the same key/value bytes and independently observed at offset 2. Account,
Persistence and QuickFIX advanced through offset 2, each retained one inbox
entry for that event, and no quarantine was recorded. The authoritative
durable-state files before and after redelivery have an empty diff, including
Account's business revisions.

Gateway restoration proved Ready and removed the operations overrides, not
post-restoration OPEN. Preserve the original r1 business FAIL and the
interrupted r2 deployment as separate evidence, never upgrading their traces
to deployed PASS. The redelivery was deliberately injected, not proven to be
caused by the restart. Cross-node recovery, filled trades, production
exactly-once and full-local certification remain outside this result;
GitHub owns the current remote-delivery status.

## Structure and S5 cutover boundary

- `RestingBuyKubernetesTest` and the Java recovery helpers own the selected
  deployed R13-R15 FIX and business checks. The former Shell entry point,
  its Ruby verdicts, recovery helpers, and dedicated fixtures are retired.
  The Java test still reads the retained `sql/resting-buy-*.sql` files and
  `sql/matching-recovery-progress.sql`.
- `tests/shared-interface-contract.sh` keeps the distinct live Shell
  Gateway/FIX/Kafka adapter contracts. It no longer tests the retired
  recovery CLI or node-local WAL observer.
- `KubernetesCriticalDelivery` owns the selected live PostgreSQL fault and
  fail-closed delivery requirement. The old mixed-consumer-Pod outage runner
  was retired without a separate replacement deployment.
- `KubernetesTradingClose` owns the selected terminal `close-day` evidence.
  The legacy full-consumer-offset matrix is not a current S5 requirement.
- `lib/matching-status.sh` validates Matching runtime evidence and normalizes
  Kafka committed positions. It performs no Kubernetes or Kafka I/O.
- `lib/system-observation.sh` collects one bounded Gateway readiness
  observation. It parallelizes Matching reads and rejects an observation when
  Kafka positions change during collection.
- `lib/cluster-data.sh` contains Kubernetes, Kafka, PostgreSQL, and test-fixture
  access.
- `lib/test-interfaces.sh` contains external FIX, Kafka Connect, and Gateway
  operations adapters that the retained Query runner still uses.
- `lib/failure-recovery.sh` provides shared Risk/FIX/Kafka observation helpers
  to Query. The old R11 fault injection and restoration functions are removed.
- `scripts/end-to-end/query-service/lib/order-projection.sh` owns the
  retained Query projection polling functions from the old close helper.
- `tests/` contains contracts for shared interfaces and observations.

The former failure and close runner commands are no longer available.
Use the Java/Jupiter `resting-buy` scenario for the retained S5 behavior.

## Current S5 verification and former runner scope

The selected deployed scenario is `RestingBuyKubernetesTest`, selected with
`-PkubernetesScenario=resting-buy` in `:tools:kubernetes-tests:kubernetesTest`.
Its canonical PASS and source identity are recorded in the S5 plan. That PASS
does not establish all historic runner assertions or full S5 acceptance.

The retired Shell recovery runner used one 180-second recovery budget,
node-local WAL observation, Gateway owner sampling, and a separate cleanup
verdict. Its dedicated WAL prefix, sampler, output-baseline, and CLI-option
tests were removed because they no longer protect an active interface.
Gateway WAL/journal integrity has independent Java tests; the current Jupiter
flow checks the selected retained claim and durable business effects.
R11/R12 Shell runners and their runner-specific contracts are retired.
The independent Gateway policy tests and Java Kafka Close Barrier observer
remain available. Query still has active Shell helper consumers until P4.

### Recorded local verification

Source `8920af4` passed this scenario on 2026-10-08 (Asia/Taipei), using the
approved `2026-08-27` artifact. Retained evidence is:

- `out/certification/issue-164-deployment-20260827-r6/report.md`: all 36
  applicable phases passed (21 executed, 15 reused); 15 Compose phases were
  explicitly skipped, so the parent remains `PARTIAL`.
- `out/certification/issue-164-gateway-recovery-20260827-r3/verdict.json`:
  final PASS with successful restoration, protocol recovery and business
  recovery recorded separately. Recovery took 37,742 ms within the 180,000 ms
  budget. The 36 owner samples observed a maximum of one active Gateway owner.

The replacement retained the owner-specific Service, node and PVC/PV identities.
The same JDBC session creation time and all four prior stored messages survived;
incoming sequence advanced from 5 to 10 and outgoing sequence from 5 to 8.
The original WAL record digest and accepted journal remained unchanged. The
retained client reconnected, requested and received ExecutionReport sequence 3
with the original ExecID/time and `PossDup`, then retried the same order body
with client sequence 8 instead of 3. Risk still had one admission and one outbox
row; Persistence remained `RESTING` with zero fills and 1,000 shares left;
Account retained one reservation for 24,300 with zero utilization.

Gateway was healthy and `OPEN` before restoration; afterward its actual
readiness and removed overrides passed, with `postRestorationOpenProven: false`.
This is one observed same-owner Gateway recovery, not Matching fault injection,
cross-node HA, a filled trade, or `full-local` certification. GitHub owns remote
delivery status; this local report does not establish remote CI success.

Use a clean committed tree, an empty result directory, and the canonical context.
Select an approved artifact for the explicit trading day before an expensive
build: this example intentionally uses the historical `2026-08-27` artifact,
not today's date. Keep that date consistent in deployment and observation; do
not relabel an old artifact or change the host clock. The runner refuses an
unrelated namespace, mismatched run ID, source revision or verifier image.
It also requires a completed deployment report and matching PASS results for
the trading prerequisites, including the fleet gate. A FAILED deployment is
not eligible even if its surviving Pods are Ready; the generated
`baseline/deployment-prerequisites.json` records this bounded prerequisite check.
It temporarily enables authenticated
operator HTTP access while preserving automatic close; it requires the existing
production live collector to make the gate open-eligible. Run before the configured
automatic close time. It does not relax readiness to compensate for a slow host.

The result is `verdict.json`, with the chain's command/order/event IDs, Kafka
positions, reserved amount, source revision and restoration result. Raw FIX stores
and logs live only in a private temporary directory and are removed on exit.
The deployment namespace remains owned by the deployment runner: the normal test
removes only its helper Pod, port-forwards, FIX process and Gateway overrides.

## Observation rules

A Gateway readiness observation is accepted only when its evidence has clear
sources:

- Matching `READY`/`OPEN` and `observedAt` come from
  `runtime-metrics.json`, including its `updated_at_epoch_ms` source timestamp.
- Matching durable progress comes from Kafka consumer-group `CURRENT-OFFSET`.
- Kafka log-end positions come from `kafka-get-offsets.sh`.
- Critical-consumer progress comes from the durable PostgreSQL progress tables;
  `last_processed_offset + 1` converts the last processed record offset to the
  next Kafka position.
- Risk and critical-consumer process availability comes from current Kubernetes
  workload status.

The collector does not claim a cross-system atomic snapshot. Instead it reads
Kafka positions before and after the other observations. If either topic moves,
the attempt is discarded and retried. Matching Pods are sampled in parallel,
and their Pod UID is checked before and after reading runtime metrics so a Pod
replacement cannot combine evidence from two processes.

## Failure-scenario FIX submission boundary

In the failure scenario, the prepared FIX client logs on before the
freshness-sensitive admission window and waits for a release file. The runner
then supplies three fresh observations,
opens Gateway admission, and immediately releases the client. The normal Gateway
stale-observation monitor remains enabled throughout the run.

The Risk outbox connector is intentionally paused before the order is released.
This is a test barrier, not a health claim: it keeps the accepted Risk command
durable without allowing `matching.commands` to advance before Matching is
stopped.

## Failure evidence

Both PASS and FAIL runs write `verdict.json` after the evidence directory has
been initialized. A failure verdict records the stage and reason; cleanup also
records whether environment restoration failed.
