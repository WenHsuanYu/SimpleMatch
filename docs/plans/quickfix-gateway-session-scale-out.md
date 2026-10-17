# QuickFIX Gateway Session-Aware Scale-Out Plan

這份文件保留分階段 scale-out 計畫與原始工作清單。現行 same-owner 基礎實作和 runner 覆蓋
不代表 multi-owner 或 standby failover 已完成；清單不是 GitHub Issue 即時狀態或本次部署證據。

## 目標

本計畫的目標是為 `quickfix-gateway` 建立可水平擴展的 session-aware 架構，先實現「同一 FIX session
永遠回到同一 logical gateway owner」，再逐步補上 standby failover。

短期目標是 correctness 與 recoverability，不是先追求任意 replica 接手。

## 目前判斷

`quickfix-gateway` 不適合做成一般 stateless service。它目前同時持有：

- QuickFIX/J session lifecycle 與 JDBC session/message store、JDBC log
- owner-local inbound WAL 與 recovery journal
- `OrderSessionRegistry` 內的 ingress order/session cache，以及 JDBC delivery ledger 的 durable
  execution identity / delivery state
- 對 `matching.events` 的 outbound FIX 回報責任

因此水平擴展應採 `StatefulSet + 固定 owner + 固定 endpoint`，而不是共享 round-robin Service。

## 分階段策略

### Phase 1：同一 logical owner restart 可續連

這一階段對齊 continuity 選項一，不做 standby takeover。

重點：

- 引入固定 `gatewayOwnerId`，例如 `quickfix-gateway-0`、`quickfix-gateway-1`
- 每個 FIX session 預先分配到固定 owner
- client 連到 owner 專屬 endpoint，而不是共享 Service
- pod crash 後，由相同 StatefulSet ordinal 重建相同 owner
- client reconnect 到同一 endpoint，保留 FIX session / sequence / resend 語義
- QuickFIX/J `ResetOnLogon/Logout/Disconnect` 要切到 production continuity 模式
- owner-local WAL / recovery journal 放在 owner 專屬 PVC；QuickFIX session/message store 與 log
  由 Gateway-owned JDBC persistence 保存
- startup 時要先做 recovery，再進 readiness

### Phase 2：補足 owner-local recovery 與 shared state 基礎

這一階段仍以 same-owner restart 為主，但開始建立將來 standby failover 需要的能力。

重點：

- 將 `OrderSessionRegistry` 從純記憶體狀態演進成可恢復狀態
- recovery pipeline 在 pod ready 前重建：
    - `order_id -> SenderCompID / TargetCompID / gatewayOwnerId`
    - cancel context
    - execution dedup 基線
- 以 `matching.events` 重新定義多 owner consume 模型
- 短期採每個 owner 獨立 consumer group、全量 consume + 本地過濾
- 長期可把 `gatewayOwnerId` 帶入 contracts，讓 gateway 只收自己的 execution
- 規劃 owner lease / fencing 所需的 state store
- Redis 可作為候選，但用途是自訂 gateway state / lease store，不是 Spring Session

### Phase 3：standby owner failover

只有 Phase 1 與 2 穩定後，才進入 continuity 選項三。

重點：

- 每個 owner bucket 有對應 standby
- primary crash 且超過 restart budget 時，promotion standby
- 以 fencing 保證同一時間只有一個合法 owner
- route transfer 同時處理：
    - client reconnect 入口
    - internal execution routing
- 若 shared state 未同步完成，系統必須 fail-closed，而不是錯誤接手 session

## 關鍵名詞

- `FIX owner`：某個 FIX session 在系統內的唯一責任歸屬。
- `promotion`：將 standby 升級為新的 primary owner。
- `failover`：從 primary 故障到 standby 成功接手的完整流程。
- `fencing`：防止雙主，同一時間只允許一個 owner 合法處理某個 session。
- `route transfer`：owner 轉移後，將 client reconnect 入口與內部 execution 回報路徑一起切到新 owner。

## 當前實作切片

已存在的 same-owner 基礎切片包含：

- 使用 `simplematch.quickfix-gateway.owner-id` 配置
- 將 `matching.events` 的 Kafka consumer group 預設改為 owner-aware
- 將 ownerId 接進 runtime 與 acceptor 啟動日志
- 補齊文件與測試，作為後續 StatefulSet / endpoint / recovery 實作的骨架
- 新增 StatefulSet / owner Service / PVC / continuity config map 的 K8s scaffolding
- 新增 startup recovery lifecycle 與 `/readyz` readiness gating
- local resilience overlay 將 QuickFIX owner 固定在 worker slot 1，並讓單一 market-data streamer
  只在 local-resilience worker pool 內以 `Recreate`、portable-workload toleration 替換

這些配置固定 deployment ownership contract。現行 `WalReplayService` 在 startup reconciliation
時恢復 accepted new-order session context；`OrderSessionRegistry` 是 ingress cache，durable FIX
delivery ledger 才是 restart-safe delivery authority。QuickFIX acceptor 使用
`QuickFixJdbcAcceptorFactory` 建立 `JdbcStoreFactory` / `JdbcLogFactory`，不使用 FileStore / FileLog。
現有 continuity config 裡的 FileStorePath / FileLogPath 不能作為啟用 file-backed factory 的依據。
manifest 渲染結果不等於 runtime recovery 證據，也不重複驗證 Kubernetes scheduler、StatefulSet
或 Kafka consumer group 已保證的語意。
同一 owner 的 FIX reconnect/resend 與 streamer replacement/reconnect/resubscribe 由部署情境
runner 負責：`scripts/end-to-end/critical-consumers/run-failure-certification.sh` 驗證 QuickFIX
durable recovery，`scripts/end-to-end/market-data/run-streamer-recovery-certification.sh` 透過
公開 gRPC subscription、Kafka group assignment 與 Pod identity 驗證 streamer replacement。
這些 runner 只檢查 issue 所需的 recovery boundary；它們不把每個 protobuf 欄位或平台已保證的
基本 scheduler 語意重新複製成第二套 verifier。

其中 critical-consumer runner 已包含相同 owner 的 Gateway restart、client reconnect 與 explicit
resend 檢查，要求重送維持同一 ExecID / MsgSeqNum 並帶 PossDupFlag。這是現有驗證路徑的範圍，
實際 PASS 仍須讀取對應 source / run evidence；本次文件整理沒有重跑它。下列原始清單中與
registry recovery、dedup、outbound recovery 和 restart/reconnect 有關的項目，應依這些既有能力
重新界定剩餘驗收範圍，不能把它們全視為未有實作，也不能據此宣稱 multi-owner routing、graceful
drain 或 standby lease / fencing / takeover 已完成。

## 檢查工作內容清單

### 文件與配置

- [x] 建立 `docs/plans/quickfix-gateway-session-scale-out.md`
- [x] 新增 `simplematch.quickfix-gateway.owner-id` 配置說明
- [ ] 定義 session routing snapshot 格式
- [x] 定義 owner endpoint 命名規則
- [x] 定義 production continuity 的 QuickFIX 參數集

### Phase 1：same-owner restart

- [x] 建立 `gatewayOwnerId` 配置骨架
- [x] 將 Kafka consumer group 預設改為 owner-aware
- [x] 設計 StatefulSet ordinal 與 owner mapping
- [x] 設計 per-owner Service / endpoint
- [x] 將 owner-local WAL / recovery journal 對齊 PVC；QuickFIX store / log 使用 JDBC persistence
- [x] 關閉 production 不適合的 reset 設定
- [x] 設計 startup recovery 與 readiness gating
- [x] 實作 same-owner reconnect 驗證 runner；保留 live certification 作為 runtime gate

### Phase 2：shared state 基礎

- [ ] 定義 `OrderSessionRegistry` 可恢復狀態模型
- [ ] 決定 state store 策略：owner-local only / Redis / Postgres
- [ ] 設計 execution dedup 持久化策略
- [ ] 設計 `matching.events` 多 owner consume 策略
- [ ] 定義 owner lease / fencing 基礎資料模型
- [ ] 驗證 recovery 後 outbound execution 仍可正確送出

### Phase 3：standby failover

- [ ] 定義 standby owner 模型
- [ ] 定義 restart budget
- [ ] 定義 promotion 條件
- [ ] 定義 fencing 機制
- [ ] 定義 route transfer 流程
- [ ] 驗證 primary hard crash 後的 standby takeover
- [ ] 驗證 failover 後 client reconnect 與 execution routing 一致

### 測試與驗證

- [x] 新增配置預設與 Spring context 綁定測試
- [ ] 新增 unit test：session owner routing
- [ ] 新增 integration test：same-owner restart continuity
- [ ] 新增 integration test：multi-owner outbound execution correctness
- [ ] 新增 integration test：graceful drain / rolling update
- [ ] 新增 integration test：standby failover
- [ ] 擴充 QuickFIX certification / smoke test 覆蓋 restart / reconnect path
