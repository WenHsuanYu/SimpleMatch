# SimpleMatch 文件導覽

這裡是開發者與 AI 協作者的文件入口。先用 [CONTEXT.md](../CONTEXT.md) 理解領域語言與責任邊界，再從[目標規格](../services/docs/README.md) 找設計依據，並以 [Phase 1 能力清單](status/phase-1-capability-inventory.md) 確認實作現況與證據。文件依用途分類；同一系統主題的規格、實作參考與驗證文件，透過下方主題索引串在一起。

## 新加入成員的閱讀順序

1. [系統與文件上手](onboarding/README.md)：看懂主要訂單路徑、各服務的責任，以及目標設計與實作現況的查閱方式。
2. [本機開發](onboarding/local-development.md)：依 Java 或 native 工作範圍準備工具，完成最小建置與測試。
3. [第一次修改](onboarding/first-change.md)：從 Issue 與驗收條件找到 owning module、規格和測試，完成範圍明確的修改與驗證。

## 先判斷需要哪一種依據

| 要回答的問題 | 權威入口與閱讀方式 |
| --- | --- |
| 業務名詞是什麼、誰擁有哪項責任？ | 根目錄 [CONTEXT.md](../CONTEXT.md) 集中描述多個 bounded contexts 的領域語言、ownership 與不變條件。 |
| 系統應如何設計、跨服務契約是什麼？ | [目標規格入口](../services/docs/README.md) 串起跨服務架構、平台規格和各服務擁有的契約；[ADR](#架構決策研究與歷史) 說明決策理由與取代關係。 |
| 目前實作了什麼、還欠哪種證據？ | [Phase 1 能力清單](status/phase-1-capability-inventory.md) 記錄 capability、依賴、驗收與證據；遇到細部行為問題，再核對對應 source、設定與測試。 |
| 工作現在是 open、blocked、assigned 還是 completed？ | [GitHub Issues](https://github.com/WenHsuanYu/SimpleMatch/issues) 擁有可執行的工作狀態；[Issue tracker 規則](agents/issue-tracker.md) 定義與能力清單的分工。`tasks.md` 僅供歷史導航。 |
| 修改時應遵守哪些流程與規則？ | [AGENTS.md](../AGENTS.md)、[開發流程](development/development-workflow.md) 與相關的政策／服務規格；以工作涉及的邊界選擇要讀的文件。 |
| 如何操作、如何驗證、某次執行證明了什麼？ | 下方 operations 提供操作步驟；verification 分別提供驗收契約、程序、工具設計與有來源版本的紀錄。讀取證據時一併核對日期、source revision、環境與適用範圍。 |

資料夾表示文件的主要用途；規範效力與實作狀態仍以文件內的角色說明為準。例如 transaction policy 定義應滿足的規則，Account／Risk reference 說明實作責任，plan 保留未來工作。`services/docs/` 和各服務文件繼續擁有目標規格，本頁負責把它們連到讀者需要的入口。

## 依用途找文件

### 開發流程與實作指引

- [開發流程](development/development-workflow.md)：開始修改、設計、測試、檢查與交付的共同順序。
- [依賴管理](development/dependencies.md)：Gradle conventions、版本目錄、BOM 與 dependency locking 的 ownership。
- [資料庫實作指引](development/database-implementation-guide.md)：Flyway、schema 演進、connector 與驗證的實作入口；目標 topology 與資料擁有權見[平台資料庫規格](../services/docs/platform/database-architecture.md)。
- [領域參數與介面 review](development/domain-parameter-safety-refactor.md)：現行設計原則、review 指引與歷史修改批次；搭配 [badCodeSmell.md](../badCodeSmell.md) 和 [PMD 政策](reference/pmd-quality-policy.md) 閱讀。

### 政策、契約與實作參考

| 涉及的工作 | 文件 |
| --- | --- |
| runtime 設定、profile 與設定權威 | [Runtime configuration](reference/runtime-configuration.md) |
| 交易邊界、原子性與 phase gates | [Transaction and consistency policy](reference/transaction-and-consistency-policy.md)；跨服務 identity、retry 與 recovery 見[平台契約](../services/docs/platform/consistency-recovery-identity-and-errors.md)。 |
| Account reservation 的責任與本地交易 | [Account reservation authority](reference/account-reservation-authority.md) |
| Risk admission 的持久化結果與恢復 | [Durable Risk admission](reference/durable-risk-admission.md) |
| 離線 Market Reference 的輸入與輸出 | [官方來源契約](reference/market-reference-official-source-contract.md)、[每日 artifact 契約](reference/market-reference-artifact-contract.md) |
| Matching journal 的 Kafka 耐久性設定 | [Matching Kafka durability profile](reference/kafka-matching-durability-profile.md) |
| Java 靜態分析與 immutable ruleset | [PMD quality policy](reference/pmd-quality-policy.md) |

### 操作指南

- [繁體中文操作手冊](operations/operation-manual.zh-TW.md)：完整本機 lab 的建置、操作、認證、觀測與清理入口；首次做最小開發驗證可先走[本機開發](onboarding/local-development.md)。
- [Market Reference 核准流程](operations/market-reference-approval-workflow.md)：candidate、review、approval 與每日 artifact 的交付。
- [本機 registry 與資源生命週期](operations/local-registry-resource-lifecycle.md)：映像、namespace、資源 ownership 與清理順序。

### 驗證、認證與執行紀錄

[本機 production-like 認證 runbook](verification/local-production-like-certification.md) 是 repository-owned 完整認證的入口；部署前先讀[部署測試 lessons 與 preflight](agents/deployment-test-lessons.md)。[ADR 0011](adr/0011-local-production-like-certification-boundary.md) 定義目前完成邊界：本機程序與證據、特定來源版本的執行紀錄，以及 staging／production promotion templates 各有適用範圍。檔名中的 live 或 certification 須連同文件的環境和證據契約解讀。

| 要驗證或查核的範圍 | 文件及角色 |
| --- | --- |
| Risk → Account reservation | [Account v2 live verification](verification/account-reservation-v2-live-verification.md)：跨 gRPC、Account、DB 與 outbox 的本機驗收程序。 |
| Risk → Matching 與 replay | [Risk-to-Matching deployed E2E](verification/risk-matching-command-e2e.md)、[Risk restart／equivalent replay](verification/risk-matching-restart-replay.md)：基線與依賴該基線的恢復程序。 |
| CDC publication | [Risk／Account CDC verification](verification/cdc-publication-verification.md)：應用交易與 Connect／Kafka 觀察的證據分層、retained evidence 與共用觀察介面。 |
| Critical Matching Event consumers | [Repository verification 紀錄](verification/critical-matching-event-consumer-verification.md)、[restart 程序](verification/critical-consumer-restart-certification.md)、[複合 failure 程序](verification/critical-consumer-failure-certification.md)：依各自故障範圍選擇驗證入口。 |
| Gateway admission 與 FIX 整合 | [Gateway admission](verification/gateway-admission-verification.md)：目前契約、驗證方式與有日期的證據；[QuickFIX/J integration evidence](verification/quickfixj-integration-evidence.md)：repository 內 FIX simulator 的整合驗證。 |
| Query 與 runtime market data | [Query certification](verification/query-service-certification.md)、[Market-data projection certification](verification/runtime-market-data-projection-certification.md)：各自公開介面的本機部署驗收。 |
| 認證 phase 與證據重用 | [Phase DAG 設計](verification/local-certification-phase-dag.md)、[實作介面與驗證](verification/local-certification-phase-dag-implementation.md)、[重用量測程序](verification/local-certification-reuse-measurement.md)。 |
| 歷史認證證據的可用性 | [Evidence audit](verification/local-certification-evidence-audit.md)：2026-10-18 查核快照，區分 r13 完整歷史紀錄、r14 無法確認的整體完成狀態，以及原始檔目前缺失的引用。 |
| Schema 修改與當時驗證 | [Cleanup record](verification/schema-contract-cleanup-record.md)、[Follow-up record](verification/schema-contract-followup-record.md)、[Hardening record](verification/schema-contract-hardening-record.md)：不同來源批次的 schema 決策與檢查紀錄。 |

### 能力狀態與演進計畫

- [Phase 1 能力清單](status/phase-1-capability-inventory.md)：比較目標與目前實作、尋找未完成的能力和驗收依據。
- [Gateway session-aware scale-out plan](plans/quickfix-gateway-session-scale-out.md)：區分已有的 same-owner 基礎與未來 multi-owner／standby 工作；相關工作狀態仍回到 owning Issue。

### AI 協作指引

AI 協作者先依根目錄 [AGENTS.md](../AGENTS.md) 執行 repository 工作；需要下列背景時，讀取對應指引：

- 進行領域探索、命名或決策修改時，讀[領域文件規則](agents/domain.md)。
- 查找或同步工作狀態時，讀 [Issue tracker](agents/issue-tracker.md)；處理 triage 時再讀 [label 對應](agents/triage-labels.md)。
- 執行 Compose、Docker、kind 或 Kubernetes 部署測試前，讀[部署測試 lessons](agents/deployment-test-lessons.md) 並完成適用的 preflight。

## 依系統主題交叉查找

先從該主題的目標規格確認責任和契約，再按工作需要選擇實作參考、操作或驗證入口；所有主題的 capability 現況都回到 [Phase 1 能力清單](status/phase-1-capability-inventory.md)。

| 主題 | 目標架構與契約入口 | 實作、操作或驗證入口 |
| --- | --- | --- |
| 系統邊界與主要資料流 | [System boundaries](../services/docs/architecture/system-boundaries.md)、[Eventing／CQRS](../services/docs/architecture/eventing-and-cqrs.md) | [CONTEXT.md](../CONTEXT.md)、[新人導覽](onboarding/README.md) |
| 每日 Market Reference 與 routing | [Routing artifact／projection](../services/docs/architecture/routing-policy-projection.md) | [Artifact 契約](reference/market-reference-artifact-contract.md)、[官方來源](reference/market-reference-official-source-contract.md)、[核准流程](operations/market-reference-approval-workflow.md) |
| FIX Gateway 與 admission | [Gateway 規格](../services/quickfix-gateway/docs/README.md)、[Gateway admission operations](../services/docs/platform/gateway-admission-operations.md) | [Admission 驗證](verification/gateway-admission-verification.md)、[QuickFIX/J 整合](verification/quickfixj-integration-evidence.md)、[Scale-out 計畫](plans/quickfix-gateway-session-scale-out.md) |
| Risk 與 Account | [Risk 規格](../services/risk-service/docs/README.md)、[Account 規格](../services/account-service/docs/README.md)、[v2 contracts](../services/docs/contracts/v2-domain-contracts.md) | [Risk admission](reference/durable-risk-admission.md)、[Account authority](reference/account-reservation-authority.md)、[Reservation 驗證](verification/account-reservation-v2-live-verification.md) |
| Native Matching、Kafka 與恢復 | [Matching ingress](../services/docs/architecture/matching-ingress.md)、[Matching fleet recovery](../services/docs/platform/matching-fleet-recovery.md)、[Kafka events](../services/docs/contracts/kafka-events.md) | [Kafka durability profile](reference/kafka-matching-durability-profile.md)、[Risk-to-Matching E2E](verification/risk-matching-command-e2e.md)、[Consumer failure 程序](verification/critical-consumer-failure-certification.md) |
| Projection、Query 與市場資料 | [Persistence 規格](../services/persistence/docs/README.md)、[Query 規格](../services/docs/query-service/README.md)、[Market-data rebuild](../services/docs/platform/marketdata-projection-rebuild.md) | [Query 驗證](verification/query-service-certification.md)、[Runtime projection 驗證](verification/runtime-market-data-projection-certification.md) |
| DB、交易與 CDC | [Database topology](../services/docs/platform/database-architecture.md)、[Consistency／recovery 契約](../services/docs/platform/consistency-recovery-identity-and-errors.md) | [交易政策](reference/transaction-and-consistency-policy.md)、[DB 實作指引](development/database-implementation-guide.md)、[CDC verification](verification/cdc-publication-verification.md) |
| 本機環境、部署與驗證 | [Development environment](../services/docs/platform/development-environment.md)、[Deployment](../services/docs/platform/deployment.md)、[Testing](../services/docs/platform/testing.md) | [最小本機開發](onboarding/local-development.md)、[操作手冊](operations/operation-manual.zh-TW.md)、[完整本機認證](verification/local-production-like-certification.md) |

## 架構決策、研究與歷史

ADR 保存選擇理由與取代關係。閱讀各篇的 status、context 與後續決策；其中 context 描述的是作成決策當時的條件。

| 決策主題 | ADR |
| --- | --- |
| Java 品質與介面 | [0001 PMD adoption](adr/0001-pmd-quality-policy.md)、[0002 Domain values](adr/0002-domain-values-for-wide-call-boundaries.md)；現行品質規則見 [PMD policy](reference/pmd-quality-policy.md)。 |
| Account／Risk aggregate ownership | [0003 Account roots](adr/0003-separate-account-authority-aggregate-roots.md)、[0004 Risk Admission root](adr/0004-risk-admission-aggregate-root.md) |
| Market Reference 決策鏈 | [0005 Routing Policy](adr/0005-market-reference-routing-policy.md)、[0006 One policy per day](adr/0006-one-routing-policy-per-trading-day.md)、[0008 Offline artifact](adr/0008-offline-market-reference-artifact.md)；0005／0006 已被後續決策取代。 |
| Cutover 與 runtime ownership | [0007 Retire pre-release compatibility](adr/0007-retire-pre-release-compatibility.md)、[0009 Kafka-journaled Matching](adr/0009-kafka-journaled-lmax-matching.md)、[0010 Fixed fleet／Gateway admission](adr/0010-fixed-matching-fleet-and-admission.md) |
| 認證邊界 | [0011 Local certification／environment promotion](adr/0011-local-production-like-certification-boundary.md) |

研究文件保留當時的選型依據：[多節點 kind 的 Kafka／Debezium 拓撲研究](research/kafka-debezium-multinode-kind.md)與 [kind worker loss 的儲存／恢復判斷](research/kind-storage-worker-loss.md)。採用前應核對研究日期、適用條件與現行 executable 配置。

[Archive 索引](archive/README.md) 逐份說明歷史文件的歸檔理由與現行替代來源，包含舊 Phase 0／1／4／5 紀錄、field-typing gates、Taiwan master plan、舊重構計畫、品質研究、文件整理及 Kubernetes startup 調查。歷史日期、來源版本與失敗／阻擋結果按原有範圍閱讀；其中保留的靜態 acceptance sections 仍有其檢查用途。

根目錄三個舊入口保留相容轉址：[Parameter-safety](domain-parameter-safety-refactor.md)、[Taiwan refactor plan](taiwan-event-driven-refactor-plan.md)、[Kubernetes workload startup](local-production-like-kubernetes-workload-startup.md)。建立新連結時使用它們指向的 canonical 文件。
