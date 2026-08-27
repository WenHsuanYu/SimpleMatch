# Runtime market-data projection certification

This specification defines the repository-owned completion gate for GitHub issue #133. It verifies
the existing `market-data-projection` and `marketdata-streamer` modules through their deployed public
interfaces. It does not create another production module and does not treat the offline Market
Reference builder as runtime market data.

## Capability and ownership

The runtime market-data projection is a non-critical, rebuildable read model. It consumes retained
`matching.events`, stores projection-owned PostgreSQL state, repairs a disposable Redis snapshot,
publishes versioned complete snapshots to `marketdata.events`, and exposes those snapshots through
the `marketdata-streamer` gRPC subscription interface.

Matching remains the authority for order books and trades. Persistence remains the authority for
permanent trade facts, while Account Authority and QuickFIX retain their own critical effects. A
projection, Redis, publisher, streamer, or subscriber failure must not pause admission, stop
Matching, or change any critical consumer's durable position.

The existing interfaces are the certification seams:

- `matching.events` is the retained input interface.
- The projection-owned PostgreSQL schema and authenticated replay reset are the durable rebuild
  interface.
- Redis is a disposable cache adapter repaired from PostgreSQL.
- `marketdata.events` is the versioned snapshot publication interface.
- `MarketDataService.SubscribeMarketDataSnapshots` is the public subscriber interface.

The application interfaces and their focused tests remain the fast behavioral gate. Deployment
certification must not call implementation classes, private methods, or repository adapters
directly.

## Required scenarios

### Live projection and streaming

Submit a run-owned order through the retained trading ingress and identify its exact final Matching
Event. Observe a complete market-data snapshot for the same venue and symbol through the public
gRPC subscription. The evidence must correlate the source event identity, instrument sequence,
source partition and offset, and snapshot freshness. The subscriber must receive a snapshot before
any later update for that instrument.

### Redis outage and repair

Make only the run-owned Redis dependency unavailable while Matching and the critical consumers stay
healthy. A new Matching Event must remain durable in the projection-owned PostgreSQL state even
when Redis cannot be updated. After Redis recovers, the cache repair path must recreate the latest
snapshot without replaying or mutating critical business state.

### Replay and deterministic rebuild

Record a baseline snapshot and all critical consumer positions. Use the authenticated projection
reset interface, reset only the `market-data-projection` Kafka group to the retained replay start,
and restart the projection. The rebuilt snapshot must equal the baseline business view and restart
its projection-owned sequence deterministically. Critical consumer positions and permanent
business state must remain unchanged.

### Gap and slow-subscriber policy

Focused tests at the application and gRPC subscription interfaces must prove that a source-position
gap marks the projection for resynchronization and that a subscriber exceeding its bounded queue is
terminated without blocking publication to other subscribers. The deployment run records these
focused test results; it does not manufacture an invalid record in a shared authoritative topic.

## Evidence and verdict

The certification writes machine-readable evidence under a run-owned directory and publishes
`verdict.json` only after environment restoration. A passing verdict includes:

- repository source revision, namespace, trading day, and image identities;
- correlated Matching Event and market-data snapshot identities and positions;
- PostgreSQL and Redis state before outage, during outage, after repair, and after replay;
- projection, streamer, Matching, admission, and all critical-consumer health observations;
- critical consumer positions before and after projection-only failure and replay;
- focused gap and slow-subscriber test results; and
- cleanup and restoration status.

Failure evidence must identify the last completed phase and preserve enough diagnostic context to
distinguish source, projection, cache, publication, and subscriber failures. A retry or dead-letter
record is diagnostic evidence for this rebuildable projection; it is not permission to skip a
critical consumer record.

## Completion gate

Issue #133 can close only when focused module tests, the market-data deployment certification,
static analysis, Flyway checks, documentation checks, and the GitHub Actions runs for the final
commit pass. Repository readiness without an executed deployment run is partial evidence, not
certification. The local production-like result proves the retained repository deployment shape; it
does not claim external production promotion, cross-region availability, or exactly-once network
delivery.
