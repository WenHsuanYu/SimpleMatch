# Database Architecture

本文件把 SimpleMatch 的 PostgreSQL 拓樸決策固定下來，作為 README、Flyway 規約、以及後續 schema rollout 的共同參考。

目前採用的方案是：**單一 PostgreSQL database instance，搭配每個服務各自擁有的 schema**。這是本 repo 對先前討論中的 **Option 1** 的正式落版。

## 1. 決策摘要

### 採用方案：Option 1

- 單一 PostgreSQL instance
- 每個需要持久化的服務各自擁有 schema
- 每個服務只透過自己的 migration、repository、connector、runtime config 管理自己的 schema
- 跨服務互動仍走 gRPC / Kafka 事件，不走 cross-schema write coupling

### 不採用的方案

| 方案 | 結論 | 原因 |
| --- | --- | --- |
| 單一 DB + 共用 `public` schema | 不採用 | 容易讓 schema ownership、Flyway version space、Debezium table routing 混在一起 |
| 每個服務一個獨立 DB instance | 暫不採用 | 隔離度高，但目前會把本專案的本機開發、部署、備援與營運成本拉高 |

### 這個決策的狀態

- 這是目前 README 與 docs 的目標架構決策。
- repo 內已有 per-service migration location，且 shared Flyway convention 已支援 per-service schema。
- `risk-service` 已完成 schema-qualified migration、runtime datasource schema 綁定、以及 Debezium outbox connector 對齊。
- `account-service` 與 `persistence` 已完成 owner schema 宣告、schema-qualified migration、以及 schema-aware migration test 對齊。
- 目前 repo 內只有 `risk-service` 有直接的 runtime datasource wiring；若後續新增其他持久化服務或讓既有服務加入 JDBC runtime，仍需先定義 owner schema 再落資料表與 datasource config。
- 實作追蹤請同步參考 [tasks.md](../tasks.md)。

## 2. 為什麼選 Option 1

這個方案的重點不是把所有服務塞進同一個 schema，而是把「單一 DB instance 的營運簡化」與「每服務 own schema 的邊界清晰」同時拿到。

### 2.1 維持營運面簡單

- 本機開發、compose、kind、CI 都只需要管理一個 PostgreSQL instance。
- 備份、監控、連線治理、Debezium source connector 的部署面較簡單。

### 2.2 讓 service ownership 清楚

- `risk-service` 擁有自己的 ingress journal 與 outbox。
- `account-service` 擁有自己的額度、部位、reservation 權威資料。
- `persistence` 擁有自己的 projection / read-model tables。
- 未來若 `matching-engine` 或 `quickfix-gateway` 真的需要 PostgreSQL，也應有自己的 schema，而不是把資料落進別人的 schema。

### 2.3 避免 Flyway 與 schema 演進互相污染

- 每個服務都需要自己的 Flyway history 與 migration lifecycle。
- 若多個服務共享 `public` schema，雖然 migration location 可以分開，但 schema 與 history 的語意仍容易耦合。
- 服務擁有 schema 後，`V1` / `V2` 這類 migration version 可以在各自 schema 內獨立演進。

### 2.4 讓 Debezium / Outbox 對齊真正的 owner

- `risk-service` 的 outbox 應屬於 `risk_service.outbox`，而不是 `public.outbox`。
- 這樣 connector 的 `table.include.list`、權限、與稽核都能清楚對齊到 service owner。

## 3. 這個決策改變什麼，沒有改變什麼

### 改變的部分

- PostgreSQL 中的業務表與 outbox，不再預設落在 `public`。
- Flyway plugin 需要有 schema-aware 設定，而不是只知道 DSN。
- Debezium connector 需要使用 schema-qualified table include list。
- 服務 runtime datasource 需要明確選到自己的 schema。

### 沒有改變的部分

- `aggregate_type` 仍然是**業務 / 事件層的 aggregate 識別**，不是 schema 名稱。
- 例如 `risk-service` 的 outbox row 仍可維持 `aggregate_type = risk_submission`。
- 即使 outbox table 未來位於 `risk_service.outbox`，也不表示 aggregate 變成 `risk_service`。

這一點很重要：**schema ownership 解決的是資料持久化邊界；aggregate metadata 解決的是事件與領域語意。兩者相關，但不是同一個欄位。**

## 4. 服務與 schema 對應

| 服務 | schema | 目前 / 規劃中的資料 |
| --- | --- | --- |
| `risk-service` | `risk_service` | `risk_submissions`, `outbox` |
| `account-service` | `account_service` | `account_limits`, `account_positions`, `account_reservations` |
| `persistence` | `persistence` | `orders`, `executions`, `processed_events` |
| `matching-engine` | `matching_engine` | 目前保留；若未來有 PostgreSQL-owned journal / outbox，再由此 schema 承接 |
| `quickfix-gateway` | `quickfix_gateway` | 目前保留；若未來把 FIX session store 或 WAL metadata 落 PostgreSQL，再由此 schema 承接 |
| `marketdata-publisher` / `marketdata-streamer` / `query-service` | 各自保留 | 只有在服務真的需要 PostgreSQL 持久化時，才建立自己的 schema |

## 5. Ownership 規則

### 5.1 單一 writer owner

- 每個 schema 只由一個服務負責寫入與 migration。
- 其他服務不得直接寫入別人的 schema。

### 5.2 禁止跨 schema persistence coupling

- 不在 write path 上引入 cross-schema foreign key。
- 不以 cross-schema join 當成跨服務整合主線。
- 跨服務資料交換走 gRPC API、Kafka 事件、或 projection rebuild。

### 5.3 Outbox 跟著 owner schema 走

- 每個發事件的服務，outbox 應存在於自己的 schema。
- Debezium connector 必須以 schema-qualified table 指定來源。

### 5.4 Runtime credentials 與 search path 必須明確

- 服務 runtime 需要明確指定自己的 schema，例如 JDBC `currentSchema` 或等價設定。
- 若先用 shared DB user，也至少要讓 runtime 與 migration 都明確設定 schema，不依賴預設 `public`。

## 6. Option 1 的實作觸點

本節不是直接修改程式碼，而是把真正落地 Option 1 時需要碰的檔案與責任先寫清楚，避免後續 rollout 只改到一半。

### 6.1 Flyway convention 與 build 設定

| 檔案 | 需要調整的重點 |
| --- | --- |
| [build-logic/src/main/kotlin/com/simplematch/gradle/SimpleMatchFlywayServicePlugin.kt](../build-logic/src/main/kotlin/com/simplematch/gradle/SimpleMatchFlywayServicePlugin.kt) | 新增 service-owned schema 設定來源，讓 plugin 不只解析 DSN，也能設定 `schemas` / `defaultSchema` / Flyway history 所在 schema |
| [services/risk-service/build.gradle.kts](../services/risk-service/build.gradle.kts) | 宣告 `risk_service` 為 owner schema |
| [services/account-service/build.gradle.kts](../services/account-service/build.gradle.kts) | 宣告 `account_service` 為 owner schema |
| [services/persistence/build.gradle.kts](../services/persistence/build.gradle.kts) | 宣告 `persistence` 為 owner schema |

建議的 plugin 行為：

- 支援 service-scoped schema override，例如 `-PriskServiceFlywaySchema=risk_service`
- 支援對應環境變數，例如 `RISK_SERVICE_FLYWAY_SCHEMA`
- 明確把 Flyway history 放進 owner schema，避免多服務共用同一份 `public.flyway_schema_history`

目前狀態：以上能力已接入 shared Flyway plugin，且 `risk-service`、`account-service`、`persistence` 已在 build script 宣告 owner schema。

### 6.2 Migration SQL

| 檔案 | 需要調整的重點 |
| --- | --- |
| [services/risk-service/src/main/resources/db/migration/risk-service/V1__create_risk_service_tables.sql](../services/risk-service/src/main/resources/db/migration/risk-service/V1__create_risk_service_tables.sql) | 建立 `risk_service` schema，並讓 `risk_submissions` / `outbox` 明確屬於該 schema |
| [services/risk-service/src/main/resources/db/migration/risk-service/V2__drop_legacy_outbox_relay_columns.sql](../services/risk-service/src/main/resources/db/migration/risk-service/V2__drop_legacy_outbox_relay_columns.sql) | 移除 / 調整 object 時要使用 schema-qualified 名稱 |
| [services/risk-service/src/main/resources/db/migration/risk-service/V3__add_outbox_kafka_partition_id.sql](../services/risk-service/src/main/resources/db/migration/risk-service/V3__add_outbox_kafka_partition_id.sql) | 對 `risk_service.outbox` 做變更，而不是預設 `public.outbox` |
| [services/account-service/src/main/resources/db/migration/account-service/V1__create_account_service_tables.sql](../services/account-service/src/main/resources/db/migration/account-service/V1__create_account_service_tables.sql) | 建立 `account_service` schema，並讓表名屬於該 schema |
| [services/persistence/src/main/resources/db/migration/persistence/V1__create_projection_tables.sql](../services/persistence/src/main/resources/db/migration/persistence/V1__create_projection_tables.sql) | 建立 `persistence` schema，並讓 projection tables 屬於該 schema |

SQL 寫法至少要滿足其中一種：

- 每個物件都使用 schema-qualified 名稱
- 或 migration 一開始就明確設定 `search_path`

但不論選哪一種，最終目標都一樣：**不能再依賴 implicit `public`。**

目前狀態：`risk-service`、`account-service`、`persistence` 的 migration 都已改為 schema-qualified SQL；`risk-service` 並已以 `risk_service.outbox` 為 outbox owner table。

### 6.3 Runtime datasource 與 bootstrap

| 檔案 | 需要調整的重點 |
| --- | --- |
| [services/risk-service/src/main/java/com/simplematch/riskservice/config/RiskServiceConfiguration.java](../services/risk-service/src/main/java/com/simplematch/riskservice/config/RiskServiceConfiguration.java) | runtime datasource 需要選到 `risk_service` schema |
| [services/account-service/src/main/java/com/simplematch/accountservice/bootstrap/RuntimeConfigConfiguration.java](../services/account-service/src/main/java/com/simplematch/accountservice/bootstrap/RuntimeConfigConfiguration.java) | 若 account-service 直接建立 datasource，需把 schema owner 接進 runtime config |
| [services/account-service/src/main/java/com/simplematch/accountservice/bootstrap/AccountServiceRuntime.java](../services/account-service/src/main/java/com/simplematch/accountservice/bootstrap/AccountServiceRuntime.java) | 啟動流程若會驗證 DB 或 migration，需遵守 `account_service` schema 邊界 |
| [services/persistence/src/main/java/com/simplematch/persistence/PersistenceApplication.java](../services/persistence/src/main/java/com/simplematch/persistence/PersistenceApplication.java) | persistence runtime 若建立 datasource / repository，也需對齊 `persistence` schema |

目前狀態：`risk-service` runtime datasource 已明確將 schema 綁定到 `risk_service`。

### 6.4 Debezium / connector 設定

| 檔案 | 需要調整的重點 |
| --- | --- |
| [deploy/compose/risk-service-outbox-connector.json](../deploy/compose/risk-service-outbox-connector.json) | `table.include.list` 需從 `public.outbox` 改為 `risk_service.outbox` |
| [deploy/k8s/risk-service-outbox-connector-configmap.yaml](../deploy/k8s/risk-service-outbox-connector-configmap.yaml) | 同上，並確認 deployment 環境下的 connector 權限與 schema-qualified table 設定一致 |

目前狀態：兩份 `risk-service` connector 範本都已切到 `risk_service.outbox`。

### 6.5 測試與驗證

| 檔案 | 需要調整的重點 |
| --- | --- |
| [services/risk-service/src/test/java/com/simplematch/riskservice/store/RiskServiceFlywayMigrationTest.java](../services/risk-service/src/test/java/com/simplematch/riskservice/store/RiskServiceFlywayMigrationTest.java) | 驗證 migration 時要建立或選到 `risk_service` schema |
| [services/account-service/src/test/java/com/simplematch/accountservice/store/AccountServiceFlywayMigrationTest.java](../services/account-service/src/test/java/com/simplematch/accountservice/store/AccountServiceFlywayMigrationTest.java) | 驗證 `account_service` schema 的 migration |
| [services/persistence/src/test/java/com/simplematch/persistence/store/PersistenceFlywayMigrationTest.java](../services/persistence/src/test/java/com/simplematch/persistence/store/PersistenceFlywayMigrationTest.java) | 驗證 `persistence` schema 的 migration |
| [services/risk-service/src/test/java/com/simplematch/riskservice/submission/SubmissionServiceIntegrationTest.java](../services/risk-service/src/test/java/com/simplematch/riskservice/submission/SubmissionServiceIntegrationTest.java) | 若 integration test 會經過 datasource / migration，需同步驗證 schema-aware startup |

目前狀態：`risk-service` 的 migration / repository / integration / gRPC / application tests 已對齊 `risk_service` schema 並通過聚焦驗證；`account-service` 與 `persistence` 的 migration tests 也已對齊各自 owner schema。

## 7. 建議 rollout 順序

1. 先讓 Flyway plugin 支援 service-owned schema。
2. 優先處理 `risk-service`，因為它同時牽涉 migration、runtime datasource、以及 Debezium outbox connector。
3. 接著處理 `account-service` 與 `persistence`，把現有 projection / authority tables 移到各自 schema。
4. 同步更新 migration tests 與整合測試，確保不再依賴 `public`。
5. 最後收斂 compose / k8s connector 與營運文件，避免執行環境仍指向舊表。

## 8. Checklist

- [x] PostgreSQL 拓樸已明確固定為「單一 instance + 每服務各自 schema」
- [x] `risk-service`, `account-service`, `persistence` 的 owner schema 名稱已固定
- [x] Flyway plugin 支援 per-service schema 設定與 schema-local history
- [x] migration SQL 不再依賴 implicit `public`
- [x] 現有 runtime datasource 路徑能明確選到 owner schema
- [x] Debezium connector 使用 schema-qualified outbox table
- [x] 測試環境會建立或選到正確 schema
- [x] README 與 tasks 已對齊本文件
- [x] `aggregate_type` 的領域語意與 schema 名稱沒有被混淆
- [x] 後續實作若引入新持久化服務，必須先定義其 owner schema 再落資料表
