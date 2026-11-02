# H2 removal decisions

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
| `persistence/PersistenceMatchingEventConsumerTest` | PostgreSQL: scenario asserts SQL, constraints, transactions, durable state, or JDBC-backed protocol behavior. |
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
| `quickfix-gateway/FinalMatchingEventFixConsumerTest` | PostgreSQL: scenario asserts SQL, constraints, transactions, durable state, or JDBC-backed protocol behavior. |
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

Remaining modules and runtime compatibility removal are incomplete.

## Current progress

Account's H2 uses and dependency are removed. Configuration-only cases open no database.
Database-backed Account behavior uses PostgreSQL, and RPC validation/error mapping uses mocks.
The complete Account suite passed before the constraint-matrix reduction.
The retained 50-case migration/constraint class passed after that reduction.
Risk's five trading-session RPC mapping cases passed with a mock transaction manager.
Repository static analysis passed using the normal offline dependency cache after an online metadata DNS failure.
These are component results. Project-wide H2 removal and final #202 acceptance remain incomplete.
