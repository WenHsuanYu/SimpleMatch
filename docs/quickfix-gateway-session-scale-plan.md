# QuickFIX Gateway Session-Aware Scale-Out Plan

## 目標

本計畫的目標是為 `quickfix-gateway` 建立可水平擴展的 session-aware 架構，先實現「同一 FIX session 永遠回到同一 logical gateway owner」，再逐步補上 standby failover。

短期目標是 correctness 與 recoverability，不是先追求任意 replica 接手。

## 目前判斷

`quickfix-gateway` 不適合做成一般 stateless service。它目前同時持有：

- QuickFIX/J session lifecycle 與本機 FileStore/FileLog
- inbound WAL
- `OrderSessionRegistry` 內的 order/session mapping 與 execution dedup
- 對 `matching.executions` 的 outbound FIX 回報責任

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
- QuickFIX store、log、WAL 都要放在 owner 專屬 PVC
- startup 時要先做 recovery，再進 readiness

### Phase 2：補足 owner-local recovery 與 shared state 基礎

這一階段仍以 same-owner restart 為主，但開始建立將來 standby failover 需要的能力。

重點：

- 將 `OrderSessionRegistry` 從純記憶體狀態演進成可恢復狀態
- recovery pipeline 在 pod ready 前重建：
  - `order_id -> session_id / gatewayOwnerId`
  - cancel context
  - execution dedup 基線
- 重新定義 `matching.executions` 的 consume 模型
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

本次先落第一個可驗證切片：

- 新增 `quickfixGateway.ownerId` 配置
- 將 `matching.executions` 的 Kafka consumer group 預設改為 owner-aware
- 將 ownerId 接進 runtime 與 acceptor 啟動日志
- 補齊文件與測試，作為後續 StatefulSet / endpoint / recovery 實作的骨架
- 新增 StatefulSet / owner Service / PVC / continuity config map 的 K8s scaffolding
- 新增 startup recovery lifecycle 與 `/readyz` readiness gating

## 檢查工作內容清單

### 文件與配置

- [x] 建立 `docs/quickfix-gateway-session-scale-plan.md`
- [x] 新增 `quickfixGateway.ownerId` 配置說明
- [ ] 定義 session routing snapshot 格式
- [x] 定義 owner endpoint 命名規則
- [x] 定義 production continuity 的 QuickFIX 參數集

### Phase 1：same-owner restart

- [x] 建立 `gatewayOwnerId` 配置骨架
- [x] 將 Kafka consumer group 預設改為 owner-aware
- [x] 設計 StatefulSet ordinal 與 owner mapping
- [x] 設計 per-owner Service / endpoint
- [x] 將 QuickFIX store / log / WAL 對齊 PVC
- [x] 關閉 production 不適合的 reset 設定
- [x] 設計 startup recovery 與 readiness gating
- [ ] 實作 same-owner reconnect 驗證測試

### Phase 2：shared state 基礎

- [ ] 定義 `OrderSessionRegistry` 可恢復狀態模型
- [ ] 決定 state store 策略：owner-local only / Redis / Postgres
- [ ] 設計 execution dedup 持久化策略
- [ ] 設計 `matching.executions` 多 owner consume 策略
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