# SimpleMatch — Implementation Tasks Checklist

> 目標：把 README 的架構落成「能開工、能跑、能觀測、能在 kind/K8s 做 smoke test」的任務拆解。
>
> 範圍假設：
>
> - 語言：polyglot（Java/Spring 為主；`matching-engine` 保留 C++20）
> - 核心業務資料面：gRPC + Kafka；其中 gRPC 承載同步准入 / 查詢依賴，Kafka 承載非同步保序事件流
> - 營運控制面：配置 / 調度 / 策略下發另行處理，不在本 checklist 的交易主線範圍內
> - System-of-record：PostgreSQL（含 outbox / inbox）
> - Read model：Redis（ **Redis-first 查詢首選**；屬可重建 projection，權威仍在 DB/`account-service`）
> - 對外：QuickFix/J（Java，FIX 4.4，`quickfix-gateway` 作為 Acceptor）、gRPC
    streaming（marketdata-streamer）
> - Observability：OpenTelemetry + Prometheus + Grafana + Alertmanager
>

---

## 0) Repo / Monorepo 結構（專案骨架）

- [x] 建立目錄（若尚未建立）：`services/`, `shared-java/`, `proto/`, `config/`, `deploy/`, `docs/`
- [x] 建立頂層 `CMakeLists.txt`（或 workspace-level build 指南）
- [x] 建立 vcpkg/依賴說明：QuickFIX（C++）、gRPC、protobuf、librdkafka、PostgreSQL client、Redis client、OTel
  SDK、Prometheus exporter
- [x] 統一設定載入方式（環境變數 + config 檔）：
    - [x] `ENV`（dev/stage/prod）
    - [x] Kafka brokers / topics / partitions
    - [x] Postgres DSN
    - [x] Redis endpoints
    - [x] gRPC targets（同步依賴）
    - [x] Observability（OTel exporter、Prometheus port）

---

## 1) 共用 modules（跨服務重用）

> 目前 Java 共用程式碼實際落在 `shared-java/`；以下仍沿用 `libs/*` 的條目，應視為對應 shared module
> 的待辦，而不是現行 repo
> 目錄名稱。
> 交叉驗證結果：`shared-java/` 目前只有 `simplematch-config` 與 `simplematch-contracts`；Kafka / DB /
> gRPC / observability
> runtime 仍以 service-local 類別分散在各服務，尚未抽成 shared module。

### 1.1 logging / config / time

- [ ] `libs/common`：結構化 JSON logging（統一欄位）
    - [x] 現況：workspace 未找到 `LogContext`、MDC/trace context helper、logback JSON encoder 或
      repo-level structured logging 設定；服務目前直接使用 SLF4J 預設 logger
    - [ ] `LogContext`：`service`, `env`, `trace_id`, `span_id`, `order_id`, `cl_ord_id`, `event_id`,
      `symbol`,
      `account_id`
    - [ ] logger 初始化（level、sink、格式）
    - [ ] request/trace context 注入 helper
- [x] `shared-java/simplematch-config`：Spring configuration authority
    - [x] `EnvironmentProperties`、`KafkaProperties`、`PostgresProperties`、`RedisProperties`、
      `GrpcProperties`、`RoutingProperties`、`ObservabilityProperties` 與 `MarketProperties` 由
      Spring Environment 綁定平台 capability settings
    - [x] `QuickFixGatewayFileProperties`、`QuickFixGatewayRuntimeProperties` 與
      `QuickFixGatewayRiskClientProperties` 擁有 gateway-local settings；JSON loader、legacy alias
      與 custom post-processor 已移除
    - [x] profile exclusivity、ConfigMap/Secret key ownership、以及 staging/production required
      Kubernetes inputs 在啟動時 fail-fast 驗證
- [x] [#70](https://github.com/WenHsuanYu/SimpleMatch/issues/70) shared capability property modules
  獨立綁定 environment、Kafka、PostgreSQL、Redis、gRPC、routing、observability 與 market
  settings，並重用既有 defaults、profile precedence 與 startup validation。
- [x] [#71](https://github.com/WenHsuanYu/SimpleMatch/issues/71) migrate Account Authority runtime and
  persistence wiring to `GrpcProperties` and `PostgresProperties` while preserving profile,
  datasource, and startup behavior。
- [x] [#72](https://github.com/WenHsuanYu/SimpleMatch/issues/72) migrate Risk Admission runtime,
  persistence, routing, Kafka outbox, and account-client wiring to the required capability modules
  while preserving partition, profile, datasource, and startup behavior。
- [x] [#73](https://github.com/WenHsuanYu/SimpleMatch/issues/73) migrate Market Reference datasource
  wiring to `PostgresProperties` while preserving publication startup and local configuration。
- [ ] `libs/common`：時間/ID 工具
    - [ ] 現況：workspace 仍未找到 shared `NowUnixMs()` helper；`UuidV7()` 已由
      `shared-java/simplematch-config` 提供
    - [ ] `NowUnixMs()`
    - [x] `UuidV7()`
    - [x] 將 ingress operation identity 接到 shared `UuidV7()` helper：`quickfix-gateway` 產生的
      `command_id` / 下游同步邊界的
      `request_id` 維持同值異名，但來源統一為 UUID v7

### 1.2 Kafka wrapper（producer/consumer）

> 現況：`shared-java/` 尚無共用 Kafka runtime module；workspace 只找到 service-local baseline，例如
> `quickfix-gateway` 內的 `MatchingExecutionConsumer`（consumer）。

- [ ] `libs/kafka`：producer wrapper
    - [ ] `KafkaProducer::Publish(topic, key, value, headers, partition_opt)`
    - [ ] 支援設定：`acks=all`、retries、delivery timeout、idempotent producer（若用 librdkafka）
    - [ ] 統一 headers：`event_id`, `traceparent`, `source_service`, `created_at`
- [ ] `libs/kafka`：consumer wrapper
    - [ ] 支援 `subscribe()`（一般服務）與 `assign()`（matching-engine 固定 partition）
    - [ ] `PollLoop(handler)` + graceful shutdown
    - [ ] offset commit 策略可選：sync/async
    - [ ] 指標：poll latency、commit latency、consumer lag（可從 librdkafka 統計/自算）

### 1.3 Postgres 存取層（含 outbox / inbox）

> 現況：`shared-java/` 尚無共用 JDBC / transaction / outbox / processed-events module；目前僅見
> service-local 實作，例如
> `risk-service` 內的 `JdbcSubmissionRepository`、`JdbcOutboxRepository` 與 `TransactionTemplate`
> wiring。

- [ ] `libs/db`：連線池/交易封裝
    - [ ] `Db::BeginTx()` / `Tx::Commit()` / `Tx::Rollback()`
- [ ] `libs/db`：Outbox DAO
    - [ ] `OutboxRepo::Insert(tx, event_id, topic, key, partition_opt, payload, headers_json)`
    - [ ] event payload row 以 INSERT 建立，供 Debezium CDC 從 PostgreSQL outbox 讀取並發布到 Kafka
- [ ] `libs/db`：Idempotency / Processed events DAO
    - [ ] `ProcessedEventsRepo::TryMarkProcessed(tx, consumer_name, event_id)`（成功才繼續）
    - [ ] 或 `InboxRepo::Upsert(event_id, ...)`（依你 schema）

### 1.4 gRPC client utilities（同步依賴韌性）

> 現況：`shared-java/simplematch-config` 已提供 gRPC targets 與 deadline / retry / breaker 參數模型；但
> shared gRPC client
> utility 尚未存在，runtime 目前只見 `quickfix-gateway` 內的 `GrpcRiskSubmissionClient`、
> `ResilientRiskSubmissionClient` 與
> circuit breaker。

- [ ] `libs/grpc`：統一 deadline/timeout 設定 helper
- [ ] `libs/grpc`：重試策略（只對 Get* 類安全讀取啟用）
- [ ] `libs/grpc`：circuit breaker / bulkhead（可先做最簡版：連續失敗 N 次→短暫熔斷）

### 1.5 Observability SDK（OTel + Prometheus）

> 現況：`shared-java/simplematch-config` 已有 OTLP endpoint / Prometheus port 的共用設定模型，但
> shared tracer / metrics
> helper 尚未存在；服務端 `/metrics` baseline 目前只明確落在 `quickfix-gateway` 與 `risk-service`，不是
> repo-level
> observability SDK。

- [ ] `libs/obs`：OpenTelemetry tracer 初始化
    - [ ] 支援 OTLP exporter（HTTP/gRPC）
    - [ ] span attributes helper（把 domain id 放進 span）
- [ ] `libs/obs`：Prometheus metrics exporter
    - [ ] `/metrics` HTTP endpoint（每服務一個）
    - [ ] 常用 metric helpers：counter/gauge/histogram

---

## 2) Protobuf / FIX domain model（契約先行）

> 此節核對的是 proto 契約與 codegen 來源；不代表所有對應的 server handler 或 downstream runtime 都已完成。
>
> 現況：`shared-java/simplematch-contracts` 直接以 repo root 的 `proto/*.proto` 作為 protobuf / gRPC
> codegen source，
> `quickfix-gateway` 與 `risk-service` 已引用生成的 contracts；`account-service` 的 gRPC server
> skeleton 已接上 generated
> stub，但目前仍回 `UNIMPLEMENTED`。

### 2.1 Protobuf：Kafka payload schemas

- [x] `proto/orders.proto`
    - [x] `OrderCommand`（new/cancel；`metadata` 內含 `schema_version` / `event_id` /
      `created_at_unix_ms`，body 含
      `command_id`, `order_id`, `account_id`, `sender_comp_id`, `target_comp_id`, `cl_ord_id`,
      `orig_cl_ord_id`,
      `symbol`, `side`, `quantity`, `price`, `order_type`, `tif`）
    - [x] `OrderValidated` / `OrderRejected`（`metadata` 內含 event metadata；`OrderRejected` 含
      reason code / text）
- [x] `proto/matching.proto`
    - [x] `ExecutionEvent`（fill/cancel/reject；`metadata` 內含 `event_id` / `created_at_unix_ms`，body
      含 `exec_id`,
      `order_id`, `account_id`, `symbol`, `fill_qty`, `fill_px`, `leaves_qty`, `cl_ord_id`,
      `orig_cl_ord_id`,
      `cancel_cl_ord_id`）
- [x] `proto/marketdata.proto`
    - [x] `MarketDataEvent`（trade/quote；`metadata` 內含 `event_id` / `created_at_unix_ms`，payload 為
      `TradeUpdate` 或
      `TopOfBookUpdate`，`symbol` 位於 payload）
- [ ] schema versioning 規範
    - [x] message 加 `schema_version` 或在 headers 帶版本（`common.proto` 的
      `EventMetadata.schema_version` 已落地，
      `risk-service` outbox payload 也會填 `v1`）
    - [ ] 相容性策略：只加欄位、不重用 field number

### 2.2 gRPC APIs（同步入口、查詢與 streaming）

- [x] `proto/account_service.proto`
    - [x] `GetLimits(account_id)` / `GetPositions(account_id)`
    - [x] `Reserve(request_id/order_id, ...)`（冪等）
    - [x] `GetLimitsResponse` 回傳 `available_notional` / `reserved_notional` / `utilized_notional`；
      `ReserveResponse` 回傳
      `ReservationStatus`、`reservation_id` 與 reason fields
- [x] `proto/marketdata_service.proto`
    - [x] `SubscribeMarketData(...)` server-streaming
    - [x] `SubscribePrivateNotifications(...)` server-streaming

### 2.3 FIX ↔ domain mapping 規範

> 現況：`quickfix-gateway` 的 runtime mapping 已存在於 `InboundFixMessageHandler` / `WalRecord`，可把
> `NewOrderSingle` /
> `OrderCancelRequest` 正規化成 `OrderCommand`；`persistence.orders` 已對
> `(sender_comp_id, target_comp_id, cl_ord_id)` 建立
> UNIQUE，`risk_submissions` 已以
> `(sender_comp_id, target_comp_id, trading_day, command_type, cl_ord_id)` 做 ingress
> dedup。contract、gateway、risk-service 與 projection schema 的 FIX-facing naming 已對齊；`order_id`
> 目前仍由 gateway 以
> `O-<ClOrdID>` 派生，尚未演進為 opaque internal order identity。

- [ ] FIX 欄位到 `OrderCommand` 的 mapping 文件化（欄位表）
- [ ] `ClOrdID` 去重 key 定義：`(SenderCompID, TargetCompID, TradingDay, ClOrdID)`

---

## 3) Kafka topics（非同步保序路徑）與資料庫 schema

### 3.1 Topics 建立與設定

> 現況：repo 已在 `config/simplematch*.json`、`shared-java/simplematch-config` 與 README 固定 topic
> 名稱與建議 partition
> catalog，並提供 `risk-service` 的 Debezium connector 範本；但尚無 topic provisioning script /
> manifest，也尚未把
> broker-level `replication.factor` / `min.insync.replicas` / `unclean leader election` 轉成可執行的
> infra 定義。`acks=all`
> 目前只見於 Spring Kafka baseline config（`quickfix-gateway` 與 `risk-service`），不是完整 data-plane
> rollout。

- [ ] 建立 topics：`orders.validated`, `matching.executions`, `marketdata.events`, `audit.events`
  （README 資料流圖預設存在；可用
  `ENABLE_AUDIT_EVENTS` 關閉）
- [ ] topic 設定（最小）：
    - [ ] `replication.factor=3`（若環境支援）
    - [ ] `min.insync.replicas=2`
    - [ ] producer `acks=all`
    - [ ] 禁用 unclean leader election（broker 層）
- [ ] partition 策略：
    - [ ] `matching.executions`：key = `symbol`（至少保證同 symbol 保序）
    - [x] `orders.validated`：`risk-service` 已依 published snapshot 計算 `kafka_partition_id`，寫入
      outbox 與
      `OrderValidated.routing_partition`，並由 Debezium connector 明確指定 partition
    - [ ] follow-up：matching-engine 若也需要本地 routing 判斷，再載入同一份 published snapshot
    - [ ] follow-up：若要盤前人工 override / 版本化管理，再升級成 `routing_snapshots` +
      `symbol_routing_entries`

### 3.2 Postgres schema（最小可跑）

> 現況：`risk-service` / `account-service` / `persistence` 各自只有一個從空資料庫安裝的 typed V1
> migration，並由
> clean-install、no-op 和 constraint tests 驗證；欄位字典、索引審查與 rollback checkpoint
> 見 [Phase 4 data dictionary](docs/phase-4-data-dictionary.md)。routing / config tables
> 仍完全未落地。owner-schema-first
> 也已被寫入 `docs/database-architecture.md` 與 repo root `AGENTS.md`，作為後續新增持久化服務的
> guardrail，而不是已結束的一次性
> rollout。

- [x] 架構決策已固定：採 **單一 PostgreSQL instance + 每服務各自 schema owner**；細節、實作觸點與
  checklist 見 [docs/database-architecture.md](docs/database-architecture.md)
- [ ] follow-up：後續若新增新的持久化服務，或讓既有服務加入新的 JDBC runtime / connector 觸點，必須從第一個
  migration 起就採 schema-qualified owner model

- [x] `risk-service` local schema
    - [x] `risk_submissions`（同步 ingress journal；
      `UNIQUE(sender_comp_id, target_comp_id, trading_day, command_type, cl_ord_id)`、
      `UNIQUE(outbox_event_id)`）
    - [x] `outbox`（append-only event row；`event_id UNIQUE`，供 Debezium CDC 讀取；已含
      `kafka_partition_id`）
- [x] `account-service` authority schema
    - [x] `account_limits`（帳戶/商品/日額度 bucket；含 `limit_total_notional`, `reserved_notional`,
      `utilized_notional`,
      `available_notional`）
    - [x] `account_positions`（per-account, per-symbol 持倉快照；`UNIQUE(account_id, symbol)`）
    - [x] `account_reservations`（open orders 預扣狀態；目前 schema 對 `reservation_id`、`request_id`、
      `order_id` 都設 UNIQUE）
- [x] `account-service` migration 已顯式落在 `account_service` schema，migration test 也已在 owner
  schema 下驗證
- [x] projection / read-model schema
    - [x] `orders`（projection/read-model；含 `sender_comp_id`, `target_comp_id`, `cl_ord_id/ClOrdID`
      的 UNIQUE）
        - 現況：此約束已與 gateway/risk-service 的 FIX-facing identity naming 對齊；projection 仍以
          `order_id` 作為權威狀態主鍵。
    - [x] `executions`（`UNIQUE(order_id, exec_id)`）
    - [x] `inbox`（`(consumer_name, event_id)` PK）
- [x] `persistence` migration 已顯式落在 `persistence` schema，migration test 也已在 owner schema 下驗證
- [ ] routing / config schema
    - [ ] `symbol_routing`（symbol→shard/partition；可選但建議；MVP 可先不建表）
    - [ ] `routing_snapshots`（版本 / 交易日 / 發布狀態）
    - [ ] `symbol_routing_entries`（snapshot 下的 symbol 映射；partition 決策由 `routing_bucket` /
      `kafka_partition_id`
      欄位表達）

### 3.3 Flyway rollout

- [x] `simplematch.flyway-service` 已支援 per-service schema 設定（`schemaName` + service-scoped
  property / env override），並把 Flyway `defaultSchema` / `schemas` 對齊到 owner schema
- [x] `risk-service` / `account-service` / `persistence` build script 已明確宣告 owner schema

- [x] `risk-service` 已接入 `simplematch.flyway-service`，並以 typed V1 migration 管理
  `risk_submissions` + binary local
  `outbox`（含 `kafka_partition_id`）
- [x] `account-service` 接入 `simplematch.flyway-service`
- [x] `account-service` 初始 migration：`account_limits`, `account_positions`, `account_reservations`
- [x] `persistence` 接入 `simplematch.flyway-service`
- [x] `persistence` typed V1 migration：`orders`, `executions`, `inbox`
- [ ] `matching-engine` / WAL loader-ingester 決定 schema owner 後接入 Flyway
    - [ ] 若 `matching-engine` 自有 PostgreSQL / outbox，建立 service-local result journal / `outbox`
    - [ ] 若 `executions` 最終由 `persistence` 單獨落地，避免在 `matching-engine` 端重複定義
      read-model tables
- [ ] `symbol_routing` schema owner 決策與初始 migration（MVP 先用 config/snapshot；若未來落表，優先收斂到單一
  `reference-data` / `routing-config` owner）
- [ ] `reference-data-service` / `routing-config-service` owner 決策（Phase 1 不建 service；先用
  published snapshot）
- [ ] 進入 Phase 2 時，再接入 Flyway 並建立 `routing_snapshots` + `symbol_routing_entries`

### 3.4 Routing rollout（依三階段）

- [x] Phase 1 基礎：`risk-service` 已以 published routing snapshot（config/file）計算 routing；熱路徑不查
  DB
    - [x] `simplematch.routing.snapshotPath` 已接入 risk-service；shared config model 的 fallback
      預設載入 classpath sample，而 repo 內 checked-in `config/simplematch*.json` 目前指向
      `config/routing/orders-validated.snapshot.json`
      ，也可覆寫到外部 published snapshot
    - [x] `quickfix-gateway` 維持不變，仍只送 `OrderCommand` 到 `risk-service`
    - [x] repo 已提供 Debezium connector 範本，把 outbox `kafka_partition_id` 套用到 Kafka partition
    - [ ] matching-engine 若要自行判斷 routing，仍需載入同一份 published snapshot
    - [ ] 盤前 reload / 盤中不變更的操作流程仍待制度化
- [ ] Phase 2：owner schema
    - [ ] 優先採 `reference-data-service`
    - [ ] 若只做較窄範圍，可退而採 `routing-config-service`
    - [ ] 單一 owner，多個 readers
- [ ] Phase 3：admin API / publish flow
    - [ ] 管理 draft routing
    - [ ] publish 某個交易日的 snapshot
    - [ ] data-plane 服務只載入 published snapshot

### 3.5 Kafka key/partition：business key 與 explicit partition 分離

- [x] `risk-service` 已以 published routing snapshot 計算 `kafka_partition_id`，保留 `message_key`
  作為業務鍵，並明確指定
  `orders.validated` partition

---

## 4) 服務任務拆解（按微服務）

## 4.1 `quickfix-gateway`（QuickFix/J + Java/Spring，FIX 4.4，Acceptor）

### 4.1.1 FIX session / transport

- [x] QuickFIX acceptor config：`config/quickfix/acceptor.cfg`
    - [x] 最小必要欄位（示意）：`BeginString=FIX.4.4`, `ConnectionType=acceptor`, `SenderCompID`,
      `TargetCompID`,
      `SocketAcceptPort`, `HeartBtInt`
    - [x] 啟用/管理 dictionary：`UseDataDictionary=Y`, `DataDictionary=fix-spec/FIX44.xml`
- [x] 支援 logon/logout、heartbeat、sequence reset baseline（QuickFIX/J callbacks + `HeartBtInt` /
  `ResetOn*` config 已接入）
- [x] inbound/outbound message persistence（為了 resend/合規稽核；file-based baseline 已接入）
    - [x] `MessageStoreFactory`：`FileStoreFactory`（起步）→ 需要時改 DB store
    - [x] `LogFactory`：`FileLogFactory`
    - [ ] resend/重送驗證：斷線重連、`ResendRequest`、gap fill、`PossDupFlag`/`OrigSendingTime`

### 4.1.2 入口 ACK（risk-service persistence-first）

- [x] 主路徑：gateway 以同步 gRPC 將請求送到 `risk-service`
- [x] 第一個成功 FIX ack 僅在 `risk-service` transaction commit 成功後回覆 `PendingNew/Accepted`
- [x] 若 `risk-service` 同步判定失敗，gateway 直接回 `Rejected`
- [x] `orders.commands` compatibility publish 已退休；WAL replay 直接走 typed v2 Risk command，且沒有 runtime 或診斷開關可重新啟用該 topic
- [ ] 若保留 WAL：
    - [x] `WalAppender::Append(record)` 作為本地恢復 / 稽核輔助
    - [ ] 明確標記 WAL 不再是主 ack 錨點
    - [ ] 規劃 crash recovery / diagnostics 使用方式，不與主提交語意混淆

### 4.1.3 FIX → Domain command

- [x] `FixParser::ParseNewOrderSingle()` → `OrderCommand{type=NEW}`
- [x] `FixParser::ParseCancelRequest()` → `OrderCommand{type=CANCEL}`
- [x] 正規化欄位：symbol、side、qty、price、order_type、tif
- [x] `command_id` 生成策略收斂：gateway ingress 改以 `UuidV7()` 產生 `OrderCommand.command_id`
  （第一階段先維持 proto / gRPC / DB 欄位型別為字串，且不與 `request_id` / `command_id` rename 綁在同一個
  slice）
- [x] `risk-service` internal outbox UUID 收斂：`risk_service.outbox.event_id` 與
  `risk_service.risk_submissions.outbox_event_id` 已在 typed Flyway `V1` 定義為 PostgreSQL `UUID`
  ，repository 端使用 UUID binding；`request_id` / `command_id` 仍暫留在 string contract slice
- [x] `risk-service` validator 先行攔截 oversized `request_id` / `order_id`：`SubmissionValidator`
  已將這兩條路徑從 DB-driven rollback 改為應用層 rejection，並同步更新 unit/integration tests
- [x] `risk-service` validator 已補齊 non-UUID `request_id` ingress validation：`SubmissionValidator`
  現在會對非 UUID
  `request_id` 回 `INVALID_REQUEST_ID` rejection，且 `risk-service` repo-local tests 已把 legacy
  `cmd-*` fixture 收斂為 UUID-shaped command ids
- [x] `risk-service` validator 先行攔截缺失與 oversized `sender_comp_id` / `target_comp_id`：對
  business-key session identity 改為應用層 rejection，且 rejected persistence path 以 deterministic
  digest surrogate 保存 dedup 所需欄位，避免 DB 長度例外
- [x] `risk-service` oversized `symbol` 路徑改為應用層 rejection：validator 先回 `OVERSIZED_SYMBOL`
  ，outbox
  `message_key` / partition fallback 改以 safe key（優先 `order_id`，否則 default partition）避免
  rejected outbox 再次因 symbol 長度寫失敗
- [x] `cl_ord_id` / `orig_cl_ord_id` 的應用層長度驗證已落地：`risk-service` 的 typed Flyway `V1` 包含
  raw-vs-persisted strategy 所需的 `raw_cl_ord_id` / `raw_orig_cl_ord_id`；gRPC response 保留 raw 值，
  `risk_submissions.cl_ord_id` 繼續承擔 dedup/business-key persisted 值
    - [x] surrogate redesign：typed Flyway `V1` 直接定義 64-char SHA-256 rejected business-key
      surrogate 與
      `business_key_surrogated` 旗標，避免和 accepted raw key 撞值；詳見
      `docs/field-typing-phase2-gates.md`
    - [x] follow-up gate：重建 schema 不含 pre-reset legacy rows；任何保留舊資料的環境必須在切換前完成
      live DB profile/backfill，詳見 `docs/field-typing-phase2-gates.md`
- [x] `quickfix-gateway` ingress precheck 已在 WAL 前攔截 gateway-local invalid FIX：
  `InboundFixMessageHandler` 先檢查
  `sender_comp_id` / `target_comp_id` / `cl_ord_id` / `orig_cl_ord_id` 的缺失與 64 字元上限，並
  在 semantic command 建構時拒絕缺少欄位、非法 side、非正數 quantity、非法 order type / TIF
  與不合法 price；直接回 FIX reject，不再把 invalid 請求送進 WAL、`risk-service` 或
  compatibility publish。Risk Admission 的 account、market、routing、idempotency 與 reservation
  policy 仍由下游負責。
- [ ] `order_id` canonical identity 決策與收斂（目前 gateway 以 `O-<ClOrdID>` 派生並對外回報；若要演進成
  opaque internal id，需同步調整 FIX `OrderID(37)` 映射與 cross-service 契約）
    - [x] Phase 2 repo-local gate finding：現行 `O-<ClOrdID>` derivation 代表 `order_id` 既不是
      UUID，也可能在
      `ClOrdID=64` 時長到 66 chars；因此目前不能直接收斂到 native UUID 或 `VARCHAR(64)`，詳見
      `docs/field-typing-phase2-gates.md`
    - [x] Phase 2 repo-local design finding：UUIDv7 適合作為 opaque internal `order_id` 候選，但不能直接取代
      `orderIdFor(clOrdId)`；gateway 還需要把 submit / duplicate replay / cancel lookup 都改成依
      canonical `order_id` 與 FIX business-key lookup 運作，詳見 `docs/field-typing-phase2-gates.md`
- [ ] 市價單保護價（若採用）：`ComputeProtectionLimitPx()`

### 4.1.4 去重（FIX + 業務層）

- [ ] FIX session 層：配合 QuickFIX 行為處理 `ResendRequest`, `PossDupFlag`, `OrigSendingTime`（並確保
  message store 能支援重送）
    - [x] QuickFIX/J session callback 與 file store/log baseline 已接入
- [ ] 業務層：FIX `ClOrdID` idempotency（目標 key =
  `(SenderCompID, TargetCompID, TradingDay, ClOrdID)`）
    - [ ] `DedupRepo::FindOrCreateByClOrdId(session, trading_day, cl_ord_id)`
    - [ ] 重送一致：若 payload 相同回同結果；不同回 reject
    - [x] 讓 ingress journal 具備 FIX business identity 所需欄位：`risk_submissions` 補
      `sender_comp_id` /
      `target_comp_id` / `trading_day`，並明確定義其來源（目前
      `trading_day = gateway created_at_unix_ms` 的 UTC 日期）
    - [x] 將目前 runtime dedup 與目標 FIX business dedup 的對齊路徑文件化：在 gateway /
      risk-service / persistence 三處明確標註目前 key 與 FIX-facing naming
    - 現況：gateway 目前攜帶 `sender_comp_id` / `target_comp_id`；projection `orders` 已有
      `UNIQUE(sender_comp_id, target_comp_id, cl_ord_id)`；`risk_submissions` 已以
      `(sender_comp_id, target_comp_id, trading_day, command_type, cl_ord_id)` 做 ingress dedup。

### 4.1.5 同步送交 `risk-service`（主路徑）

- [x] gRPC client：`RiskService::SubmitOrder()` / `CancelOrder()`
- [x] request 內必須帶穩定、可重送的 client-supplied id：`cl_ord_id` / `ClOrdID`
- [x] bounded retry：僅限暫態 transport 錯誤，且重試時必須沿用同一個 ingress business identity（至少同一
  `sender_comp_id` /
  `target_comp_id` / `trading_day` / `command_type` / `cl_ord_id`）
- [x] deadline / breaker / connection reuse 落地
    - [x] deadline / connection reuse 已落地（blocking stub deadline + shared managed channel）
    - [x] breaker 已落地（consecutive-failure open + cooldown half-open probe）

### 4.1.6 消費 `matching.executions` → FIX 回報

- [x] Kafka consumer：`matching.executions`
- [x] `ExecutionEvent` → FIX `ExecutionReport`（成交/狀態更新/撤單成功）
- [x] 撤單被拒（FIX 慣例）：`ExecutionEvent` → FIX `OrderCancelReject (35=9)`
    - [x] 必填：`ClOrdID(11)`（撤單請求）、`OrigClOrdID(41)`（原委託）、`OrdStatus(39)`（原委託狀態）
    - [x] 原因：`CxlRejReason(102)` + `Text(58)`（若有）、`CxlRejResponseTo(434)=1`
- [x] 去重：目前以 `exec_id` 去重（非 `(order_id, exec_id)`）
- [ ] session 斷線/重連：可重送回報（FIX resend）

### 4.1.7 endpoints

- [x] `/healthz` `/readyz` `/metrics`

### 4.1.8 （選用）`quickfix-gateway` ↔ `account-service`（session 身分/權限映射）

> 對齊 README：此連線用途是 FIX session 身分 ↔ `account_id` 映射、帳戶/權限驗證。
> 建議僅用於 session 建立/定期刷新，避免進入每筆下單的極短 ACK 路徑。

- [ ] gRPC client：`AccountService::ResolveSessionIdentity()`（或以 `GetAccountProfile()` 等形式）

### 4.1.9 `quickfix-gateway` session-aware scale-out baseline

- [x] 建立 `docs/quickfix-gateway-session-scale-plan.md`
- [x] 新增 `quickfixGateway.ownerId` 配置與 owner-aware consumer group 預設
- [x] `quickfix-gateway` StatefulSet / per-owner Service / PVC manifests
- [x] startup recovery + readiness gating
- [ ] shared state / owner lease / fencing
- [ ] standby promotion / route transfer
- [ ] 在 Logon / Session 建立時：取得 `account_id` / 權限/風控等級（若需要）並快取於 session context
- [ ] failure policy：連不上 `account-service` 時拒絕建立 session（fail-closed）或降級（依需求選一個）
- [ ] deadline / breaker / bulkhead 落地（與 `risk-service` 同一套最低規範）

---

## 4.2 `risk-service`

> 交叉驗證結果：`risk-service` 已完成 v2 durable admission journal、pending saga、account reservation
> adapter、accepted/rejected binary outbox、recovery、CDC-lag backpressure 與 v1 compatibility
> adapter；既有 v1 ingress
> 仍保留。交易時段與完整 IOC/FOK 規則矩陣屬後續 Phase 8 範圍。

### 4.2.1 gRPC server：primary ingress

- [x] 定義 `SubmitOrder` / `CancelOrder` gRPC API
- [x] request 需攜帶 `cl_ord_id` / `ClOrdID`、`order_id`、必要 FIX 正規化欄位
- [x] 同步回覆語意：只有在本地 transaction commit 成功後才回成功
- [x] 目前版 ingress 唯一鍵冪等：相同 current key 重送時回同一筆結果，不可重複建立訂單
    - [x] PostgreSQL 唯一鍵 / transaction 版已完成
    - 現況：`risk-service` 當前 authoritative key 已是
      `(sender_comp_id, target_comp_id, trading_day, command_type, cl_ord_id)`；舊的
      `idempotency_key` transitional 欄位與 runtime 生成路徑都已移除，FIX-facing naming 也已對齊到最終業務表述。
    - [x] `risk_submissions` 補 `sender_comp_id` / `target_comp_id` / `trading_day` 欄位，讓 ingress
      journal 能直接表達 FIX business identity
        - [x] 現況：`sender_comp_id` / `target_comp_id` 已落庫；`trading_day` 已以 gateway
          `created_at_unix_ms` 的 UTC 日期落庫
    - [x] 將 ingress UNIQUE 約束由單一欄位演進為業務欄位組合（目前為
      `(sender_comp_id, target_comp_id, trading_day, command_type, cl_ord_id)`）
    - [x] 移除 `idempotency_key` transitional 欄位、runtime generator 與 outbox event-id 對它的依賴

### 4.2.2 規則檢核

- [ ] 基本格式、交易時段、symbol 合法
    - [x] 基本必填格式檢核已落地
    - [ ] 交易時段 / symbol registry 驗證尚未完成
- [ ] 支援 LIMIT/MARKET × ROD/IOC/FOK（規則不合法直接 rejected）
    - [x] LIMIT / MARKET 的價格必填差異已驗證（limit 缺 price reject；market 可無 price）
    - [ ] IOC / FOK 與非法組合拒絕語義尚未完成
- [ ] 市價單保護價規則（若採用）

### 4.2.3 交易額度 / reservation（同步 gRPC 依賴）

> 現況：`risk-service` 已透過 `GrpcAccountReservationClient` 呼叫 account reservation v1
> adapter；account authority 已提供
> limits、positions、release、fill 與 lifecycle outbox。

- [ ] gRPC client：`AccountService::GetLimits/GetPositions`（快取可選；回傳需對齊 `account_limits` /
  `account_positions`）
- [x] `Reserve(order_id/request_id, ...)`（冪等；由 `account-service` 更新 `account_reservations`）
    - [x] 目前先持久化 accepted reservation row 並以 `request_id` 重送回同結果
    - [ ] `account_limits.available/reserved` 同步扣減仍待補
- [ ] deadline / retry / breaker / bulkhead 落地

### 4.2.4 產出 `orders.validated` / `orders.rejected`

- [x] Outbox pattern：同步請求先完成本地 transaction，並寫入待發布事件到 outbox
    - [x] `risk-service` service code 已切換為 Debezium CDC 導向：服務內只寫 append-only outbox，不再
      app 內 publish
    - [x] 清理 app 內 built-in relay 與其專屬 publish / lease state
    - [x] 補正式 versioned PostgreSQL migration，既有 schema 明確移除 relay 專屬欄位
- [x] `risk_submissions` 作為 local ingress journal，與 outbox event 一對一關聯
- [x] Kafka key/partition：與 commands 同套路由（symbol）

### 4.2.5 endpoints

- [ ] `/healthz` `/readyz` `/metrics`
    - [x] 預設 actuator 已 expose `health` / `info` / `metrics`
    - [ ] 自訂 `/healthz` `/readyz` alias 尚未補

---

## 4.3 `matching-engine`

> 現況：workspace 目前沒有 `services/matching-engine/` 或對應 native module；README / docs 只保留 C++
> matching-engine 的架構與
> rollout 規劃，因此以下項目都仍屬未開始。

### 4.3.1 消費 `orders.validated`

- [ ] 部署為 StatefulSet 思維（instance id 固定）
- [ ] consumer 方式：`assign()` 固定 partition → matching-engine-N
- [ ] offset 管理：處理成功後才 commit

### 4.3.2 撮合核心

- [ ] `OrderBook`
    - [ ] `AddOrder(order)`
    - [ ] `CancelOrder(order_id)`
    - [ ] `Match()`（產生 fills）
- [ ] 支援 IOC/FOK：
    - [ ] IOC：未成交 leaves 產生取消事件
    - [ ] FOK：深度不足整筆取消（不產生部分成交）
- [ ] Idempotency：`command_id` / `(order_id, command_seq)` 防重

### 4.3.3 撮合結果 durability / publish

- [ ] 本地 WAL：`MatchResult` / 成交結果先強制落盤
- [ ] WAL loader / ingester：以單一 PostgreSQL transaction 寫入成交結果 + outbox（topic=
  `matching.executions`）
- [ ] Debezium CDC：由 PostgreSQL logical decoding / WAL 將 `matching.executions` 發布到 Kafka

### 4.3.4 主備接手（進階，可後做）

- [ ] fencing：每 shard/partition 一把鎖（etcd/Consul/ZK/PG advisory lock 任選）
- [ ] standby warm-up：跟讀重建 orderbook 但不產出
- [ ] takeover：取得鎖 + 對齊 offset + 開始產出

### 4.3.5 endpoints

- [ ] `/healthz` `/readyz` `/metrics`

---

## 4.4 `persistence`（sink / projection builder）

> Spring Boot module、owner schema、`orders` / `executions` / `inbox` tables 已存在；交叉驗證結果顯示目前只有
> Spring Boot
> entrypoint + Flyway migration/test baseline，build 尚未引入 `spring-kafka` 或 Redis runtime，因此仍缺
> Kafka
> consumer、projection writer 與完整 actuator 暴露。

### 4.4.1 Kafka consumer：`matching.executions`

- [ ] 反序列化 ExecutionEvent
- [ ] Idempotency：`InboxRepo::TryMarkProcessed(consumer=persistence, event_id/exec_id)`
    - [x] `inbox` table schema 已存在
    - [x] raw column audit：目前 `persistence` 只有 projection / idempotency schema，尚未有會把 FIX
      identity 從 DB 回吐成同步 contract 的 runtime；Phase 1 保持 bounded persisted 欄位即可，不需要複製
      `risk-service` 的 `raw_*` 欄位模式
    - [x] Phase 2 repo-local gate finding：`inbox.event_id` 已在 typed V1 為 UUID；
      `orders.last_command_id` 仍缺 workspace 內的 writer runtime 證據，詳見
      `docs/field-typing-phase2-gates.md`

### 4.4.2 batch commit

- [ ] buffer 策略：每 N 筆或每 T ms
- [ ] 單 tx 內：insert executions + update orders（若有）+ mark processed
- [ ] commit 成功後才 commit Kafka offset

### 4.4.3 Redis read model（查詢首選，建議做）

- [ ] 定義 Redis key schema（與 README 對齊）
    - [ ] `order:{order_id}`
    - [ ] `acct:{account_id}:open_orders`
    - [ ] `sym:{symbol}:top`
- [ ] 每次處理 `matching.executions` 時更新 Redis（冪等 upsert）
- [ ] value 內附上投影水位（例如 `last_event_ts` 或 `topic/partition/offset`）方便 debug
- [ ] 設計重建流程：可從 `matching.executions` replay 重建 Redis

### 4.4.4 產出 `audit.events`

> 若你要把 `persistence` 做成「落地 + 稽核事件流輸出」的服務（README 的資料流圖是這個預設），就需要能穩定產生
> `audit.events`。

- [ ] 定義 `AuditEvent` schema（可先用 `proto/audit.proto`，或沿用既有 `ExecutionEvent` 加 audit
  metadata）
- [ ] 從 `matching.executions` 映射產生 `AuditEvent`（例如：原事件摘要 + 落地後的 DB 主鍵/水位）
- [ ] 可靠性：若 `audit.events` 開啟，使用 outbox（同 DB tx 內 insert outbox）→ Debezium CDC → Kafka
- [ ] 去重鍵：`audit_event_id`（可沿用上游 `event_id` 或 `exec_id` 衍生）
- [ ] feature flag：`ENABLE_AUDIT_EVENTS=true|false`（MVP 可先關閉）

### 4.4.5 endpoints

- [ ] `/healthz` `/readyz` `/metrics`
    - [x] Spring Boot app 與 `health` / `info` actuator exposure 已存在
    - [ ] `metrics` exposure 與 root alias 尚未補

> 註：若 `persistence` 要產生衍生事件流（例如 audit/events），才需要在 `persistence` 端引入 Outbox。

---

## 4.5 `marketdata-publisher`

> 現況：`services/marketdata-publisher/` 已提供每日市場參考 snapshot publisher；
> `proto/marketdata.proto` 的 public trade/quote event publisher 仍未開始。

> Phase 5 已新增 `services/marketdata-publisher/` 作為每日市場參考資料的離線匯入、
> immutable snapshot、activation 與 transactional outbox owner。它不消費 runtime
> execution，也不發布 public trade/quote；下列 market-data event 工作仍屬後續 phase。

- [ ] v1：consume `matching.executions`
- [ ] v1：先產出 `marketdata.events` 的 `TradeUpdate`
- [ ] quote / `TopOfBookUpdate` 後補：待有穩定 order book / quote projection 後再加，避免先造
  synthetic quote
- [ ] 決定發布可靠性：
    - [ ] 若市場資料也要求 durability，沿用 outbox + CDC
    - [ ] 若 MVP 接受較弱保證，先明確記錄 direct publish 的限制
- [ ] 去重策略（可選）：event_id / 時間窗合併
- [ ] `/healthz` `/readyz` `/metrics`

---

## 4.6 `marketdata-streamer`（external gRPC streaming）

> 現況：workspace 未見 `services/marketdata-streamer/`；目前只有 `proto/marketdata.proto` /
> `proto/marketdata_service.proto` 契約與 README 架構說明，尚無 runtime consumer / gRPC server 實作。

### 4.6.1 Kafka consumers

- [ ] v1：consume `marketdata.events`
- [ ] private notifications 走分流：`matching.executions` 另做 private stream，不與公開 market data
  混在同一條 stream

### 4.6.2 gRPC server（v1）

- [ ] `SubscribeMarketData(request) -> stream MarketDataEvent`
- [ ] v1 先做 steady-state delta stream，不要求 per-client replay Kafka opening burst
- [ ] backpressure / flow control（最小：每連線 buffer 上限，超過就斷線或降級）

### 4.6.3 snapshot / delta / resync（follow-up）

- [ ] snapshot 由 Redis read model 提供（例如 `sym:{symbol}:top`），不要由 streamer 臨時掃 Kafka 組裝
- [ ] client bootstrap：先拿 snapshot，再續接 delta stream
- [ ] resync：lag 過大 / 斷線恢復時，重新拿 snapshot，不重播整段 Kafka 開盤事件
- [ ] 協定演進：補 `marketdata.proto` / `marketdata_service.proto` 的 snapshot-oriented message shape
- [ ] 協定演進：補 sequence / watermark / resume token，避免 client 直接理解 Kafka offset

### 4.6.4 AuthN/AuthZ

- [ ] TLS
- [ ] mTLS 或 JWT（選一個最小可跑）
- [ ] private stream：account_id 由憑證/claims 映射，不接受 client 任意指定

### 4.6.5 endpoints

- [ ] `/healthz` `/readyz` `/metrics`

---

## 4.7 `account-service`

> `proto/account_service.proto`、Spring Boot module、gRPC server 與 authority transaction service
> 已存在；Reserve、GetLimits、GetPositions、ReleaseReservation、ApplyFill 均接到權威資料庫與 lifecycle
> outbox。以下勾選以實際可用功能與已落地
> schema 為準。

### 4.7.1 gRPC server（同步查詢 / reservation）

- [x] `GetLimits(account_id)`
- [x] `GetPositions(account_id)`
- [x] `Reserve(request_id/order_id, ...)`（冪等）
    - [x] 唯一鍵：目前 schema 對 `reservation_id = order_id`、`request_id`、`order_id` 都有 UNIQUE
    - [x] 重送回同結果
    - [x] ingress validation：`AccountGrpcService` 現在會在 JDBC 前拒絕非 UUID 的 `request_id`，並拒絕超過
      255 chars 的
      `request_id` / `order_id`，以 gRPC `INVALID_ARGUMENT` 回應而不是 DB-driven `INTERNAL`
    - [x] raw column audit：`account_reservations` 目前只需 replay bounded 的 `request_id` /
      `order_id` /
      `reservation_id` 到 gRPC response，沒有像 `risk-service` 那樣「持久化 surrogate、回應 raw FIX
      identity」的需求；Phase 1 只需 ingress validation + bounded persisted 欄位，不需要額外 `raw_*` 欄位
    - [x] Phase 2 repo-local gate finding：`reservation_id` 目前對齊 `order_id`，所以跟著 `order_id`
      一起卡在「非 UUID、且可能 >64 chars」的 blocker 上；詳見 `docs/field-typing-phase2-gates.md`

### 4.7.2 Kafka consumer（建議）：`matching.executions`

- [x] fill：`ApplyFill(exec_id, order_id, ...)`
- [x] cancel/IOC leaves：`ReleaseReservation(reservation_id/order_id, ...)`
- [x] Idempotency：`exec_id` / `event_id`

### 4.7.3 交易額度資料模型（不含現金版）

> PostgreSQL schema / migration 已落地；這裡的勾選代表 tables / columns 已存在，不代表 reserve /
> release / apply-fill
> runtime 已完成。

- [x] `account_limits`（帳戶/商品/日額度 bucket；含 `reserved_notional` / `utilized_notional` /
  `available_notional`）
- [x] `account_positions`（per-account, per-symbol 持倉快照）
- [x] `account_reservations`（open orders 預扣狀態；目前 schema 對 `reservation_id`、`request_id`、
  `order_id` 都設 UNIQUE）
- [x] `utilized` 先作為 `account_limits.utilized_notional` materialized 欄位；若後續 exposure 邏輯獨立再拆表
- [x] `available = limit_total - reserved - utilized`

### Phase 6/7 completion evidence

- [x] [Phase 6 account reservation authority](docs/phase-6-account-reservation-authority.md)
- [x] [Phase 7 durable risk admission](docs/phase-7-durable-risk-admission.md)

### 4.7.4 endpoints

- [ ] `/healthz` `/readyz` `/metrics`
    - [x] Spring Boot app 與 `health` / `info` actuator exposure 已存在
    - [ ] `metrics` exposure 與 root alias 尚未補

---

## 4.8 `query-service`（內網查詢 API；Redis-first）

> 目的：把查詢流量從權威服務（例如 `account-service` / Postgres）分離，提供低延遲 read API。
> 對齊 README：`query-service` 建議只走 gRPC/HTTP 讀 Postgres/Redis projections， **不要直接讀
Kafka**。
> 現況：workspace 未見 `services/query-service/`；README 目前僅保留 Redis-first query service
> 的架構規劃，尚無實際
> gRPC/HTTP、Redis 或 Postgres 查詢模組。

### 4.8.1 讀路徑（Redis-first + fallback）

- [ ] 對外（內網）提供 gRPC/HTTP 查詢端點（選一種起步即可）
- [ ] 查詢優先讀 Redis；Redis miss / 重建中才回落 Postgres
- [ ] 回傳可選欄位：附帶投影水位（讓 caller 知道資料新鮮度）

### 4.8.2 API（最小）

- [ ] `GetOrder(order_id)`（查 `order:{order_id}`）
- [ ] `ListOpenOrders(account_id)`（查 `acct:{account_id}:open_orders`）
- [ ] `GetSymbolTop(symbol)`（查 `sym:{symbol}:top`）

### 4.8.3 可靠性/韌性

- [ ] gRPC deadline（若用 gRPC）
- [ ] bulkhead：Redis / Postgres 連線池隔離
- [ ] 降級策略：Redis 連不上時快速 fail-fast 或回落 DB（依需求）

### 4.8.4 endpoints

- [ ] `/healthz` `/readyz` `/metrics`

---

## 5) Debezium / CDC（主線 Outbox 發佈）

> 現況：Risk、Account Authority、Market Reference 都有 service-owned binary outbox connector
> contract、compose/K8s templates 與可執行的 Docker CDC harness。Matching Engine、persistence、
> query-service、streamer 尚未成為 authoritative outbox owners，因此尚未建立 connector。

- [x] `risk-service`：已提供 Debezium connector 範本，將 PostgreSQL outbox 變更發布到 Kafka，並以
  `kafka_partition_id` 指定 partition
    - [x] `deploy/compose/risk-service-outbox-connector.json`、
      `deploy/k8s/risk-service-outbox-connector-configmap.yaml`
      與 `deploy/compose/apply-risk-service-outbox-connector.sh` 已存在
- [x] `account-service`：使用同一份 binary Outbox Event Router contract，且只讀
  `account_service.outbox`
- [x] `marketdata-publisher`：使用同一份 binary Outbox Event Router contract，且只讀
  `marketdata_publisher.outbox`
- [x] local CDC harness：驗證 payload bytes、key、headers、timestamp、explicit partition、pause/
  resume retention 與 connector recovery（`scripts/run-outbox-cdc-contract-check.sh`）
- [ ] `matching-engine`：配置 Debezium connector，將 PostgreSQL outbox 變更發布到 Kafka
    - [ ] 尚未成為 authoritative outbox owner；目前只完成 native ingress seam
- [ ] 若 `persistence` 後續產生 `audit.events`，再為其 outbox DB 配置 Debezium connector
    - [ ] workspace 未找到 `persistence` outbox / `audit.events` connector config
- [x] topic routing：outbox.topic 欄位 → Kafka topic
- [x] at-least-once 期望：consumer 冪等必做
    - [x] `quickfix-gateway` 消費 `matching.executions` 時已以 `exec_id` 做 in-memory 去重
    - [x] `persistence` schema 已有 `inbox` table
    - [x] `account-service` execution lifecycle 與 `risk-service` routing-policy projection 使用
      critical retry/quarantine boundary
    - [x] non-critical QuickFIX projection 使用 delayed retry/DLQ boundary
- [x] delivery metrics contract：Micrometer bridge 暴露 connector lag、outbox age、consumer lag、
  duplicate、retry、quarantine、dead-letter 的穩定 metric names/labels
- [x] Phase 9 completion evidence：issues
  [#92](https://github.com/WenHsuanYu/SimpleMatch/issues/92)–
  [#99](https://github.com/WenHsuanYu/SimpleMatch/issues/99) 使用 focused tests、完整 Java tests、
  QuickFIX certification、native CTest、blocking static analysis 與 Docker CDC harness 驗證；
  cleanup 僅由 durable CDC watermark 與 replay/investigation retention boundary 授權。

---

## 6) Kubernetes（kind）部署與設定

> 現況：`deploy/k8s/` 目前只有 `quickfix-gateway` continuity / session-aware scale-out
> scaffold（StatefulSet、headless/per-owner Service、QuickFIX ConfigMap）與 `risk-service` outbox
> connector
> ConfigMap；尚未形成整個系統的 kind 部署集合。

### 6.1 manifests（最小）

- [ ] Namespace
- [ ] Deployments/StatefulSets：各服務
    - [x] `quickfix-gateway` StatefulSet 已存在
    - [ ] `risk-service` / `account-service` / `persistence` / `matching-engine` manifests 未見
- [ ] Services：內部 DNS
    - [x] `quickfix-gateway` headless / per-owner Services 已存在
    - [ ] 其餘服務 Service manifests 未見
- [ ] ConfigMaps/Secrets：Kafka/Postgres/Redis/gRPC targets
    - [x] QuickFIX config 與 risk-service outbox connector ConfigMap 已存在
    - [ ] Secrets 與 service runtime config aggregation 未見
- [ ] Probes：liveness/readiness
    - [x] `quickfix-gateway` 已有 startup/readiness/liveness probes
- [ ] 資源限制：requests/limits（避免 noisy neighbor）
    - [ ] 目前只見 PVC storage request，container CPU/memory requests/limits 未見

### 6.2 固定 shard owner（matching-engine）

- [ ] matching-engine 用 StatefulSet
    - [ ] workspace 未找到 `matching-engine` K8s manifests
- [ ] 每個 pod 透過 env/args 指定要 `assign()` 哪些 partitions
    - [ ] workspace 未找到 `matching-engine` assign-partition env/args scaffold

---

## 7) CI（GitHub Actions；kind smoke test 待補）

> 現況：`.github/workflows/ci.yml` 已有 changed-area gating、Java static analysis + repo-wide
> test/certification、Flyway/PostgreSQL smoke、native configure/build/test；kind cluster 與 `kubectl`
> deploy smoke 尚未進 CI。

- [x] Java 靜態分析已接入 Gradle build：`./gradlew -q staticAnalysis` 會對所有 Java 模組執行
  `Error Prone`
  blocking 編譯，並對既定模組執行 blocking `Checkstyle` / `PMD` / `SpotBugs`
- [x] #55 Error Prone blocking ratchet：移除 warning-only 設定，以 TestKit 負向驗證確認
  handwritten finding 會使 `compileJava` 失敗，並保留 generated-source exclusion 邊界
- [x] #56 complete quality-ratchet verification（2026-08-03）：ordinary root static analysis 在
  Checkstyle / PMD / SpotBugs / Error Prone 全部 blocking 的情況下通過，完整 Java suite 與
  QuickFIX certification 通過；47-rule PMD single source 與七參數邊界已記錄於
  [quality-ratchet-verification](docs/quality-ratchet-verification.md)。該文件也記錄 #21/#22
  的目前 closure evidence，但不替兩個 parent issue 修改內容或狀態
- [x] #21 PMD suppression closure（2026-08-03）：production Java 已無 PMD suppression 或
  `//NOPMD`，且四 analyzer gate、完整 Java suite 與 policy locks/docs/CI evidence 均通過；未
  使用 `FutureReturnValueIgnored` suppression
- [x] #22 Checkstyle suppression review（2026-08-03）：五個 Spring Boot application entry
  point 改由 private constructor 通過 `HideUtilityClassConstructor`，`config/checkstyle/suppressions.xml`
  僅保留 generated-source boundary，且不修改 `config/checkstyle/checkstyle.xml`
- [x] SpotBugs false-positive filter 已先收斂到已確認的 Spring config / runtime holder 類別，避免干擾實際問題
- [x] 現有 GitHub Actions Java job 已接入同一個 `./gradlew staticAnalysis` blocking gate，並上傳
  Checkstyle / PMD / SpotBugs 報告 artifacts
- [x] GitHub Actions 已依變更路徑做 job-level filtering：docs-only 變更不再觸發 Java / Native 全量建置
- [x] GitHub Actions Java job 已執行 repo-wide `test` 與
  `:services:quickfix-gateway:certificationTest`
- [x] #31 mutable-exposure ratchet：移除 class-wide `EI_EXPOSE_REP` / `EI_EXPOSE_REP2`
  exclusions；`config/spotbugs/exclude.xml` 僅保留六個具 owner、理由與 retirement condition
  的 private-final infrastructure fields，並由 account outbox、risk outbox 與 WAL ownership
  tests 保護 payload / collection 邊界
- [x] #32 validation evidence（2026-07-31）：ordinary root `./gradlew -q staticAnalysis`、
  repo-wide `./gradlew test` 與 `:services:quickfix-gateway:certificationTest` 均通過於目前
  working tree；清潔 review commit 的同一組 root gate 仍須由 owner 重新驗證後才可關閉 #32
- [x] GitHub Actions 已執行 Flyway / PostgreSQL smoke checks（`bash scripts/run-flyway-ci-checks.sh`）
- [x] GitHub Actions 已執行 native configure/build/test（CMake preset + `ctest --preset vcpkg`）
- [ ] workflow：起 kind cluster
    - [ ] workflow 尚未安裝或建立 kind cluster
- [ ] `kubectl apply -f deploy/k8s/`
    - [ ] README 有手動指令，但 CI workflow 尚未自動套用 manifests
- [ ] 等待 pods ready
- [ ] 最小驗證：
    - [x] 現況：CI 目前沒有任何 kind / K8s smoke assertions
    - [ ] 呼叫 `/readyz`
    - [ ] scrape `/metrics`（至少回 200）
    - [ ] （可選）丟一筆測試 command（用簡化 producer）→ 看到 matching.executions

---

## 8) Observability（Prometheus + Grafana + Alertmanager + OTel）

### 8.1 Prometheus

- [ ] scrape configs：所有服務 `/metrics`
- [ ] 基礎 rules：
    - [ ] consumer lag 增長
    - [ ] outbox/WAL backlog age 超標
    - [ ] gRPC error rate / timeout

### 8.2 Grafana

- [ ] Prometheus data source
- [ ] dashboards（最小集合）：Kafka / Outbox-WAL / Matching / gRPC

### 8.3 Alertmanager

- [ ] routes：severity（page/ticket）
- [ ] receivers：Slack/Email（選一個最小）
- [ ] inhibit rules：避免告警風暴

### 8.4 OpenTelemetry

- [ ] trace export（OTLP）
- [ ] trace propagation：HTTP/gRPC + Kafka headers（`traceparent`）
- [ ] span attributes：`order_id`, `cl_ord_id`, `command_id`, `exec_id`, `symbol`, `account_id`

---

## 9) 文件 / 操作手冊（補齊落地）

- [ ] `tasks.md`（本檔）維護：每個里程碑完成時打勾
- [x] [Taiwan event-driven refactor plan](docs/taiwan-event-driven-refactor-plan.md) Phase 0:
  establish the recoverable baseline through worktree review, module inventory, FIX/risk/WAL/outbox
  characterization, v1 Protobuf compatibility inventory, and validation evidence (GitHub
  issue [#10](https://github.com/WenHsuanYu/SimpleMatch/issues/10))
- [x] [Taiwan event-driven refactor plan](docs/taiwan-event-driven-refactor-plan.md) Phase 1:
  centralize version ownership, Spring/Protobuf/Flyway/Java conventions, and checked-in Gradle
  dependency locks; gate evidence is
  in [Phase 1 build and dependency policy](docs/phase-1-build-dependency-policy.md).
- [x] [Taiwan event-driven refactor plan](docs/taiwan-event-driven-refactor-plan.md) Phase 3: add
  additive v2 Protobuf domain contracts, checked-in field-number inventories, typed Taiwan-market
  values, and lossless v1 command adapters without rerouting existing services.
- [x] [Parameter-safe Account Authority and Risk Admission lifecycle](docs/refactoring/domain-parameter-safety-refactor.md)
  （GitHub issues [#39](https://github.com/WenHsuanYu/SimpleMatch/issues/39) and
  [#44](https://github.com/WenHsuanYu/SimpleMatch/issues/44)）：完成 semantic lifecycle values,
  single-path Account transactions, persisted Risk Admission delivery routes, compatibility checks,
  and the completed-slice verification gate。
    - [x] [#40](https://github.com/WenHsuanYu/SimpleMatch/issues/40) Account Authority reservation
      lifecycle state
    - [x] [#41](https://github.com/WenHsuanYu/SimpleMatch/issues/41) legacy reservation writer,
      response projection, and account outbox
    - [x] [#42](https://github.com/WenHsuanYu/SimpleMatch/issues/42) Risk Admission journal/result
      semantic state values
    - [x] [#43](https://github.com/WenHsuanYu/SimpleMatch/issues/43) deterministic symbol-keyed
      delivery route persistence and recovery reuse
    - [x] [#44](https://github.com/WenHsuanYu/SimpleMatch/issues/44) cross-service verification and
      documentation alignment
- [x] [Risk Submission outbox event descriptor](docs/refactoring/domain-parameter-safety-refactor.md)
  （GitHub spec [#45](https://github.com/WenHsuanYu/SimpleMatch/issues/45) and issue
  [#46](https://github.com/WenHsuanYu/SimpleMatch/issues/46)）：以 event information、delivery
  routing、serialized payload 與 aggregate reference 取代 positional outbox construction，並保留
  headers、Protobuf、Kafka routing、JDBC 與 CDC contract。
    - [x] [#46](https://github.com/WenHsuanYu/SimpleMatch/issues/46) Deepen Risk Submission outbox
      event descriptor
- [x] [QuickFIX ingress durable path](docs/refactoring/domain-parameter-safety-refactor.md)
  （GitHub issues [#65](https://github.com/WenHsuanYu/SimpleMatch/issues/65) and
  [#66](https://github.com/WenHsuanYu/SimpleMatch/issues/66)）：將 new-order 的 preparation、
  WAL-before-risk admission、accepted/rejected response 深化為具名模組，並讓 inbound handler
  只負責 message-type dispatch；保留 FIX、WAL、risk、session 與 compatibility 行為。
    - [x] [#65](https://github.com/WenHsuanYu/SimpleMatch/issues/65) Deepen the new-order FIX path
    - [x] [#66](https://github.com/WenHsuanYu/SimpleMatch/issues/66) Reduce FIX ingress to message dispatch
- [x] [Remaining parameter-safe application and configuration modules](docs/refactoring/domain-parameter-safety-refactor.md)
  （GitHub issues [#67](https://github.com/WenHsuanYu/SimpleMatch/issues/67)、
  [#68](https://github.com/WenHsuanYu/SimpleMatch/issues/68)、以及
  [#69](https://github.com/WenHsuanYu/SimpleMatch/issues/69)）：沿既有 capability seam 與
  application-owned transaction seam 深化實作，保留 Spring property、SQL、outbox 與 admission
  行為。
    - [x] [#67](https://github.com/WenHsuanYu/SimpleMatch/issues/67) Split QuickFIX gateway properties
    - [x] [#68](https://github.com/WenHsuanYu/SimpleMatch/issues/68) Extract Admission lifecycle transactions
    - [x] [#69](https://github.com/WenHsuanYu/SimpleMatch/issues/69) Separate pending Admission recovery
    - [x] [#70](https://github.com/WenHsuanYu/SimpleMatch/issues/70) Expand shared capability properties
    - [x] [#71](https://github.com/WenHsuanYu/SimpleMatch/issues/71) Migrate Account Authority config
    - [x] [#72](https://github.com/WenHsuanYu/SimpleMatch/issues/72) Migrate Risk Admission config
    - [x] [#73](https://github.com/WenHsuanYu/SimpleMatch/issues/73) Migrate Market Reference config
    - [x] [#74](https://github.com/WenHsuanYu/SimpleMatch/issues/74) Migrate FIX Gateway config
    - [x] [#75](https://github.com/WenHsuanYu/SimpleMatch/issues/75) Remove the shared platform facade
    - [x] [#76](https://github.com/WenHsuanYu/SimpleMatch/issues/76) Verify parameter-safe module migration
- [x] [Market Reference routing ownership](https://github.com/WenHsuanYu/SimpleMatch/issues/38)：
  design accepted in [ADR 0005](docs/adr/0005-market-reference-routing-policy.md)；#78–#86
  implementation and certification establish Market Reference as the sole routing-policy authority。
- [x] [README and technical documentation refactor](docs/readme-documentation-refactor-spec.md)
  （GitHub issue [#1](https://github.com/WenHsuanYu/SimpleMatch/issues/1)）：將目標技術規格收斂至
  `services/docs/`，並保留穩定索引與 forwarding pages
    - [x] [#2](https://github.com/WenHsuanYu/SimpleMatch/issues/2) 建立 cross-cutting docs index 與
      Markdown link-validation seam
    - [x] [#3](https://github.com/WenHsuanYu/SimpleMatch/issues/3) 發布 cross-cutting architecture
      specifications
    - [x] [#4](https://github.com/WenHsuanYu/SimpleMatch/issues/4) 發布 Kafka、gRPC 與 FIX contract
      specifications
    - [x] [#5](https://github.com/WenHsuanYu/SimpleMatch/issues/5) 發布 data、database 與
      configuration platform specifications
    - [x] [#6](https://github.com/WenHsuanYu/SimpleMatch/issues/6) 發布 development 與 operations
      platform specifications
    - [x] [#7](https://github.com/WenHsuanYu/SimpleMatch/issues/7) 新增 service-owned target
      documentation
    - [x] [#8](https://github.com/WenHsuanYu/SimpleMatch/issues/8) 將 README 收斂為
      target-architecture landing page
    - [x] [#9](https://github.com/WenHsuanYu/SimpleMatch/issues/9) 完成 canonical documentation 與
      compatibility navigation audit
- [ ] README 補 link：
    - [ ] 指向 proto 檔
    - [ ] 指向 deploy/k8s
    - [x] 指向 CI workflow
- [ ] Troubleshooting runbook：
    - [ ] Kafka lag 飆高怎麼查
    - [ ] outbox backlog 怎麼查
    - [ ] FIX resend/dedup 怎麼驗證
