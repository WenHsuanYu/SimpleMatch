# SimpleMatch — Implementation Tasks Checklist

> 目標：把 README 的架構落成「能開工、能跑、能觀測、能在 kind/K8s 做 smoke test」的任務拆解。
>
> 範圍假設：
>
> - 語言：C++20（依 README）
> - Data plane：Kafka（at-least-once）+ 端到端冪等
> - Control/Query plane：gRPC unary / streaming（明確 deadline / retry / breaker）
> - System-of-record：PostgreSQL（含 outbox / processed_events）
> - Read model：Redis（**Redis-first 查詢首選**；屬可重建 projection，權威仍在 DB/`account-service`）
> - 對外：QuickFIX（C++，FIX 4.4，fix-gateway 作為 Acceptor）、gRPC streaming（marketdata-streamer）
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
  - [ ] outbox 為 append-only（只 INSERT）
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

- [ ] 建立 topics：`orders.commands`, `orders.validated`, `matching.executions`, `marketdata.events`, `audit.events`（README 資料流圖預設存在；可用 `ENABLE_AUDIT_EVENTS` 關閉）
- [ ] topic 設定（最小）：
  - [ ] `replication.factor=3`（若環境支援）
  - [ ] `min.insync.replicas=2`
  - [ ] producer `acks=all`
  - [ ] 禁用 unclean leader election（broker 層）
- [ ] partition 策略：
  - [ ] key = `symbol`（至少保證同 symbol 保序）
  - [ ] 若要固定路由：`symbol_routing`（symbol→partition_id）

### 3.2 Postgres schema（最小可跑）

- [ ] `orders`（含 `source_session_id`, `client_order_id/ClOrdID` 的 UNIQUE）
- [ ] `executions`（`UNIQUE(order_id, exec_id)`）
- [ ] `outbox`（append-only；`event_id UNIQUE`）
- [ ] `processed_events`（`(consumer_name, event_id)` PK）
- [ ] `symbol_routing`（symbol→shard/partition；可選但建議）

---

## 4) 服務任務拆解（按微服務）

## 4.1 `fix-gateway`（QuickFIX/C++，FIX 4.4，Acceptor）

### 4.1.1 FIX session / transport

- [ ] QuickFIX acceptor config：`config/fix/acceptor.cfg`
  - [ ] 最小必要欄位（示意）：`BeginString=FIX.4.4`, `ConnectionType=acceptor`, `SenderCompID`, `TargetCompID`, `SocketAcceptPort`, `HeartBtInt`
  - [ ] 啟用/管理 dictionary：`UseDataDictionary=Y`, `DataDictionary=fix-spec/FIX44.xml`
- [ ] 支援 logon/logout、heartbeat、sequence reset（依對手方需求）
- [ ] inbound/outbound message persistence（為了 resend/合規稽核；最小可先落檔）
  - [ ] `MessageStoreFactory`：`FileStoreFactory`（起步）→ 需要時改 DB store
  - [ ] `LogFactory`：`FileLogFactory`
  - [ ] resend/重送驗證：斷線重連、`ResendRequest`、gap fill、`PossDupFlag`/`OrigSendingTime`

### 4.1.2 入口 ACK（DB commit 版 / WAL 版）

- [ ] 設計選項旗標：`ACK_MODE = db_commit | wal`
- [ ] `db_commit` 路徑：
  - [ ] `PersistOrderAndOutbox(tx, ...)` 成功後才回覆 PendingNew/Accepted
- [ ] `wal` 路徑：
  - [ ] `WalAppender::Append(record)`
  - [ ] flush 策略（per-record vs group-commit）
  - [ ] WAL 落盤成功後回覆 PendingNew/Accepted
  - [ ] WAL ingester：讀 WAL → 寫 DB + outbox（`orders.commands`）
  - [ ] crash recovery：重啟時從 WAL 重放未入 outbox 的記錄

### 4.1.3 FIX → Domain command

- [ ] `FixParser::ParseNewOrderSingle()` → `OrderCommand{type=NEW}`
- [ ] `FixParser::ParseCancelRequest()` → `OrderCommand{type=CANCEL}`
- [ ] 正規化欄位：symbol、side、qty、price、order_type、tif
- [ ] 市價單保護價（若採用）：`ComputeProtectionLimitPx()`

### 4.1.4 去重（FIX + 業務層）

- [ ] FIX session 層：配合 QuickFIX 行為處理 `ResendRequest`, `PossDupFlag`, `OrigSendingTime`（並確保 message store 能支援重送）
- [ ] 業務層：ClOrdID idempotency
  - [ ] `DedupRepo::FindOrCreateByClOrdId(session, trading_day, cl_ord_id)`
  - [ ] 重送一致：若 payload 相同回同結果；不同回 reject

### 4.1.5 產出 `orders.commands`

- [ ] Outbox event producer：`OutboxRepo::Insert(... topic=orders.commands ...)`
- [ ] 事件 key/partition：依 `symbol_routing` 指派 partition（若啟用）

### 4.1.6 消費 `matching.executions` → FIX 回報

- [ ] Kafka consumer：`matching.executions`
- [ ] `ExecutionEvent` → FIX `ExecutionReport`（成交/狀態更新/撤單成功）
- [ ] 撤單被拒（FIX 慣例）：`ExecutionEvent` → FIX `OrderCancelReject (35=9)`
  - [ ] 必填：`ClOrdID(11)`（撤單請求）、`OrigClOrdID(41)`（原委託）、`OrdStatus(39)`（原委託狀態）
  - [ ] 原因：`CxlRejReason(102)` + `Text(58)`（若有）、`CxlRejResponseTo(434)=1`
- [ ] 去重：`exec_id` / `(order_id, exec_id)`
- [ ] session 斷線/重連：可重送回報（FIX resend）

### 4.1.7 endpoints

- [ ] `/healthz` `/readyz` `/metrics`

### 4.1.8 （選用）`fix-gateway` ↔ `account-service`（session 身分/權限映射）

> 對齊 README：此連線用途是 FIX session 身分 ↔ `account_id` 映射、帳戶/權限驗證。
> 建議僅用於 session 建立/定期刷新，避免進入每筆下單的極短 ACK 路徑。

- [ ] gRPC client：`AccountService::ResolveSessionIdentity()`（或以 `GetAccountProfile()` 等形式）
- [ ] 在 Logon / Session 建立時：取得 `account_id` / 權限/風控等級（若需要）並快取於 session context
- [ ] failure policy：連不上 `account-service` 時拒絕建立 session（fail-closed）或降級（依需求選一個）
- [ ] deadline / breaker / bulkhead 落地（與 `risk-service` 同一套最低規範）

---

## 4.2 `risk-service`

### 4.2.1 Kafka consumer：`orders.commands`

- [ ] 反序列化 `OrderCommand`
- [ ] Idempotency：`ProcessedEventsRepo::TryMarkProcessed(consumer=risk, event_id/command_id)`

### 4.2.2 規則檢核

- [ ] 基本格式、交易時段、symbol 合法
- [ ] 支援 LIMIT/MARKET × ROD/IOC/FOK（規則不合法直接 rejected）
- [ ] 市價單保護價規則（若採用）

### 4.2.3 交易額度 / reservation（control plane gRPC）

- [ ] gRPC client：`AccountService::GetLimits/GetPositions`（快取可選）
- [ ] `Reserve(order_id/request_id, ...)`（冪等）
- [ ] deadline / retry / breaker / bulkhead 落地

### 4.2.4 產出 `orders.validated` / `orders.rejected`

- [ ] Outbox pattern：決策落 DB（可選）+ outbox insert → Debezium
- [ ] Kafka key/partition：與 commands 同套路由（symbol）

### 4.2.5 endpoints

- [ ] `/healthz` `/readyz` `/metrics`

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

- [ ] A) Outbox+CDC（建議）
  - [ ] 撮合結果寫入本地 journal 或 DB
  - [ ] 同 tx outbox insert（topic=matching.executions）
- [ ] B) 輕量直寫 Kafka（可選）
  - [ ] producer acks=all + idempotent

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

### 4.4.4 （可選，但 README 圖上預設存在）產出 `audit.events`

> 若你要把 `persistence` 做成「落地 + 稽核事件流輸出」的服務（README 的資料流圖是這個預設），就需要能穩定產生 `audit.events`。

- [ ] 定義 `AuditEvent` schema（可先用 `proto/audit.proto`，或沿用既有 `ExecutionEvent` 加 audit metadata）
- [ ] 從 `matching.executions` 映射產生 `AuditEvent`（例如：原事件摘要 + 落地後的 DB 主鍵/水位）
- [ ] 可靠性：若 `audit.events` 開啟，使用 outbox（同 DB tx 內 insert outbox）→ Debezium → Kafka
- [ ] 去重鍵：`audit_event_id`（可沿用上游 `event_id` 或 `exec_id` 衍生）
- [ ] feature flag：`ENABLE_AUDIT_EVENTS=true|false`（MVP 可先關閉）

### 4.4.5 endpoints

- [ ] `/healthz` `/readyz` `/metrics`

> 註：若 `persistence` 要產生衍生事件流（例如 audit/events），才需要在 `persistence` 端引入 Outbox。

---

## 4.5 `marketdata-publisher`

- [ ] consume `matching.executions`
- [ ] 產出 `marketdata.events`（trade/quote）
- [ ] 去重策略（可選）：event_id / 時間窗合併
- [ ] `/healthz` `/readyz` `/metrics`

---

## 4.6 `marketdata-streamer`（external gRPC streaming）

### 4.6.1 Kafka consumers

- [ ] consume `marketdata.events`
- [ ] （可選）consume `matching.executions` 做 private notifications

### 4.6.2 gRPC server

- [ ] `SubscribeMarketData(request) -> stream MarketDataEvent`
- [ ] `SubscribePrivateNotifications(request) -> stream PrivateNotification`
- [ ] backpressure / flow control（最小：每連線 buffer 上限，超過就斷線或降級）

### 4.6.3 AuthN/AuthZ

- [ ] TLS
- [ ] mTLS 或 JWT（選一個最小可跑）
- [ ] private stream：account_id 由憑證/claims 映射，不接受 client 任意指定

### 4.6.4 endpoints

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
- [ ] cancel/IOC leaves：`Release(order_id, ...)`
- [ ] Idempotency：`exec_id` / `event_id`

### 4.7.3 交易額度資料模型（不含現金版）

- [ ] `limits`（帳戶/商品/日）
- [ ] `reservations`（open orders 占用）
- [ ] `utilized`（成交後占用，可由 positions 或另表推導）
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

## 5) Debezium / CDC（Outbox 發佈）

- [ ] Debezium connector for each outbox-owning service DB（fix-gateway, risk-service, matching-engine 等）
- [ ] topic routing：outbox.topic 欄位 → Kafka topic
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
  - [ ] 指向 CI workflow
- [ ] Troubleshooting runbook：
  - [ ] Kafka lag 飆高怎麼查
  - [ ] outbox backlog 怎麼查
  - [ ] FIX resend/dedup 怎麼驗證
