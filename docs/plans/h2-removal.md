# H2 removal decisions

Current P2 status: complete runtime evidence and completed milestone review, with no remaining findings.
The restored-host run and corrected local FIX certification executed 1,090 cases with zero failures, errors, or skips.
The first invocation failed and remains recorded separately. This is component evidence, not complete #202 or deployed acceptance.

Owner: [#202](https://github.com/WenHsuanYu/SimpleMatch/issues/202).
The owner prohibits H2 and requires a decision for each existing case.
This inventory uses the source at `1d4fb205679eb3d14940b5992e03a7591db58693`.
These are migration decisions, not a completion or PostgreSQL PASS record.

Use real PostgreSQL when SQL, migrations, constraints, transaction rollback, concurrency, or durable restart state belongs to the scenario.
Use mocks when the scenario concerns dependency errors or protocol validation outside persistence.
Configuration-only tests need a valid PostgreSQL DSN but no database connection.
Keep the existing public assertions and avoid a second fixture or assertion framework.

The shared test fixture starts one Testcontainers PostgreSQL instance per JVM and gives each database-backed fixture an isolated database.
Test callers retain canonical Flyway migration ownership. No product schema is initialized through ad hoc SQL.
Testcontainers owns container cleanup.

## Test decisions

Each row covers the existing class's scenarios. Mixed cases identify the individual responsibility split.

| Existing service/test class | Decision |
| --- | --- |
| `account-service/AccountServiceApplicationTest` | PostgreSQL: verifies an actual managed-pool connection and selected schema. |
| `account-service/AccountServiceLocalProfileApplicationTest` | Configuration-only PostgreSQL DSN; no connection or database required. |
| `account-service/AccountReservationApplicationServiceTransactionTest` | PostgreSQL: scenario asserts SQL, constraints, transactions, durable state, or JDBC-backed protocol behavior. |
| `account-service/AccountDataSourceAutoConfigurationTest` | Connection-free PostgreSQL mapping/error cases. Retire two H2-only compatibility cases. |
| `account-service/AccountFinalMatchingEventProgressRestartTest` | PostgreSQL: scenario asserts SQL, constraints, transactions, durable state, or JDBC-backed protocol behavior. |
| `account-service/AccountReservationV2GrpcServiceTest` | PostgreSQL for acceptance/replay/conflicts. Mocks for validation and error mapping. |
| `account-service/FinalMatchingEventAccountConsumerTest` | PostgreSQL: scenario asserts SQL, constraints, transactions, durable state, or JDBC-backed protocol behavior. |
| `account-service/FinalMatchingEventAccountApplicationServiceTest` | PostgreSQL for transactions/inbox/progress. No database for the defensive-value case. |
| `account-service/AccountServiceFlywayMigrationTest` | PostgreSQL: scenario asserts SQL, constraints, transactions, durable state, or JDBC-backed protocol behavior. |
| `account-service/JdbcAccountOutboxRepositoryTest` | PostgreSQL: scenario asserts SQL, constraints, transactions, durable state, or JDBC-backed protocol behavior. |
| `market-data-projection/MarketDataProjectionApplicationServiceTest` | PostgreSQL: scenario asserts SQL, constraints, transactions, durable state, or JDBC-backed protocol behavior. |
| `market-data-projection/RuntimeMarketDataPublicationTest` | PostgreSQL: scenario asserts SQL, constraints, transactions, durable state, or JDBC-backed protocol behavior. |
| `market-data-projection/MarketDataProjectionFlywayMigrationTest` | PostgreSQL: scenario asserts SQL, constraints, transactions, durable state, or JDBC-backed protocol behavior. |
| `persistence/PersistenceMatchingEventProgressRestartTest` | PostgreSQL: scenario asserts SQL, constraints, transactions, durable state, or JDBC-backed protocol behavior. |
| `persistence/PersistenceMatchingEventConsumerTest` | PostgreSQL for successful durable application. Mocks for restored quarantine and invalid-key rejection, with no handler call. |
| `persistence/MatchingEventPersistenceApplicationServiceTest` | PostgreSQL: scenario asserts SQL, constraints, transactions, durable state, or JDBC-backed protocol behavior. |
| `persistence/JdbcMatchingEventStoreTest` | PostgreSQL: scenario asserts SQL, constraints, transactions, durable state, or JDBC-backed protocol behavior. |
| `persistence/PersistenceFlywayMigrationTest` | PostgreSQL: scenario asserts SQL, constraints, transactions, durable state, or JDBC-backed protocol behavior. |
| `quickfix-gateway/QuickFixGatewayApplicationTest` | Configuration-only PostgreSQL DSN; no connection or database required. |
| `quickfix-gateway/QuickFixGatewayLiveObservationApplicationTest` | Configuration-only PostgreSQL DSN; no connection or database required. |
| `quickfix-gateway/QuickFixGatewayLocalProfileApplicationTest` | Configuration-only PostgreSQL DSN; no connection or database required. |
| `quickfix-gateway/QuickFixFinalMatchingEventProgressRestartTest` | PostgreSQL: scenario asserts SQL, constraints, transactions, durable state, or JDBC-backed protocol behavior. |
| `quickfix-gateway/FinalFixDeliveryDispatcherTest` | PostgreSQL: scenario asserts SQL, constraints, transactions, durable state, or JDBC-backed protocol behavior. |
| `quickfix-gateway/FinalMatchingEventFixAdmissionIntegrationTest` | PostgreSQL: scenario asserts SQL, constraints, transactions, durable state, or JDBC-backed protocol behavior. |
| `quickfix-gateway/QuickFixCertificationEvidenceTest` | PostgreSQL: scenario asserts SQL, constraints, transactions, durable state, or JDBC-backed protocol behavior. |
| `quickfix-gateway/FinalMatchingEventFixConsumerTest` | PostgreSQL for successful durable application. Mocks for restored quarantine and invalid-key rejection, with no handler call. |
| `quickfix-gateway/FinalMatchingEventFixDeliveryApplicationServiceTest` | PostgreSQL: scenario asserts SQL, constraints, transactions, durable state, or JDBC-backed protocol behavior. |
| `quickfix-gateway/FinalMatchingEventFixLifecycleIntegrationTest` | PostgreSQL: scenario asserts SQL, constraints, transactions, durable state, or JDBC-backed protocol behavior. |
| `quickfix-gateway/JdbcGatewayOperationAuditStoreTest` | PostgreSQL: scenario asserts SQL, constraints, transactions, durable state, or JDBC-backed protocol behavior. |
| `quickfix-gateway/QuickFixGatewayFlywayMigrationTest` | PostgreSQL: scenario asserts SQL, constraints, transactions, durable state, or JDBC-backed protocol behavior. |
| `risk-service/RiskGrpcServerApplicationTest` | Configuration-only PostgreSQL DSN; no connection or database required. |
| `risk-service/RiskServiceApplicationTest` | Configuration-only PostgreSQL DSN; no connection or database required. |
| `risk-service/RiskServiceLocalProfileApplicationTest` | Configuration-only PostgreSQL DSN; no connection or database required. |
| `risk-service/AccountReservationSagaRecoveryIntegrationTest` | PostgreSQL: scenario asserts SQL, constraints, transactions, durable state, or JDBC-backed protocol behavior. |
| `risk-service/GrpcAccountReservationClientAccountIdentityTest` | PostgreSQL: scenario asserts SQL, constraints, transactions, durable state, or JDBC-backed protocol behavior. |
| `risk-service/OrderAdmissionApplicationServiceTransactionTest` | PostgreSQL: scenario asserts SQL, constraints, transactions, durable state, or JDBC-backed protocol behavior. |
| `risk-service/TradingSessionBarrierServiceTransactionTest` | PostgreSQL: scenario asserts SQL, constraints, transactions, durable state, or JDBC-backed protocol behavior. |
| `risk-service/TradingSessionOperationsGrpcServiceTest` | Mock transaction manager; in-memory outbox and RPC status mapping. |
| `risk-service/AdmissionJournalConstraintsTest` | PostgreSQL: scenario asserts SQL, constraints, transactions, durable state, or JDBC-backed protocol behavior. |
| `risk-service/JdbcCdcDeliveryProgressStoreTest` | PostgreSQL: scenario asserts SQL, constraints, transactions, durable state, or JDBC-backed protocol behavior. |
| `risk-service/JdbcOutboxRepositoryTest` | PostgreSQL: scenario asserts SQL, constraints, transactions, durable state, or JDBC-backed protocol behavior. |
| `risk-service/RiskServiceFlywayMigrationTest` | PostgreSQL: scenario asserts SQL, constraints, transactions, durable state, or JDBC-backed protocol behavior. |
| `risk-service/H2TestDatabaseUrl` | Retire H2-only helper and its dedicated test. |
| `risk-service/H2TestDatabaseUrlTest` | Retire H2-only helper and its dedicated test. |

## Retirements and completion gates

- Retire H2-specific datasource schema initialization and URL support after the last consumer migrates.
- Remove H2 dependency declarations and lock entries after each module is converted.
- Account's exact table/migration-count snapshots and repeated-Flyway check retire. The actual Gradle PostgreSQL smoke owns migration wiring.
- Account's 21 timestamp/outcome negative cases reduce to nine independent checks: two timestamp rules, two malformed-code representatives, one empty-detail case, and four state/field checks.
- Retire the duplicate Unicode whitespace matrix and seven redundant malformed-code variants. Existing domain tests keep detailed token validation.
- Verify database rejection from an otherwise valid row without asserting diagnostic constraint-name text.
- Do not relabel historical H2 results as PostgreSQL evidence.
- Review engine-specific expectations against actual PostgreSQL results without weakening business assertions.
- After service PostgreSQL constraints have current evidence, remove equivalent legacy aggregate SQL probes and their dedicated support.

Source cutover removes the H2 drivers, test fixtures, datasource compatibility, and JDBC dialect alternatives.
Complete PostgreSQL suite execution is recorded in the restored-host evidence below.

## Historical progress before restored host execution

Account's H2 uses and dependency are removed. Configuration-only cases open no database.
Database-backed Account behavior uses PostgreSQL, and RPC validation/error mapping uses mocks.
The complete Account suite passed before the constraint-matrix reduction.
The retained 50-case migration/constraint class passed after that reduction.
Risk's five trading-session RPC mapping cases passed with a mock transaction manager.
Repository static analysis passed using the normal offline dependency cache after an online metadata DNS failure.
At that point, project-wide H2 removal was incomplete. The completed P2 component evidence is recorded below; final #202 acceptance remains incomplete.

## Risk cutover decisions

- The three Spring context/server-composition classes use connection-free PostgreSQL DSNs. Their RPC rejection occurs before persistence.
- Admission, barrier rollback, saga recovery, Account identity/replay, outbox, and durable CDC cases use real PostgreSQL.
- Saga recovery keeps two independent databases and transaction managers.
- Retire the H2 URL helper and its sole test after all callers move.
- Retire exact table/migration-count snapshots, repeated Flyway checks, and constructor-parameter reflection. Existing migration smoke and immutable PMD rules remain authoritative.
- Replace CDC shadow-table DDL with canonical migrations. One existing observation case uses large Kafka offsets and epoch values instead of a metadata-only column-width test.
- Reduce SQL rejection-code/detail character matrices to basic representatives. Detailed application validation remains with domain tests.
- Use PostgreSQL fixture SQL and verify integrity failures rather than accepting arbitrary runtime exceptions.

## Market Data Projection cutover criteria

All three database-backed classes must use isolated PostgreSQL databases with canonical migrations.
Keep transaction rollback, replay, durable publication, and cache-repair assertions.
Keep distinct database rules and boundary cases; remove duplicate symbol/venue examples and provenance cases already covered by instrument checks.
The module must have no H2 dependency or connection lifecycle workaround.
Its complete suite must pass against PostgreSQL before this cutover is reported complete.

Risk's first PostgreSQL run exposed six fixture failures: missing outbox timestamps and a UUID parameter bound as text.
Those fixtures are corrected, including a valid baseline before the negative-partition update.
The next run could not create the Docker Unix socket (`Operation not permitted`), despite requesting escalated execution.
Its 63 database-fixture failures have one infrastructure cause and provide no business-behavior verdict.
Docker CLI access fails in the same execution route. Do not retry the suite until daemon access is restored.

## Persistence cutover criteria

Use PostgreSQL for durable restart, repository facts/replay, rollback, and constraints.
The native-event consumer's successful persistence case uses PostgreSQL.
Its restored-quarantine and invalid-key cases use a mock handler and verify that persistence is never called; they need no database.
Retire old table inventories, migration-count snapshots, and repeated Flyway assertions.
Keep the trade-quantity and quarantine-lifecycle constraint cases with valid initial rows.
Compile and run the complete module suite against PostgreSQL before reporting completion.

## QuickFIX Gateway cutover criteria

The three composition/profile classes use connection-free PostgreSQL DSNs.
Use PostgreSQL for delivery ledgers, operation audits, restart restoration, and actual JDBC-backed QuickFIX acceptor/session tests.
Keep public FIX identity, quantity, retry, and lifecycle assertions.
The consumer's quarantine/key-rejection cases use a mock handler with no persistence call, while its successful delivery case uses PostgreSQL.
Retire metadata-only table/type/length snapshots and repeated-Flyway assertions. Keep actual large-log-ID round trips and delivery/quarantine constraints.
The complete module suite must pass against PostgreSQL before this cutover is complete.

## Runtime compatibility removal criteria

Only PostgreSQL JDBC URLs and PostgreSQL URIs are supported in every environment.
Keep one negative H2 URL input to prove rejection; it must never open an H2 connection.
Remove H2-only schema initialization and the unused dependency alias.
Let canonical Flyway tasks own schema creation, and let Hikari select the configured service schema.

## Historical focused evidence before the complete P2 run

All four remaining service test sources compile. H2 driver dependencies and the catalog alias are removed.
The shared configuration suite passed 47 tests, including PostgreSQL-only DSN rejection.
Connection-free Risk tests passed 10 cases, and Persistence/Gateway mock and composition selections passed seven cases.
All results have zero failures, errors, or skips. Global static analysis passed.
These selections exclude required database scenarios; they do not establish a complete PostgreSQL or #202 PASS.

Remove remaining engine-detection queries and non-PostgreSQL SQL fallbacks.
Use the existing PostgreSQL SQL path, preserve its parameters and business behavior, and rely on the retained real-database suites.
No replacement tests of SQL strings or engine detection are needed.

The test-value review also retired `acceptsCompleteRejectionReason`: its helper already asserts the valid insert in every retained rejection case.
Two remaining single-value Risk parameterized tests are ordinary tests.
The runtime cutover removes dialect detection and non-PostgreSQL fallbacks across Account, Risk, Persistence, Market Data, Gateway, and Query.
Existing PostgreSQL upserts and their business parameters remain; no migration SQL changes are required.

After removal of the JDBC dialect alternatives, global static analysis passed again.

## Restored host execution and P2 runtime validation

Host Docker access recovered with an unconfined process and the original Desktop daemon identity.
The first complete PostgreSQL cutover run completed all ordinary service suites successfully.
Its local FIX certification failed only at the prepared-client case's immediate server `isLoggedOn()` assertion after client completion.
Retire that assertion before rerun: selected-delivery receipt is the case's purpose, peer disconnect is asynchronous, and the separate simulator lifecycle case already waits for logout.
Keep all receipt/selection assertions and unconditional acceptor cleanup. Do not add a sleep, retry framework, or weaken the lifecycle case.
The first invocation remains failed; record the corrected certification rerun separately.

## P2 complete runtime evidence

Evidence: `out/verification/issue-202-p2-postgres-01/`.
The ordinary suite counts are shared config 47, Account 161, Risk 183, Market Data 58, Persistence 14, Query 362, and Gateway 257.
The additional Query collector passed once, and local FIX certification passed seven cases after the documented assertion retirement.
Total: 1,090 cases, zero failures, errors, or skips. No required database case was excluded to obtain these results.
`gradle.log` preserves the first failed certification invocation; `certification-rerun.log` records the successful corrected seven-case suite.
`final-test-counts.json` records per-task counts. Earlier socket-denied results remain historical failures, not current blockers.
The run uses the original Docker Desktop daemon and isolated Testcontainers databases, with no Kubernetes mutation.

Final static analysis passed with normal caches after the certification change.
Final Docker inventory matches the initial 7 containers, 17 volumes, and 29 image identities; no run-owned fixture resources remain and no resources were manually deleted.
Builder and generated-build cache measurements are retained separately. No reclaimed-space claim is made.

The reviewed P2 source is committed locally in seven cohesive service/configuration commits ending at `06bf738`.
These commits have not been pushed. P0/P5a task consolidation and retirement changes remain separate working-tree work.
The bad-smell check found no new general-purpose fixture framework, duplicated dialect branch, suppression, or changed analysis policy.
