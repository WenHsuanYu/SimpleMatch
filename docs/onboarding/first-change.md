# 第一次修改

第一次貢獻的目標是完成一個邊界明確、可驗證、容易 review 的改動。先理解 owner 與成功條件，再寫程式，能讓測試證據直接對應需求，也能避免因一個局部問題而改動多個服務的責任。本文提供工作順序；具體規則以 [AGENTS.md](../../AGENTS.md) 與 [開發流程](../development/development-workflow.md) 為準。

## 1. 從 Issue 寫出驗收條件

先閱讀 owning GitHub Issue 的需求、comments、dependencies 與目前狀態，再對照 [Phase 1 能力清單](../status/phase-1-capability-inventory.md) 的實作與證據。Issue 擁有工作狀態，能力清單負責 repository capability/evidence；兩者的分工見 [Issue tracker](../agents/issue-tracker.md#authority-model)。

動手前，用幾句話寫下觸發情境、預期行為、要保留的不變量與可觀察的成功條件，並列出受影響的 services、modules、files。將本次必須完成的行為和後續可選工作分開。若需求尚無 owner 或明確驗收條件，先釐清這些缺口，保留已有的獨立查核結果。

## 2. 找到規格、目前實作與測試

依 [CONTEXT.md](../../CONTEXT.md) 判斷誰有權決定該行為，從 [target documentation](../../services/docs/README.md) 和 service-owned specs 找契約，再查目前 source、configuration 與 tests。AI 協作者先依 [AGENTS.md](../../AGENTS.md) 使用 repository 的 graph 導覽與 applicable skills，將 source 閱讀縮小到相關路徑；graph 關係仍須用實際 source 與 tests 驗證。

閱讀時整理出三件事：契約要求什麼、目前程式做什麼，以及哪個 public seam 能觀察結果。例如修改 Account reservation，應先確認 reservation lifecycle、trading day 與 idempotency 的規則，再選 Account 的公開測試入口。若涉及 Gateway、Risk 與 Account 的共同流程，先查 [transaction and consistency policy](../reference/transaction-and-consistency-policy.md)，明確處理 transaction owner、RPC、retry 與 recovery 的邊界。

若規格與程式不同，保留差異及其依據，先判斷是 bug、規格過時或尚未完成的能力。測試需要證明預期行為，單純複製目前 implementation 的執行順序無法解決這個判斷。

## 3. 選最小切片，先定義驗證

選擇能獨立滿足驗收條件的最小改動，優先重用既有 module、interface 和 public test seam。編輯前閱讀 [badCodeSmell.md](../../badCodeSmell.md)，辨識這次容易引入的重複、責任混雜、命名不清或過度一般化；界面與 domain-value 的既有原則可查 [parameter-safety 指引](../development/domain-parameter-safety-refactor.md)。

非平凡程式變更先寫能重現缺失行為的 focused test，再做最小 implementation。PostgreSQL schema 變更先設計 versioned Flyway migration、SQL review 和驗證方案，依 [database implementation guide](../development/database-implementation-guide.md) 進行。純文件或路徑調整則先列語意與連結檢查計畫，使用既有 checker 驗證對應風險。

修改期間保持每個 commit 單一目的，檢查工作目錄中是否有其他人的變更，只處理本次範圍。Java 的 Javadocs、transaction、PMD 與其他規則直接依 [AGENTS.md](../../AGENTS.md) 執行；不要藉由改 gate 或放寬規則讓局部修改通過。

## 4. 按變更範圍驗證

先跑最快能驗證此行為的 focused checks，再跑相關完整 suite。下表是選擇方向，精確任務與前置條件仍以 owning module 和 [開發流程](../development/development-workflow.md#5-validate-in-the-right-order) 為準。

| 變更 | 驗證順序與預期 |
| --- | --- |
| Java 行為或 wiring | Focused tests → owning module/service suite → `./gradlew staticAnalysis` → 適用的 service smoke 或 certification。Integration tests 所需 dependencies 應先準備完成。 |
| Native 行為 | Focused target/tests → 正常 `dev-debug` build/CTest；重現 CI 或 sanitizer 問題時選對應 preset。 |
| PostgreSQL schema 或 repository SQL | Migration-focused 與受影響的 tests、適用的 Flyway tasks，再檢查 SQL/index/query implications 和必要的 service verification。 |
| Markdown 文件或導覽 | 受影響文件的 Markdown links/fragments、checker regression、`git diff --check`，再比較規格含義與證據邊界。 |
| 跨服務、messaging 或 deployment | 依契約選 integration/smoke/certification，先完成適用的 deployment preflight 和 planner/reuse 判定。 |

文件修改的基本檢查可使用下列指令，將路徑換成實際受影響的文件：

```bash
bash scripts/check-markdown-links.sh docs/onboarding/first-change.md
bash scripts/test-check-markdown-links.sh
git diff --check
```

記錄實際執行的命令與結果，區分重新執行、`UP-TO-DATE`、blocked 和未執行。某個 focused check 通過，證據只涵蓋該範圍；deployment gate 的結果需保留其 source revision、環境與輸出。

## 5. 同步文件並準備 review

完成實作後，更新行為、設定或入口所屬的文件，並同步 owning Issue 的驗收與證據；只有 capability、evidence、dependency 或 acceptance mapping 有變時，才更新 [能力清單](../status/phase-1-capability-inventory.md)。`tasks.md` 保持歷史導覽的角色。新增重要決策或改變 domain terminology 時，依 [domain 文件規則](../agents/domain.md) 維持 CONTEXT 和 ADR 的分工。

最後對照驗收條件檢查 diff，確認程式、測試、文件與設定一致，並依 [badCodeSmell.md](../../badCodeSmell.md) 做一次精簡檢查。交付說明應讓 reviewer 看懂修改解決的行為、測試涵蓋的範圍，以及仍未驗證或刻意留待後續的項目。與 GitHub 發布或 push 有關的授權和操作，依 [AGENTS.md](../../AGENTS.md) 執行。

當你能從 Issue 指到規格、從規格指到 owner 與測試，再以同一組驗收條件說明結果，就已具備繼續處理下一個切片的基本工作方式。其他主題可從 [文件總索引](../README.md) 查找。
