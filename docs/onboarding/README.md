# 新成員上手：先理解系統與文件

本指南假設你熟悉一般軟體開發，但尚未接觸 SimpleMatch。完成這條閱讀路徑後，你應能說明一筆訂單經過哪些責任邊界、找到修改所依據的規格與現況、執行與工作範圍相符的本機檢查，並開始第一次小範圍修改。各領域的詳細規則由原有權威文件維護；本指南負責建立閱讀順序。

## 一筆訂單如何經過系統

SimpleMatch 是 Java 與 C++ 共存的 monorepo，處理現金股票的 FIX 訂單流程。先閱讀根目錄 [README](../../README.md) 的架構概觀，再讀 [CONTEXT.md](../../CONTEXT.md) 的 context map、bounded contexts 與 ubiquitous language。理解資料流時，先掌握下列順序：

1. **Gateway 接住外部請求。** `quickfix-gateway` 負責 FIX session、輸入正規化、gateway-local validation、有效輸入的 durable intent，以及對外 FIX 回應，再同步呼叫 Risk Admission。
2. **Risk 與 Account 各自作出所屬決定。** `risk-service` 擁有 admission identity、可恢復的接受／拒絕結果與可靠命令發布；`account-service` 擁有額度、部位和 reservation，並在自己的交易中維持不變量。Risk 不能直接接管 Account 的狀態。
3. **被接受的命令進入有序 Matching 路徑。** Matching 依固定 partition ownership 處理命令、維護 order book 並產生確定性的結果。目標流程中的第一個成功 acknowledgment 代表 durable admission；成交結果會由後續非同步事件傳回。
4. **下游各自處理事件。** Account、Persistence 與 Gateway 負責各自的永久效果及冪等處理；Query、market-data projection 與 streaming 提供可重建的讀取或串流視圖。這些讀取視圖不取得下單或帳務的決策權。

這條路徑還有兩個先決條件：離線 Market Reference builder 產出已核准的每日 immutable artifact，Risk 與 Matching 載入相同 bytes；Gateway 內的 Operational Coordination 綜合各 owner 的狀態決定 admission 是否可開啟，恢復不會自動重新開放。這些責任邊界與完整的 identity、retry、replay 規則都在 [CONTEXT.md](../../CONTEXT.md)，更精確的跨服務時序則見 [system boundaries](../../services/docs/architecture/system-boundaries.md) 和 [reliability and consistency](../../services/docs/architecture/reliability-and-consistency.md)。

## 從責任找到規格與程式

先依行為判定 owner，再選資料夾。某個數值出現在多個服務中，不表示所有服務都能修改它；共享 contract 也不等於共享一個跨服務 aggregate。

| 想理解或修改的責任 | 程式入口 | 優先閱讀 |
| --- | --- | --- |
| FIX、session、外部回應與 admission 操作 | `services/quickfix-gateway/` | [Gateway 規格](../../services/quickfix-gateway/docs/README.md) |
| Admission、risk decision、accepted command 發布 | `services/risk-service/` | [Risk 規格](../../services/risk-service/docs/README.md) |
| 額度、部位、reservation 與 fill application | `services/account-service/` | [Account 規格](../../services/account-service/docs/README.md) |
| 確定性撮合、partition ownership 與 replay | `matching-engine/` | [Matching ingress and recovery](../../services/docs/architecture/matching-ingress.md) |
| 永久交易紀錄、critical inbox 與投影 | `services/persistence/` | [Persistence 規格](../../services/persistence/docs/README.md) |
| 可重建的查詢視圖與讀取 API | `services/query-service/` | [Query 規格](../../services/docs/query-service/README.md) |
| 每日 Market Reference artifact | `tools/market-reference-builder/`、`shared-java/market-reference-contract/` | [Builder 使用指南](../../config/market-reference/README.md)、[artifact contract](../reference/market-reference-artifact-contract.md) |
| 跨服務 protocol 與 wire contract | `proto/`、`shared-java/` | [Contracts 索引](../../services/docs/contracts/README.md) |

Market-data 與 streaming 的責任及範圍先從 [CONTEXT.md](../../CONTEXT.md) 和 [系統規格](../../services/docs/architecture/system-boundaries.md) 進入，再用 [Phase 1 能力清單](../status/phase-1-capability-inventory.md) 找已實作的入口與證據。目標圖上的方塊不能單獨證明對應功能已經完成。

## 分清規格、現況、工作狀態與證據

開始修改前，先確認目前閱讀的文件回答哪一種問題。這能避免將未完成計畫當成現行機制，或把某次成功測試解讀為所有環境都可用。

| 問題 | 優先來源 | 閱讀方式 |
| --- | --- | --- |
| 系統為何如此劃分、名詞和不變量是什麼？ | [CONTEXT.md](../../CONTEXT.md)、[總索引中的 ADR 入口](../README.md) | ADR 要確認是否已被取代，再判定目前適用的決策。 |
| 系統應該滿足什麼契約？ | [Target documentation](../../services/docs/README.md)、service-owned specs | 目標規格表達應有行為；與 source 不符時，需要判斷是實作缺口或文件過時。 |
| Repository 目前完成哪些能力？ | [Phase 1 能力清單](../status/phase-1-capability-inventory.md)、目前 source/config/tests | 檢查列出的實作、限制與證據版本。 |
| 目前要做什麼、誰負責、是否被阻擋？ | Owning GitHub Issue | Issue 是 executable workflow state 的權威；`tasks.md` 提供歷史導覽。 |
| 某項驗證實際證明什麼？ | 對應 verification 文件及其保留證據 | 核對 source revision、執行日期、環境與測試範圍；本機、聚焦測試與完整認證有不同的證據邊界。 |

有衝突時，把不一致的位置與可查核依據寫清楚，再處理 owning scope；完整的文件分工見 [Issue tracker authority model](../agents/issue-tracker.md#authority-model)。

## 建議閱讀順序與完成條件

1. 讀根目錄 [README](../../README.md) 與 [CONTEXT.md](../../CONTEXT.md)，確認自己能說明 admission、reservation、matching 與 projection 的 owner。
2. 用 [target documentation 索引](../../services/docs/README.md) 找與任務相關的規格，再讀 [能力清單](../status/phase-1-capability-inventory.md)；寫下應有行為、目前行為與尚缺證據各是什麼。
3. 依 [本機開發起步](local-development.md) 選 Java 或 native 路徑，完成最小驗證並找到測試輸出。
4. 按 [第一次修改](first-change.md) 從 Issue、驗收條件和 owner 開始，完成一個容易 review 的變更。

需要其他用途或主題時，回到 [文件總索引](../README.md)。AI 協作者也遵循這條路徑，並先讀 [AGENTS.md](../../AGENTS.md)；文件搜尋與 graph 導覽的具體要求以該檔為準。
