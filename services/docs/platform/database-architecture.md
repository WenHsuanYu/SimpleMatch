# Database Architecture

This is the canonical target specification for SimpleMatch PostgreSQL topology and schema
boundaries. It describes the intended steady state rather than the current migration completion of
any particular service.

## Topology

SimpleMatch uses one operational PostgreSQL deployment with a schema owned by each service that
persists business or projection state. This keeps local and operational database management bounded
while retaining service-level data ownership. A service does not gain access to another service's
data merely because both schemas are hosted by the same PostgreSQL deployment.

| Service            | Target schema      | Intended responsibility                                                |
|--------------------|--------------------|------------------------------------------------------------------------|
| `risk-service`     | `risk_service`     | Durable admissions, risk decisions, and outbox records                 |
| `account-service`  | `account_service`  | Limits, positions, and reservations                                    |
| `persistence`      | `persistence`      | Execution, order, and audit projections                                |
| `matching-engine`  | `matching_engine`  | Reserved for a PostgreSQL-owned journal or outbox if one is introduced |
| `quickfix-gateway` | `quickfix_gateway` | Reserved for PostgreSQL-owned FIX continuity metadata if introduced    |

Other services receive a schema only when they have a clear persistent ownership need. A shared
`public` schema is not an integration boundary.

## Ownership and access rules

- One service owns each schema's DDL, data writes, and migration history.
- Runtime credentials and the database search path select the owner's schema explicitly; no service
  relies on an implicit `public` schema.
- A service does not directly write another schema. Cross-service interaction uses gRPC, Kafka
  events, or a rebuildable projection.
- Cross-schema foreign keys and write-path joins are prohibited. They couple service availability
  and schema evolution.
- Read sharing is supplied by explicit APIs or projections with their own freshness and rebuild
  guarantees.

## Schema evolution and publication

Every schema change is a versioned Flyway migration owned by the corresponding service and executed
through the shared
`simplematch.flyway-service`
convention. The owner keeps its Flyway history in its own schema, may evolve versions independently,
and validates the migration before deployment.

When a state transition publishes an integration event, its outbox is in the same owner schema and
transaction as that state. Debezium or another connector reads a schema-qualified outbox table;
connector configuration never treats a schema name as aggregate metadata.

## Operational implications

The shared deployment is an operational convenience, not shared application state. Backup, restore,
permissions, connection limits, and disaster recovery must preserve schema ownership and allow an
owner to recover without another service writing its data. Moving to separate database deployments
later is an infrastructure change that preserves these ownership and contract rules.

## Target versus execution state

This page owns the target topology and boundary rules. Migration file references, rollout
sequencing, and completion checklists belong in the
[database implementation guide](../../../docs/database-architecture.md).
