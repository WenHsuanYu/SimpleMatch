# Risk Service Submission Refactor Plan（已完成）

## 目前進度

- ~~已收斂驗證順序~~
- ~~已收斂實作順序~~
- ~~已收斂可執行 checklist~~
- ~~已完成第一批純規則切分檔案落地~~
- ~~已完成第一批新增單元測試~~
- ~~已完成第一批新舊測試回歸驗證~~
- ~~已完成第二批 `OutboxRecord` / `SubmissionOutboxFactory` 純轉換切分~~
- ~~已完成第二批新增單元測試~~
- ~~已完成第二批新舊測試回歸驗證~~
- ~~已完成第三批 repository 抽象與 JDBC adapter 切分~~
- ~~已完成第三批新增 repository 測試~~
- ~~已完成第三批 `risk-service` 全測試回歸驗證~~
- ~~已完成第四批 orchestration service 與 store delegation 切分~~
- ~~已完成第四批新增 service 測試~~
- ~~已完成第四批 `risk-service` 全測試回歸驗證~~
- ~~已完成第五批 transport adapter 與 Spring wiring 切換~~
- ~~已完成第五批 gRPC / context / 全測試回歸驗證~~
- ~~已完成第六批舊類別清理~~
- ~~已完成第六批 `risk-service` 全測試回歸驗證~~

## 驗證順序

1. ~~守住既有黑箱／整合行為契約~~
    - 目標：保護 `persist(...)`、gRPC 回應、DB 狀態、outbox 契約、rollback、duplicate-key race path。
    - 目前狀態：已完成 baseline 與回歸執行。
    - 主要測試：
    - `SubmissionServiceIntegrationTest`
        - `RiskGrpcServiceTest`
        - `RiskServiceFlywayMigrationTest`
        - `RiskServiceApplicationTest`

2. ~~補純規則層驗證~~
    - 目標：把驗證規則與 ingress dedup 規則抽成純單元測試，避免後續重構把規則改壞。
    - 目前狀態：已完成第一批。
    - 已完成測試：
        - `SubmissionValidatorTest`

3. ~~補資料轉換層驗證~~
    - 目標：鎖住 outbox payload、headers、message key、event id、aggregate 欄位的生成規則。
    - 目前狀態：已完成第二批。
    - 已完成測試：
        - `SubmissionOutboxFactoryTest`

4. ~~補 persistence adapter 驗證~~
    - 目標：鎖住 `risk_submissions` 與 `outbox` 的 row mapping、insert、find 邏輯。
    - 目前狀態：已完成第三批。
    - 已完成測試：
        - `JdbcSubmissionRepositoryTest`
        - `JdbcOutboxRepositoryTest`

5. 持續做整體回歸
    - 目標：每搬一塊責任後，都要再次驗證整體行為等價。
    - 說明：這一項不是一次性完成，而是在每一刀重構後反覆執行。

## 實作順序

1. ~~建立 submission 規則模型與中介 value object~~
    - 已建立：
        - `SubmissionResult`
        - `SubmissionDecision`
        - `SubmissionValidator`

2. ~~建立 outbox 轉換模型與 factory~~
    - 已建立：
        - `OutboxRecord`
        - `SubmissionOutboxFactory`

3. ~~建立 persistence 抽象與 JDBC 實作~~
    - 已建立：
        - `SubmissionRepository`
        - `JdbcSubmissionRepository`
        - `OutboxRepository`
        - `JdbcOutboxRepository`

4. ~~建立新的 orchestration service~~
    - 已建立：
        - `SubmissionService`
        - `TransactionalSubmissionService`

5. ~~將 transport adapter 改接新 service~~
    - 已修改：
        - `RiskGrpcService`

6. ~~更新 Spring wiring~~
    - 已修改：
        - `RiskServiceConfiguration`

7. ~~清理舊命名與舊類別~~
    - 已清理：
        - `SubmissionStore`
        - `PostgresSubmissionStore`
        - `StoredSubmission`
        - 舊整合測試命名 `PostgresSubmissionStoreTest`

## 第一批已完成的檔案

- ~~
  `services/risk-service/src/main/java/com/simplematch/riskservice/submission/SubmissionResult.java`~~
- ~~
  `services/risk-service/src/main/java/com/simplematch/riskservice/submission/SubmissionDecision.java`~~
- ~~
  `services/risk-service/src/main/java/com/simplematch/riskservice/submission/SubmissionValidator.java`~~
- ~~
  `services/risk-service/src/test/java/com/simplematch/riskservice/submission/SubmissionValidatorTest.java`~~

## 第二批已完成的檔案

- ~~`services/risk-service/src/main/java/com/simplematch/riskservice/outbox/OutboxRecord.java`~~
- ~~
  `services/risk-service/src/main/java/com/simplematch/riskservice/outbox/SubmissionOutboxFactory.java`~~
- ~~
  `services/risk-service/src/test/java/com/simplematch/riskservice/outbox/SubmissionOutboxFactoryTest.java`~~

## 第三批已完成的檔案

- ~~
  `services/risk-service/src/main/java/com/simplematch/riskservice/submission/SubmissionRepository.java`~~
- ~~
  `services/risk-service/src/main/java/com/simplematch/riskservice/submission/OutboxRepository.java`~~
- ~~
  `services/risk-service/src/main/java/com/simplematch/riskservice/store/JdbcSubmissionRepository.java`~~
- ~~
  `services/risk-service/src/main/java/com/simplematch/riskservice/store/JdbcOutboxRepository.java`~~
- ~~
  `services/risk-service/src/test/java/com/simplematch/riskservice/store/JdbcSubmissionRepositoryTest.java`~~
- ~~
  `services/risk-service/src/test/java/com/simplematch/riskservice/store/JdbcOutboxRepositoryTest.java`~~

## 第四批已完成的檔案

- ~~
  `services/risk-service/src/main/java/com/simplematch/riskservice/submission/SubmissionService.java`~~
- ~~
  `services/risk-service/src/main/java/com/simplematch/riskservice/submission/TransactionalSubmissionService.java`~~
- ~~
  `services/risk-service/src/test/java/com/simplematch/riskservice/submission/TransactionalSubmissionServiceTest.java`~~
- ~~
  `services/risk-service/src/main/java/com/simplematch/riskservice/store/PostgresSubmissionStore.java`~~

## 第五批已完成的檔案

- ~~`services/risk-service/src/main/java/com/simplematch/riskservice/grpc/RiskGrpcService.java`~~
- ~~
  `services/risk-service/src/main/java/com/simplematch/riskservice/config/RiskServiceConfiguration.java`~~
- ~~
  `services/risk-service/src/test/java/com/simplematch/riskservice/grpc/RiskGrpcServiceTest.java`~~

## 第六批已完成的檔案

- ~~
  `services/risk-service/src/test/java/com/simplematch/riskservice/submission/SubmissionServiceIntegrationTest.java`~~
- ~~已刪除
  `services/risk-service/src/main/java/com/simplematch/riskservice/store/SubmissionStore.java`~~
- ~~已刪除
  `services/risk-service/src/main/java/com/simplematch/riskservice/store/PostgresSubmissionStore.java`~~
- ~~已刪除
  `services/risk-service/src/main/java/com/simplematch/riskservice/store/StoredSubmission.java`~~

## 可執行 Checklist

### 第一批：純規則切分

- ~~新增 `SubmissionResult`~~
- ~~新增 `SubmissionDecision`~~
- ~~新增 `SubmissionValidator`~~
- ~~新增 `SubmissionValidatorTest`~~
- ~~執行新測試並確認通過~~
- ~~執行既有關鍵整合測試並確認通過~~
- ~~執行 `risk-service` 全測試並確認通過~~

### 第二批：outbox 轉換切分

- ~~建立 `OutboxRecord`~~
- ~~建立 `SubmissionOutboxFactory`~~
- ~~建立 `SubmissionOutboxFactoryTest`~~
- ~~先在新測試中鎖住 payload 與 headers 契約~~
- ~~再執行 `risk-service` 全測試~~

### 第三批：persistence adapter 切分

- ~~建立 `SubmissionRepository`~~
- ~~建立 `JdbcSubmissionRepository`~~
- ~~建立 `OutboxRepository`~~
- ~~建立 `JdbcOutboxRepository`~~
- ~~建立 `JdbcSubmissionRepositoryTest`~~
- ~~建立 `JdbcOutboxRepositoryTest`~~
- ~~執行 repository 測試~~
- ~~執行 `risk-service` 全測試~~

### 第四批：orchestration service 切分

- ~~建立 `SubmissionService`~~
- ~~建立 `TransactionalSubmissionService`~~
- ~~將 transaction / duplicate-key / repository 協調搬入新 service~~
- ~~讓既有 store 整合測試改接 delegated service 路徑~~
- ~~新增 `TransactionalSubmissionServiceTest`~~
- ~~執行 `risk-service` 全測試~~

### 第五批：adapter 與 wiring 切換

- ~~修改 `RiskGrpcService` 改依賴 `SubmissionService`~~
- ~~修改 `RiskServiceConfiguration` 完成組裝~~
- ~~執行 gRPC 測試~~
- ~~執行 context test~~
- ~~執行 `risk-service` 全測試~~

### 第六批：清理舊類別與命名

- ~~刪除或替換 `SubmissionStore`~~
- ~~刪除或替換 `PostgresSubmissionStore`~~
- ~~刪除或替換 `StoredSubmission`~~
- ~~清理 imports、死碼與舊測試命名~~
- ~~執行 `risk-service` 全測試~~

## 驗證命令

```bash
./gradlew :services:risk-service:test --tests '*SubmissionValidatorTest' --tests '*SubmissionOutboxFactoryTest'
./gradlew :services:risk-service:test --tests '*JdbcSubmissionRepositoryTest' --tests '*JdbcOutboxRepositoryTest'
./gradlew :services:risk-service:test --tests '*TransactionalSubmissionServiceTest' --tests '*SubmissionServiceIntegrationTest' --tests '*RiskGrpcServiceTest'
./gradlew :services:risk-service:test --tests '*RiskServiceApplicationTest'
./gradlew :services:risk-service:test
```

## 備註

- 第一批已完成的是「新增規則類別與測試」，不是「已完成重構」。
- 第一批刻意不碰 runtime wiring，不改既有 `PostgresSubmissionStore` 的對外行為。
- 第二批已完成的是「新增 outbox 轉換模型與測試」，同樣尚未把 runtime 實作改接到新 factory。
- 第三批已完成的是「新增 repository abstraction / JDBC implementation / repository
  tests」，同樣尚未把既有交易協調流程改接到這些 repositories。
- 第四批已完成的是「新增 orchestration service 並讓 `PostgresSubmissionStore` 轉成 thin adapter
  delegation」，尚未把
  `RiskGrpcService` 與 Spring bean 組裝直接切到 `SubmissionService`。
- 第五批已完成的是「讓 `RiskGrpcService` 與 Spring bean 組裝直接改接 `SubmissionService`」，但
  `SubmissionStore` /
  `PostgresSubmissionStore` / `StoredSubmission` 的移除仍留到第六批。
- 第六批已完成的是「移除 `SubmissionStore` / `PostgresSubmissionStore` / `StoredSubmission`
  並把整合測試命名收斂到
  `SubmissionServiceIntegrationTest`」，目前 submission refactor 主線已收尾。
- `~~刪節線~~` 表示目前已完成；未劃線項目表示尚未開始或尚未收尾。
