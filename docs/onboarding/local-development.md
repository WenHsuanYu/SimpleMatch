# 本機開發起步

先完成與工作範圍相符的建置和測試，再增加外部服務或部署環境。Java 的 pure contract 測試可先確認 JDK、Gradle 與測試工具鏈；native 的 `dev-debug` 路徑可確認 compiler、vcpkg、CMake 與撮合測試。部署和認證需要額外的資源與前置檢查，另由其操作文件管理。

以下指令都從 repository 根目錄執行。先完成 [系統導覽](README.md)，再讀根目錄 [AGENTS.md](../../AGENTS.md) 與 [開發流程](../development/development-workflow.md)，確認適用於此次任務的規則。

## 選擇工具範圍

| 工作範圍 | 必要工具與設定 | 宣告來源 |
| --- | --- | --- |
| Java shared modules 與 services | JDK 25；使用 repository 的 Gradle Wrapper，目前固定為 9.7.0，無須安裝 system Gradle | [Java conventions](../../build-logic/src/main/kotlin/com/simplematch/gradle/SimpleMatchJavaConventionsPlugin.kt)、[wrapper properties](../../gradle/wrapper/gradle-wrapper.properties) |
| Native Matching | CMake 3.28+、Ninja、支援 C++20 的 GCC 或 Clang、可用且可寫的 vcpkg checkout；設定 `VCPKG_ROOT` | [CMakePresets.json](../../CMakePresets.json)、[CMakeLists.txt](../../CMakeLists.txt)、[依賴指南](../development/dependencies.md) |
| Database、Kafka、service integration 或部署 | 按該測試或操作指南準備所需的 dependency 和 runtime | [測試分層](../../services/docs/platform/testing.md)、[操作手冊](../operations/operation-manual.zh-TW.md) |

第一次執行可能下載 Gradle distribution、Maven dependencies 或 vcpkg ports，因此需要對應的網路存取與磁碟空間。選定的工具鏈與 cache 位置也必須具有適當寫入權限。完整依賴政策以 [依賴指南](../development/dependencies.md) 為準。

## Java：先跑不需要外部服務的 contract 測試

確認目前 shell 使用的 JDK，再執行 Market Reference contract module 的測試：

```bash
java -version
./gradlew :shared-java:market-reference-contract:test
```

這個入口驗證 artifact contract、codec、routing 和 startup validation 等 module 行為，不需要先啟動 PostgreSQL、Kafka 或 Docker。建置本身仍需解析 Gradle plugins 與 Java dependencies。正常完成時應看到 `BUILD SUCCESSFUL`；若輸出為 `UP-TO-DATE`，表示 Gradle 重用目前 inputs 的既有任務輸出，這次沒有重新執行該任務。

測試報告位於 `shared-java/market-reference-contract/build/reports/tests/test/index.html`，原始測試結果位於同 module 的 `build/test-results/test/`。需要了解測試驗證的規則時，讀 [artifact contract](../reference/market-reference-artifact-contract.md)，再對照 [module build 定義](../../shared-java/market-reference-contract/build.gradle.kts) 與該 module 的測試。

接著選自己要修改的 module 執行 focused tests。Service integration tests 可能需要真實 database、Kafka 或 Testcontainers；先查 owning module 的測試和操作說明，再執行整個 service suite。Java 變更的完整驗證順序與 `staticAnalysis` 要求見 [開發流程](../development/development-workflow.md#5-validate-in-the-right-order)。

### 只有預設 cache 無法寫入時才改位置

一般環境使用預設 Gradle user home。若受限環境的預設 cache 是唯讀或無權寫入，可指定可寫的位置：

```bash
GRADLE_USER_HOME=/tmp/simplematch-gradle-cache \
  ./gradlew :shared-java:market-reference-contract:test
```

這只改 Gradle 的 cache 位置，不是 SimpleMatch 的 runtime configuration。新 cache 可能需要重新下載 distribution 與 dependencies；dependency repository 的認證或網路錯誤需要依原始錯誤處理，改 cache 不會修復那些問題。此使用方式也記錄於 [Market Reference builder 指南](../../config/market-reference/README.md#gradle-cache-setting)。

## Native：使用既有 dev-debug preset

先檢查工具版本、compiler 與 vcpkg toolchain：

```bash
cmake --version
ninja --version
c++ --version
test -n "$VCPKG_ROOT"
test -f "$VCPKG_ROOT/scripts/buildsystems/vcpkg.cmake"
test -w "$VCPKG_ROOT"
```

`VCPKG_ROOT` 應指向已準備完成的 vcpkg checkout；設定位置及 Linux system packages 的要求見 [依賴指南](../development/dependencies.md#prerequisites)。確認使用的 compiler 支援 C++20 後，執行正常本機路徑：

```bash
cmake --preset dev-debug
cmake --build --preset dev-debug --parallel
ctest --preset dev-debug
```

三步分別解析依賴並建立 build tree、編譯、執行 CTest。`dev-debug` 的 build tree 在 `out/build/dev-debug/`；成功條件是 configure 與 build 皆成功，CTest 沒有 failed tests。若 configure 已失敗，先處理第一個 toolchain 或 dependency 錯誤，再繼續 build。

`dev-debug` 包含目前核心 native 開發與測試所需的 features；Kafka runtime adapters 另受 `SIMPLEMATCH_ENABLE_MESSAGING` 控制。需要重現 PR gate 時用 `ci-fast`，需要 ASan/UBSan 時用 `ci-sanitize`；`full-native-dev` 啟用更廣的 capability features。各 preset 的精確開關以 [CMakePresets.json](../../CMakePresets.json) 和 [CMakeLists.txt](../../CMakeLists.txt) 為準，選擇前先確認該任務實際需要的範圍。

## 何時需要完整環境

當驗收條件涉及服務之間的連線、database migration、Kafka delivery、restart/replay 或 deployment behavior，再讀 [操作手冊](../operations/operation-manual.zh-TW.md) 和對應的 [驗證文件入口](../README.md)。套用 Docker、Compose、kind 或 Kubernetes resources 前，完成 [deployment-test lessons](../agents/deployment-test-lessons.md) 的適用 preflight；昂貴的本機認證依 [Phase DAG 與 reuse contract](../verification/local-certification-phase-dag.md) 分類 inputs。

最小 contract test 和 native unit tests 只證明各自覆蓋的行為。完整系統是否可部署、是否符合某個 release gate，仍需看對應版本、環境與範圍的證據。完成本頁適用的路徑後，前往 [第一次修改](first-change.md)。
