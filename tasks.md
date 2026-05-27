# SimpleMatch — Implementation Tasks Checklist

> 目標：把 README 的架構落成「能開工、能跑、能觀測、能在 kind/K8s 做 smoke test」的任務拆解。
>
> 範圍假設：
>
> - 語言：polyglot（Java/Spring 為主；`matching-engine` 保留 C++20）
> - Data plane：Kafka（at-least-once）+ 端到端冪等
> - Control/Query plane：gRPC unary / streaming（明確 deadline / retry / breaker）
> - System-of-record：PostgreSQL（含 outbox / processed_events）
> - Read model：Redis（**Redis-first 查詢首選**；屬可重建 projection，權威仍在 DB/`account-service`）
> - 對外：QuickFix/J（Java，FIX 4.4，`quickfix-gateway` 作為 Acceptor）、gRPC streaming（marketdata-streamer）
> - Observability：OpenTelemetry + Prometheus + Grafana + Alertmanager
>

---

## 0) Repo / Monorepo 結構（專案骨架）

- [x] 建立目錄（若尚未建立）：`services/`, `libs/`, `proto/`, `config/`, `deploy/`, `docs/`
- [x] 建立頂層 `CMakeLists.txt`（或 workspace-level build 指南）
- [x] 建立 vcpkg/依賴說明：QuickFIX（C++）、gRPC、protobuf、librdkafka、PostgreSQL client、Redis client、OTel SDK、Prometheus exporter
- [x] 統一設定載入方式（環境變數 + config 檔）：
  - [x] `ENV`（dev/stage/prod）
  - [x] Kafka brokers / topics / partitions
  - [x] Postgres DSN
  - [x] Redis endpoints
  - [x] gRPC targets（control plane）
  - [x] Observability（OTel exporter、Prometheus port）

---

## 1) 共用 libs（跨服務重用）

### 1.1 logging / config / time

- [ ] `libs/common`：結構化 JSON logging（統一欄位）
  - [ ] `LogContext`：`service`, `env`, `trace_id`, `span_id`, `order_id`, `cl_ord_id`, `event_id`, `symbol`, `account_id`
  - [ ] logger 初始化（level、sink、格式）
  - [ ] request/trace context 注入 helper
- [ ] `libs/common`：config loader
  - [ ] `LoadConfig()`：env + yaml/toml/json（選一種）
  - [ ] 嚴格驗證必填欄位（啟動即 fail-fast）
- [ ] `libs/common`：時間/ID 工具
  - [ ] `NowUnixMs()`
  - [ ] `UuidV4()`

### 1.2 Kafka wrapper（producer/consumer）

- [ ] `libs/kafka`：producer wrapper
  - [ ] `KafkaProducer::Publish(topic, key, value, headers, partition_opt)`
  - [ ] 支援設定：`acks=all`、retries、delivery timeout、idempotent producer（若用 librdkafka）
  - [ ] 統一 headers：`event_id`, `traceparent`, `source_service`, `created_at`
- [ ] `libs/kafka`：consumer wrapper
  - [ ] 支援 `subscribe()`（一般服務）與 `assign()`（matching-engine 固定 partition）
  - [ ] `PollLoop(handler)` + graceful shutdown
  - [ ] offset commit 策略可選：sync/async
  - [ ] 指標：poll latency、commit latency、consumer lag（可從 librdkafka 統計/自算）

### 1.3 Postgres 存取層（含 outbox / processed_events）

- [ ] `libs/db`：連線池/交易封裝
  - [ ] `Db::BeginTx()` / `Tx::Commit()` / `Tx::Rollback()`
- [ ] `libs/db`：Outbox DAO
  - [ ] `OutboxRepo::Insert(tx, event_id, topic, key, partition_opt, payload, headers_json)`
  - [ ] event payload row 以 INSERT 建立，供 Debezium CDC 從 PostgreSQL outbox 讀取並發布到 Kafka
- [ ] `libs/db`：Idempotency / Processed events DAO
  - [ ] `ProcessedEventsRepo::TryMarkProcessed(tx, consumer_name, event_id)`（成功才繼續）
  - [ ] 或 `InboxRepo::Upsert(event_id, ...)`（依你 schema）

### 1.4 gRPC client utilities（control plane 韌性）

- [ ] `libs/grpc`：統一 deadline/timeout 設定 helper
- [ ] `libs/grpc`：重試策略（只對 Get* 類安全讀取啟用）
- [ ] `libs/grpc`：circuit breaker / bulkhead（可先做最簡版：連續失敗 N 次→短暫熔斷）

### 1.5 Observability SDK（OTel + Prometheus）

- [ ] `libs/obs`：OpenTelemetry tracer 初始化
  - [ ] 支援 OTLP exporter（HTTP/gRPC）
  - [ ] span attributes helper（把 domain id 放進 span）
- [ ] `libs/obs`：Prometheus metrics exporter
  - [ ] `/metrics` HTTP endpoint（每服務一個）
  - [ ] 常用 metric helpers：counter/gauge/histogram

---

## 2) Protobuf / FIX domain model（契約先行）

### 2.1 Protobuf：Kafka payload schemas

- [ ] `proto/orders.proto`
  - [ ] `OrderCommand`（new/cancel；含 `command_id`, `order_id`, `account_id`, `symbol`, `side`, `qty`, `price`, `order_type`, `tif`, `created_at`）
  - [ ] `OrderValidated` / `OrderRejected`（含 reason code）
- [ ] `proto/matching.proto`
  - [ ] `ExecutionEvent`（fill/cancel/reject；含 `exec_id`, `order_id`, `symbol`, `fill_qty`, `fill_px`, `leaves_qty`, `event_id`, `created_at`）
- [ ] `proto/marketdata.proto`
  - [ ] `MarketDataEvent`（trade/quote；含 `event_id`, `symbol`, `ts_unix_ms`）
- [ ] schema versioning 規範
  - [ ] message 加 `schema_version` 或在 headers 帶版本
  - [ ] 相容性策略：只加欄位、不重用 field number

### 2.2 gRPC：control/query plane APIs

- [ ] `proto/account_service.proto`
  - [ ] `GetLimits(account_id)` / `GetPositions(account_id)`
  - [ ] `Reserve(request_id/order_id, ...)`（冪等）
  - [ ] 回傳包含 reservation 狀態（reserved/available/utilized）
- [ ] `proto/marketdata_service.proto`
  - [ ] `SubscribeMarketData(...)` server-streaming
  - [ ] `SubscribePrivateNotifications(...)` server-streaming

### 2.3 FIX ↔ domain mapping 規範

- [ ] FIX 欄位到 `OrderCommand` 的 mapping 文件化（欄位表）
- [ ] `ClOrdID` 去重 key 定義：`(SenderCompID, TargetCompID, TradingDay, ClOrdID)`

---

## 3) Data plane topics（Kafka）與資料庫 schema

### 3.1 Topics 建立與設定

- [ ] 建立 topics：`orders.validated`, `matching.executions`, `marketdata.events`, `audit.events`（README 資料流圖預設存在；可用 `ENABLE_AUDIT_EVENTS` 關閉）
- [ ] topic 設定（最小）：
  - [ ] `replication.factor=3`（若環境支援）
  - [ ] `min.insync.replicas=2`
  - [ ] producer `acks=all`
  - [ ] 禁用 unclean leader election（broker 層）
- [ ] partition 策略：
  - [ ] `matching.executions`：key = `symbol`（至少保證同 symbol 保序）
  - [x] `orders.validated`：`risk-service` 已依 published snapshot 計算 `kafka_partition_id`，寫入 outbox 與 `OrderValidated.routing_partition`，並由 Debezium connector 明確指定 partition
  - [ ] follow-up：matching-engine 若也需要本地 routing 判斷，再載入同一份 published snapshot
  - [ ] follow-up：若要盤前人工 override / 版本化管理，再升級成 `routing_snapshots` + `symbol_routing_entries`

### 3.2 Postgres schema（最小可跑）

- [ ] `risk-service` local schema
  - [x] `risk_submissions`（同步 ingress journal；`UNIQUE(idempotency_key)`、`UNIQUE(outbox_event_id)`）
  - [x] `outbox`（append-only event row；`event_id UNIQUE`，供 Debezium CDC 讀取；已含 `kafka_partition_id`）
- [ ] `account-service` authority schema
  - [x] `account_limits`（帳戶/商品/日額度 bucket；含 `limit_total_notional`, `reserved_notional`, `utilized_notional`, `available_notional`）
  - [x] `account_positions`（per-account, per-symbol 持倉快照；`UNIQUE(account_id, symbol)`）
  - [x] `account_reservations`（open orders 預扣狀態；`reservation_id = order_id` 或 `request_id`）
- [ ] projection / read-model schema
  - [x] `orders`（含 `source_session_id`, `client_order_id/ClOrdID` 的 UNIQUE）
  - [x] `executions`（`UNIQUE(order_id, exec_id)`）
  - [x] `processed_events`（`(consumer_name, event_id)` PK）
- [ ] routing / config schema
  - [ ] `symbol_routing`（symbol→shard/partition；可選但建議；MVP 可先不建表）
  - [ ] `routing_snapshots`（版本 / 交易日 / 發布狀態）
  - [ ] `symbol_routing_entries`（snapshot 下的 symbol 映射；partition 決策由 `routing_bucket` / `kafka_partition_id` 欄位表達）

### 3.3 Flyway rollout（目前只有 `risk-service` 已接線）

- [x] `risk-service` 已接入 `simplematch.flyway-service`，並以 versioned migration 管理 `risk_submissions` + local `outbox`
- [x] `risk-service` 已新增 `kafka_partition_id` migration，讓 Debezium 可使用 explicit partition placement
- [x] `account-service` 接入 `simplematch.flyway-service`
- [x] `account-service` 初始 migration：`account_limits`, `account_positions`, `account_reservations`
- [x] `persistence` 接入 `simplematch.flyway-service`
- [x] `persistence` 初始 migration：`orders`, `executions`, `processed_events`
- [ ] `matching-engine` / WAL loader-ingester 決定 schema owner 後接入 Flyway
  - [ ] 若 `matching-engine` 自有 PostgreSQL / outbox，建立 service-local result journal / `outbox`
  - [ ] 若 `executions` 最終由 `persistence` 單獨落地，避免在 `matching-engine` 端重複定義 read-model tables
- [ ] `symbol_routing` schema owner 決策與初始 migration（MVP 先用 config/snapshot；若未來落表，優先收斂到單一 `reference-data` / `routing-config` owner）
- [ ] `reference-data-service` / `routing-config-service` owner 決策（Phase 1 不建 service；先用 published snapshot）
- [ ] 進入 Phase 2 時，再接入 Flyway 並建立 `routing_snapshots` + `symbol_routing_entries`

### 3.4 Routing rollout（依三階段）

- [x] Phase 1 基礎：`risk-service` 已以 published routing snapshot（config/file）計算 routing；熱路徑不查 DB
  - [x] `simplematch.routing.snapshotPath` 已接入 risk-service，預設載入 classpath sample，可覆寫到外部 published snapshot
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

- [x] `risk-service` 已以 published routing snapshot 計算 `kafka_partition_id`，保留 `message_key` 作為業務鍵，並明確指定 `orders.validated` partition

---

## 4) 服務任務拆解（按微服務）

## 4.1 `quickfix-gateway`（QuickFix/J + Java/Spring，FIX 4.4，Acceptor）

### 4.1.1 FIX session / transport

- [x] QuickFIX acceptor config：`config/fix/acceptor.cfg`
  - [x] 最小必要欄位（示意）：`BeginString=FIX.4.4`, `ConnectionType=acceptor`, `SenderCompID`, `TargetCompID`, `SocketAcceptPort`, `HeartBtInt`
  - [x] 啟用/管理 dictionary：`UseDataDictionary=Y`, `DataDictionary=fix-spec/FIX44.xml`
- [ ] 支援 logon/logout、heartbeat、sequence reset（依對手方需求）
- [ ] inbound/outbound message persistence（為了 resend/合規稽核；最小可先落檔）
  - [ ] `MessageStoreFactory`：`FileStoreFactory`（起步）→ 需要時改 DB store
  - [ ] `LogFactory`：`FileLogFactory`
  - [ ] resend/重送驗證：斷線重連、`ResendRequest`、gap fill、`PossDupFlag`/`OrigSendingTime`

### 4.1.2 入口 ACK（risk-service persistence-first）

- [x] 主路徑：gateway 以同步 gRPC 將請求送到 `risk-service`
- [x] 第一個成功 FIX ack 僅在 `risk-service` transaction commit 成功後回覆 `PendingNew/Accepted`
- [x] 若 `risk-service` 同步判定失敗，gateway 直接回 `Rejected`
- [x] `orders.commands` compatibility publish / WAL replay 不再視為主線；目前預設停用，僅保留遷移或診斷時顯式開啟
- [ ] 若保留 WAL：
  - [x] `WalAppender::Append(record)` 作為本地恢復 / 稽核輔助
  - [ ] 明確標記 WAL 不再是主 ack 錨點
  - [ ] 規劃 crash recovery / diagnostics 使用方式，不與主提交語意混淆

### 4.1.3 FIX → Domain command

- [x] `FixParser::ParseNewOrderSingle()` → `OrderCommand{type=NEW}`
- [x] `FixParser::ParseCancelRequest()` → `OrderCommand{type=CANCEL}`
- [x] 正規化欄位：symbol、side、qty、price、order_type、tif
- [ ] 市價單保護價（若採用）：`ComputeProtectionLimitPx()`

### 4.1.4 去重（FIX + 業務層）

- [ ] FIX session 層：配合 QuickFIX 行為處理 `ResendRequest`, `PossDupFlag`, `OrigSendingTime`（並確保 message store 能支援重送）
- [ ] 業務層：ClOrdID idempotency
  - [ ] `DedupRepo::FindOrCreateByClOrdId(session, trading_day, cl_ord_id)`
  - [ ] 重送一致：若 payload 相同回同結果；不同回 reject

### 4.1.5 同步送交 `risk-service`（主路徑）

- [x] gRPC client：`RiskService::SubmitOrder()` / `CancelOrder()`
- [x] request 內必須帶穩定冪等鍵：`client_order_id` / `ClOrdID`
- [x] bounded retry：僅限暫態 transport 錯誤，且重試時必須沿用同一 idempotency key
- [x] deadline / breaker / connection reuse 落地
  - [x] deadline / connection reuse 已落地（blocking stub deadline + shared managed channel）
  - [x] breaker 已落地（consecutive-failure open + cooldown half-open probe）

### 4.1.6 消費 `matching.executions` → FIX 回報

- [ ] Kafka consumer：`matching.executions`
- [ ] `ExecutionEvent` → FIX `ExecutionReport`（成交/狀態更新/撤單成功）
- [ ] 撤單被拒（FIX 慣例）：`ExecutionEvent` → FIX `OrderCancelReject (35=9)`
  - [ ] 必填：`ClOrdID(11)`（撤單請求）、`OrigClOrdID(41)`（原委託）、`OrdStatus(39)`（原委託狀態）
  - [ ] 原因：`CxlRejReason(102)` + `Text(58)`（若有）、`CxlRejResponseTo(434)=1`
- [ ] 去重：`exec_id` / `(order_id, exec_id)`
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

### 4.2.1 gRPC server：primary ingress

- [x] 定義 `SubmitOrder` / `CancelOrder` gRPC API
- [x] request 需攜帶 `client_order_id` / `ClOrdID`、`order_id`、必要 FIX 正規化欄位
- [x] 同步回覆語意：只有在本地 transaction commit 成功後才回成功
- [x] 唯一鍵冪等：相同 key 重送時回同一筆結果，不可重複建立訂單
  - [x] PostgreSQL 唯一鍵 / transaction 版已完成

### 4.2.2 規則檢核

- [ ] 基本格式、交易時段、symbol 合法
  - [x] 基本必填格式檢核已落地
  - [ ] 交易時段 / symbol registry 驗證尚未完成
- [ ] 支援 LIMIT/MARKET × ROD/IOC/FOK（規則不合法直接 rejected）
- [ ] 市價單保護價規則（若採用）

### 4.2.3 交易額度 / reservation（control plane gRPC）

- [ ] gRPC client：`AccountService::GetLimits/GetPositions`（快取可選；回傳需對齊 `account_limits` / `account_positions`）
- [ ] `Reserve(order_id/request_id, ...)`（冪等；由 `account-service` 更新 `account_reservations`）
- [ ] deadline / retry / breaker / bulkhead 落地

### 4.2.4 產出 `orders.validated` / `orders.rejected`

- [x] Outbox pattern：同步請求先完成本地 transaction，並寫入待發布事件到 outbox
  - [x] `risk-service` service code 已切換為 Debezium CDC 導向：服務內只寫 append-only outbox，不再 app 內 publish
  - [x] 清理 app 內 built-in relay 與其專屬 publish / lease state
  - [x] 補正式 versioned PostgreSQL migration，既有 schema 明確移除 relay 專屬欄位
- [x] `risk_submissions` 作為 local ingress journal，與 outbox event 一對一關聯
- [x] Kafka key/partition：與 commands 同套路由（symbol）

### 4.2.5 endpoints

- [ ] `/healthz` `/readyz` `/metrics`
  - [x] `application.yaml` 已開 management exposure，服務 smoke test 已補上
  - [ ] 自訂 `/healthz` `/readyz` alias 尚未補

---

## 4.3 `matching-engine`

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
- [ ] WAL loader / ingester：以單一 PostgreSQL transaction 寫入成交結果 + outbox（topic=`matching.executions`）
- [ ] Debezium CDC：由 PostgreSQL logical decoding / WAL 將 `matching.executions` 發布到 Kafka

### 4.3.4 主備接手（進階，可後做）

- [ ] fencing：每 shard/partition 一把鎖（etcd/Consul/ZK/PG advisory lock 任選）
- [ ] standby warm-up：跟讀重建 orderbook 但不產出
- [ ] takeover：取得鎖 + 對齊 offset + 開始產出

### 4.3.5 endpoints

- [ ] `/healthz` `/readyz` `/metrics`

---

## 4.4 `persistence`（sink / projection builder）

### 4.4.1 Kafka consumer：`matching.executions`

- [ ] 反序列化 ExecutionEvent
- [ ] Idempotency：`ProcessedEventsRepo::TryMarkProcessed(consumer=persistence, event_id/exec_id)`

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

> 若你要把 `persistence` 做成「落地 + 稽核事件流輸出」的服務（README 的資料流圖是這個預設），就需要能穩定產生 `audit.events`。

- [ ] 定義 `AuditEvent` schema（可先用 `proto/audit.proto`，或沿用既有 `ExecutionEvent` 加 audit metadata）
- [ ] 從 `matching.executions` 映射產生 `AuditEvent`（例如：原事件摘要 + 落地後的 DB 主鍵/水位）
- [ ] 可靠性：若 `audit.events` 開啟，使用 outbox（同 DB tx 內 insert outbox）→ Debezium CDC → Kafka
- [ ] 去重鍵：`audit_event_id`（可沿用上游 `event_id` 或 `exec_id` 衍生）
- [ ] feature flag：`ENABLE_AUDIT_EVENTS=true|false`（MVP 可先關閉）

### 4.4.5 endpoints

- [ ] `/healthz` `/readyz` `/metrics`

> 註：若 `persistence` 要產生衍生事件流（例如 audit/events），才需要在 `persistence` 端引入 Outbox。

---

## 4.5 `marketdata-publisher`

- [ ] v1：consume `matching.executions`
- [ ] v1：先產出 `marketdata.events` 的 `TradeUpdate`
- [ ] quote / `TopOfBookUpdate` 後補：待有穩定 order book / quote projection 後再加，避免先造 synthetic quote
- [ ] 決定發布可靠性：
  - [ ] 若市場資料也要求 durability，沿用 outbox + CDC
  - [ ] 若 MVP 接受較弱保證，先明確記錄 direct publish 的限制
- [ ] 去重策略（可選）：event_id / 時間窗合併
- [ ] `/healthz` `/readyz` `/metrics`

---

## 4.6 `marketdata-streamer`（external gRPC streaming）

### 4.6.1 Kafka consumers

- [ ] v1：consume `marketdata.events`
- [ ] private notifications 走分流：`matching.executions` 另做 private stream，不與公開 market data 混在同一條 stream

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

### 4.7.1 gRPC server（control plane）

- [ ] `GetLimits(account_id)`
- [ ] `GetPositions(account_id)`
- [ ] `Reserve(request_id/order_id, ...)`（冪等）
  - [ ] 唯一鍵：`reservation_id = order_id` 或 `request_id`
  - [ ] 重送回同結果

### 4.7.2 Kafka consumer（建議）：`matching.executions`

- [ ] fill：`ApplyFill(exec_id, order_id, ...)`
- [ ] cancel/IOC leaves：`ReleaseReservation(reservation_id/order_id, ...)`
- [ ] Idempotency：`exec_id` / `event_id`

### 4.7.3 交易額度資料模型（不含現金版）

- [ ] `account_limits`（帳戶/商品/日額度 bucket；含 `reserved_notional` / `utilized_notional` / `available_notional`）
- [ ] `account_positions`（per-account, per-symbol 持倉快照）
- [ ] `account_reservations`（open orders 預扣狀態；`reservation_id = order_id` 或 `request_id`）
- [ ] `utilized` 先作為 `account_limits.utilized_notional` materialized 欄位；若後續 exposure 邏輯獨立再拆表
- [ ] `available = limit_total - reserved - utilized`

### 4.7.4 endpoints

- [ ] `/healthz` `/readyz` `/metrics`

---

## 4.8 `query-service`（內網查詢 API；Redis-first）

> 目的：把查詢流量從權威服務（例如 `account-service` / Postgres）分離，提供低延遲 read API。
> 對齊 README：`query-service` 建議只走 gRPC/HTTP 讀 Postgres/Redis projections，**不要直接讀 Kafka**。

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

- [x] `risk-service`：已提供 Debezium connector 範本，將 PostgreSQL outbox 變更發布到 Kafka，並以 `kafka_partition_id` 指定 partition
- [ ] `matching-engine`：配置 Debezium connector，將 PostgreSQL outbox 變更發布到 Kafka
- [ ] 若 `persistence` 後續產生 `audit.events`，再為其 outbox DB 配置 Debezium connector
- [x] topic routing：outbox.topic 欄位 → Kafka topic
- [ ] at-least-once 期望：consumer 冪等必做
- [ ] 監控：connector lag、error rate

---

## 6) Kubernetes（kind）部署與設定

### 6.1 manifests（最小）

- [ ] Namespace
- [ ] Deployments/StatefulSets：各服務
- [ ] Services：內部 DNS
- [ ] ConfigMaps/Secrets：Kafka/Postgres/Redis/gRPC targets
- [ ] Probes：liveness/readiness
- [ ] 資源限制：requests/limits（避免 noisy neighbor）

### 6.2 固定 shard owner（matching-engine）

- [ ] matching-engine 用 StatefulSet
- [ ] 每個 pod 透過 env/args 指定要 `assign()` 哪些 partitions

---

## 7) CI（GitHub Actions + kind）Smoke test

- [x] Java 靜態分析已接入 Gradle build：`./gradlew staticAnalysis` 會對所有 Java 模組執行 blocking `Error Prone` 編譯，並對既定模組執行 `Checkstyle` / `SpotBugs`
- [x] SpotBugs false-positive filter 已先收斂到已確認的 Spring config / runtime holder 類別，避免干擾實際問題
- [x] 現有 GitHub Actions Java job 已接入同一個 `./gradlew staticAnalysis` blocking gate，並上傳 Checkstyle / SpotBugs 報告 artifacts
- [x] GitHub Actions 已依變更路徑做 job-level filtering：docs-only 變更不再觸發 Java / Native 全量建置
- [ ] workflow：起 kind cluster
- [ ] `kubectl apply -f deploy/k8s/`
- [ ] 等待 pods ready
- [ ] 最小驗證：
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
- [ ] README 補 link：
  - [ ] 指向 proto 檔
  - [ ] 指向 deploy/k8s
  - [x] 指向 CI workflow
- [ ] Troubleshooting runbook：
  - [ ] Kafka lag 飆高怎麼查
  - [ ] outbox backlog 怎麼查
  - [ ] FIX resend/dedup 怎麼驗證
