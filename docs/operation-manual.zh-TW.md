# SimpleMatch 繁體中文操作手冊

本手冊提供目前 SimpleMatch repository 的實際開發、建置、本機 Kubernetes lab、映像發布、認證、資源觀測與清理流程。目標是讓操作者可以從「剛 clone repository」一路走到可重現的 local production-like certification，而不需要先理解所有內部架構文件。

本文件描述的是 **repository-owned local environment**。`staging` 與 `production` 仍是 promotion template；本機驗證成功不能宣稱為 production certification，也不能把本機 registry、明文 Kafka、kind 儲存或 local image identity 當成正式環境證據。

---

## 1. 先理解三個操作層級

SimpleMatch 的操作流程分成三個層級，先判斷自己正在做哪一種工作，避免把不同邊界混在一起。

### 1.1 日常開發驗證

適合修改 Java、C++、Protobuf、Flyway 或單一服務時使用。這一層主要執行 Gradle、CMake、靜態分析與 repository contract tests，不需要每次都啟動完整 kind cluster。

### 1.2 Repository-owned local lab

正式的本機整合環境是 `simplematch-live` kind cluster，加上 repository-owned local OCI registry。cluster 拓撲固定為一個 tainted control-plane 加三個 worker，並由 repository script 驗證 labels、StorageClass、PV node affinity、registry 整合與 kubelet image GC policy。

這一層是 local production-like certification、Matching E2E、resilience 與資源生命週期驗證的主要環境。

### 1.3 Staging / production promotion template

`deploy/k8s/overlays/staging` 與 `deploy/k8s/overlays/production` 不是本機測試環境。它們要求外部 PostgreSQL、Kafka、Redis、TLS/SASL、Secret、正式 registry digest 與真實 FIX counterparty 等環境條件。不要把 repository 中的 placeholder endpoint、CIDR 或 image name 直接套用到正式環境。

---

## 2. 必要工具與環境

從 repository root 執行本手冊中的命令。

### 2.1 Java / Gradle

Java modules 使用 Gradle Wrapper，repository conventions 目前要求 Java 25 toolchain。一般情況直接使用：

```bash
./gradlew --version
```

若預設 Gradle cache 不可寫，可使用 repository 外的可寫目錄：

```bash
export GRADLE_USER_HOME=/tmp/simplematch-gradle-cache
```

這只改變 Gradle cache 位置，不是 application runtime 設定。

### 2.2 Native toolchain

Native development 需要：

- CMake >= 3.28
- Ninja
- GCC 或 Clang，支援 C++20
- vcpkg
- `VCPKG_ROOT` 指向可寫且可使用的 vcpkg checkout

例如：

```bash
export VCPKG_ROOT="$HOME/vcpkg"
cmake --version
ninja --version
```

不要把唯讀 vcpkg root 當成正常開發配置。CMake configure 會透過 manifest mode 使用 vcpkg，選定的 preset 也會建立自己的 build tree 與 `vcpkg_installed` 狀態。

### 2.3 Local lab 工具

完整 local lab 至少需要：

- Docker Engine / Docker Desktop 可正常存取 daemon
- Docker Compose v2，或相容的 `docker-compose`
- kind
- kubectl
- jq
- `findmnt`
- GNU `timeout`

可先確認：

```bash
docker info
docker compose version
kind version
kubectl version --client
jq --version
findmnt --version
timeout --version
```

### 2.4 Docker 儲存檔案系統

`manage-simplematch-live.sh create` 會檢查 Docker Root Dir 所在的檔案系統。若 Docker data root 位於 `ntfs`、`ntfs3`、`exfat`、`vfat`、`fuseblk`、`cifs` 或 `smb3`，script 會拒絕建立 kind cluster。

原因不是 Docker image 完全不能存在於這些檔案系統，而是 kind node 內的 containerd 需要 Linux filesystem semantics 來建立自己的 overlayfs snapshot。Docker root 應放在 ext4、XFS 或其他適合 Linux container storage 的檔案系統。

檢查目前位置：

```bash
docker info --format '{{.DockerRootDir}}'
findmnt -T "$(docker info --format '{{.DockerRootDir}}')"
```

---

## 3. Repository 主要操作入口

日常操作應優先使用 repository 已提供的入口，不要直接拼接底層 `docker`, `kind` 或 `kubectl` 指令取代管理腳本。

| 目的 | 主要入口 |
| --- | --- |
| Java build / test / analysis | `./gradlew` |
| Native configure / build / test | CMake presets + `ctest` |
| 建立本機映像 | `scripts/build-local-images.sh` |
| 管理 canonical kind cluster | `scripts/manage-simplematch-live.sh` |
| 管理 local OCI registry | `scripts/manage-local-registry.sh` |
| 準備 Kubernetes images | `scripts/prepare-local-kubernetes-images.sh` |
| 發布 images 並產生 digest lock | `scripts/publish-local-images.sh` |
| Render local Kubernetes manifest | `scripts/render-local-kubernetes-manifest.sh` |
| Local production-like gate | `scripts/run-local-production-like-certification.sh` |
| 測量 certification reuse | `scripts/measure-local-certification-reuse.sh` |
| Local resilience | `scripts/run-local-resilience.sh` |
| 資源報告 | `scripts/local-resource-report.sh` |
| 日常清理 | `scripts/simplematch-clean-local-disk.sh` |
| 專案 hard reset | `scripts/hard-reset-local.sh` |

`scripts/archive/` 保存的是重構期間用來固定 shell 結構或 delegation contract 的歷史 development checks，不是正常 operator entry point。

---

## 4. Java 日常開發

### 4.1 優先執行 focused task

修改單一 service 時，先跑該 module 的 compile/test，再執行較大的 quality gate。例如修改 Risk：

```bash
./gradlew -q :services:risk-service:test
```

修改 Account：

```bash
./gradlew -q :services:account-service:test
```

QuickFIX runtime 有額外 certification tests：

```bash
./gradlew --no-daemon \
  :services:quickfix-gateway:test \
  :services:quickfix-gateway:certificationTest
```

### 4.2 Static analysis

Repository-level Java quality gate：

```bash
./gradlew --no-daemon -q staticAnalysis
```

此 gate 由 repository build logic 統一 Checkstyle、PMD、SpotBugs 與 Error Prone policy。不要因為單一 module 可編譯就跳過 quality gate。

### 4.3 Dependency lock

Gradle dependency locks 是 repository contract。一般開發不應手動修改 lockfile；只有 dependency upgrade 或 build-tool resolution 改變時才使用 `--write-locks`，並逐一 review diff。

---

## 5. Native C++ 日常開發

### 5.1 一般開發 preset

```bash
export VCPKG_ROOT="$HOME/vcpkg"
cmake --preset dev-debug
cmake --build --preset dev-debug --parallel
ctest --preset dev-debug --output-on-failure
```

`dev-debug` 是一般 native development policy。

### 5.2 完整 native capability preset

需要驗證完整 native dependency set 時：

```bash
cmake --preset full-native-dev
cmake --build --preset full-native-dev --parallel
ctest --preset full-native-dev --output-on-failure
```

### 5.3 CI-oriented presets

`ci-fast` 使用較小的 dependency closure；`ci-sanitize` 在同一類 policy 上加入 ASan/UBSan。不要把 CI preset 和本機 `dev-debug` 的 dependency policy 視為完全相同。

---

## 6. Market Reference artifact

Risk 與 Matching 必須使用同一份核准的 Market Reference identity。Local production-like certification 也需要對應 trading day 的 delivery manifest。

Builder entry point：

```bash
./gradlew :tools:market-reference-builder:run --args='...'
```

### 6.1 建立 review-only candidate

使用已捕捉的官方來源：

```bash
./gradlew :tools:market-reference-builder:run \
  --args='candidate \
    --trading-day 2026-08-11 \
    --source-dir /secure/captures/2026-08-11 \
    --output-dir /secure/market-reference-review'
```

Candidate 只供 review，不是可部署 artifact。

### 6.2 建立 final artifact

```bash
./gradlew :tools:market-reference-builder:run \
  --args='final \
    --trading-day 2026-08-11 \
    --fetch-live \
    --approved-root /secure/market-reference/approved \
    --approved-by trading-operator'
```

有前一個核准 artifact 時，加入：

```text
--previous-artifact /secure/market-reference/approved/YYYY-MM-DD/market_reference.json
```

Final artifact 會產生 canonical JSON、checksum、approval report 與 delivery plan。Builder 不會自行 apply Kubernetes resource，也不會打開交易 session。

### 6.3 Certification 建議明確指定 trading day

Local certification 的預設 trading day 來自 `Asia/Taipei` 的日曆日期。即使如此，使用核准歷史 artifact 時仍建議總是顯式設定：

```bash
export SIMPLEMATCH_CERTIFICATION_TRADING_DAY=2026-08-11
export SIMPLEMATCH_MARKET_REFERENCE_DELIVERY_MANIFEST=\
tools/market-reference-builder/data/2026-08-11/delivery/manifest.yaml
```

若 artifact 存放在 repository 外，將第二個變數改成實際核准 manifest 路徑。

---

## 7. 建立 canonical local kind cluster

Canonical cluster 名稱是 `simplematch-live`，Kubernetes context 是 `kind-simplematch-live`。

### 7.0 使用指定的 Docker Desktop disk image

若 local lab 必須使用外接 ext4 磁碟上的 Docker Desktop backend，所有 kind、registry
與 cleanup 命令都要明確指向 Desktop socket，不要依賴 Docker CLI 的 current context：

```bash
export DOCKER_HOST=unix:///home/alexyu/.docker/desktop/docker.sock
export KUBECONFIG=/tmp/simplematch-kind-desktop.kubeconfig
export SIMPLEMATCH_LOCAL_REGISTRY_PORT=5002
```

目前 Desktop VM 的 QEMU disk argument 應指向：
`/media/alexyu/250g_ext4/.docker/desktop/vms/0/data/DockerDesktop/Docker.raw`。
`docker info` 顯示的 `/var/lib/docker` 是 VM 內部路徑，不代表另一個 host backend。
建立或清理前可用以下唯讀檢查確認實際 endpoint 與 disk image：

```bash
docker info --format 'Name={{.Name}} Root={{.DockerRootDir}} Driver={{.Driver}}'
ps -eo pid,args | rg 'qemu-system-x86_64.*Docker.raw'
```

同一個 `simplematch-live` 名稱可能同時存在於不同 Docker daemon；因此 reset、create、
verify 與 cleanup 都必須保留上述 `DOCKER_HOST`。若要操作 native `/var/run/docker.sock`，
請另行明確設定該 endpoint 並先完成 inventory，不能用 `env -u DOCKER_HOST` 猜測目標。

### 7.1 首次建立

```bash
bash scripts/manage-simplematch-live.sh create
```

`create` 會：

1. 驗證必要工具與 Docker storage filesystem。
2. 拒絕修改已存在的同名 cluster。
3. 建立或啟動 repository-owned local registry。
4. 建立一個 control-plane、三個 worker 的 kind cluster。
5. 將 registry endpoint 接到每個 kind node 的 containerd registry config。
6. 套用 `simplematch-rwo-pod` StorageClass。
7. 驗證 topology、worker slots 與 kubelet image GC policy。
8. 建立 disposable PVC/Pod probe 驗證 PV node affinity。
9. 等待 probe cleanup 完成。
10. 建立乾淨 resource baseline，預設寫到 `out/local-resource-baseline.json`。

建立後切換 context：

```bash
kubectl config use-context kind-simplematch-live
```

Local production-like runner 會要求目前 context 正好是 `kind-simplematch-live`，不是只要 cluster 存在即可。

### 7.2 驗證既有 cluster

```bash
bash scripts/manage-simplematch-live.sh verify
```

這不是單純 `kubectl get nodes`。它會再次驗證 kind container identity、topology、labels、kubelet image GC、registry、StorageClass 與實際 PV affinity probe。

### 7.3 刪除 cluster

```bash
bash scripts/manage-simplematch-live.sh delete
```

這是 explicit rebuild 操作，不是每次 test 結束都要做。正常 resilience/certification cleanup 應保留 reusable cluster。

`delete` 會保留 local registry cache；下一次 `create` 會建立新的 cluster generation 與新的 resource baseline。

---

## 8. Local OCI registry

Registry 預設：

- logical host: `localhost`
- port: `5001`
- container: `simplematch-local-registry`
- image: `registry:3`
- data volume: `simplematch-local-registry-data`
- Docker network: `kind`

Logical host 故意固定為 `localhost`，避免 local publication workflow 被改成向遠端 registry push。

一般情況不需要手動管理 registry，因為 cluster manager 會處理。故障排查時可使用：

```bash
bash scripts/manage-local-registry.sh create
bash scripts/manage-local-registry.sh connect --cluster simplematch-live
bash scripts/manage-local-registry.sh verify --cluster simplematch-live
```

只驗證 registry 本身：

```bash
bash scripts/manage-local-registry.sh verify --registry-only
```

移除 container 但保留 cache：

```bash
bash scripts/manage-local-registry.sh delete
```

連 data volume 一起刪除：

```bash
bash scripts/manage-local-registry.sh delete --purge-data
```

---

## 9. 建立 local images

### 9.1 先查看 canonical image inventory

```bash
bash scripts/build-local-images.sh --list
```

Inventory 是 build、publication、rendering 與 legacy kind-load 共用的來源，不應另外手動維護一份 image 清單。

### 9.2 建立完整 image set

```bash
bash scripts/build-local-images.sh --tag local
```

Spring Boot images 使用 `bootBuildImage`；Matching、Flyway runner 與 verifier 等使用 repository Dockerfile。

### 9.3 只建立單一 service

```bash
bash scripts/build-local-images.sh --service matching --tag local
```

`--service` 可重複指定。也可使用 `--skip-spring`、`--skip-native`、`--skip-flyway` 或 `--skip-verifier` 縮小範圍。

先查看會執行什麼：

```bash
bash scripts/build-local-images.sh --dry-run
```

### 9.4 BootBuildImage local run-image 問題

若 Docker data migration 或 multi-platform metadata 導致 Paketo run image 無法正常 export，可顯式指定已驗證的 local run image：

```bash
export SIMPLEMATCH_BOOT_RUN_IMAGE='<local-image-reference>'
export SIMPLEMATCH_BOOT_PULL_POLICY=IF_NOT_PRESENT
```

Script 會在 Spring image build 前驗證 local run image/platform。不要用大範圍 Docker prune 當作第一個修復方式。

---

## 10. Image transport：registry 是 default

目前 Kubernetes image transport contract：

```text
registry   # default
kind-load  # compatibility fallback
```

### 10.1 Registry path

完整準備流程：

```bash
bash scripts/prepare-local-kubernetes-images.sh \
  --transport registry \
  --tag local \
  --cluster simplematch-live \
  --image-lock out/local-images.lock
```

Registry path 會驗證 cluster/registry integration、push canonical images，並產生 immutable digest lock。它 **不會** 執行 `normalize-local-images-for-kind.sh`。

### 10.2 Legacy kind-load fallback

```bash
bash scripts/prepare-local-kubernetes-images.sh \
  --transport kind-load \
  --tag local \
  --cluster simplematch-live
```

完整 kind-load fallback 會在需要時 normalize Spring Boot images，再執行 `kind load docker-image`。這條路徑保留是為了 compatibility，不是 registry path 的一部分。

### 10.3 Matching-only transport

```bash
bash scripts/prepare-local-kubernetes-images.sh \
  --transport registry \
  --matching-only \
  --tag local \
  --cluster simplematch-live \
  --image-lock out/matching-images.lock
```

Matching-only 是 renderer 明確支援的 partial profile。不要假設任意單一 service 的 partial lock 都能拿去 render 完整 local overlay。

---

## 11. Digest lock 與 manifest rendering

### 11.1 手動發布 images

通常由 image preparation 或 certification runner 呼叫；需要獨立 debug 時可執行：

```bash
bash scripts/publish-local-images.sh --tag local --output out/local-images.lock
```

Lock 格式：

```text
service|source-image|registry-tag|registry-digest-reference
```

Runtime identity 使用最後一欄的 immutable digest reference，而不是 mutable `:local` tag。

### 11.2 Render local manifest

```bash
bash scripts/render-local-kubernetes-manifest.sh \
  --transport registry \
  --image-lock out/local-images.lock \
  --namespace simplematch-local \
  --output out/simplematch-local.yaml
```

Registry full render 會拒絕殘留的 mutable `:local` application image。輸出檔案採 atomic replacement，render 失敗不應破壞上一份有效輸出。

Kind-load 模式不需要 digest substitution：

```bash
bash scripts/render-local-kubernetes-manifest.sh \
  --transport kind-load \
  --namespace simplematch-local \
  --output out/simplematch-local-kind-load.yaml
```

---

## 12. Local production-like certification

權威入口：

```bash
bash scripts/run-local-production-like-certification.sh
```

一般完整 run 不需要先手動執行 `build-local-images.sh` 或 `prepare-local-kubernetes-images.sh`。Certification runner 會依 Phase DAG 決定哪些 prerequisite 必須 `EXECUTE`、哪些 unchanged content-addressed evidence 可以 `REUSE`、哪些外部 artifact 必須 `REVALIDATE`。跨 run reuse 是 runner 的正常行為，不需要 `--resume`。

建議顯式指定核准的 trading day 與 delivery manifest：

```bash
export SIMPLEMATCH_CERTIFICATION_TRADING_DAY=2026-08-11
export SIMPLEMATCH_MARKET_REFERENCE_DELIVERY_MANIFEST=\
tools/market-reference-builder/data/2026-08-11/delivery/manifest.yaml

kubectl config use-context kind-simplematch-live
bash scripts/manage-simplematch-live.sh verify
bash scripts/run-local-production-like-certification.sh
```

新的完整 run 仍會建立 fresh Kubernetes runtime。Namespace、migrations、topics、Open Barriers、workloads、Matching fleet verification 與 retained provenance 等 runtime-state-dependent phases 不會因 cache 存在就跨 run 重用。

Compose phase 與 Kubernetes local overlay 是不同驗證邊界；Kubernetes workloads 不應被理解為依賴已淘汰的 Compose bridge。

如果完整 run 的前置 phase 都是 PASS，只有 Risk CDC observer 因暫時性 runtime 問題失敗，可對同一份 retained evidence 執行 focused diagnostic：

```bash
bash scripts/run-local-cdc-delivery-focused-diagnostic.sh \
  --evidence-dir out/certification/<retained-run> \
  --timeout-seconds 180
```

它只接受 full proof profile，並從 `run-context` 驗證 namespace/run-id、依賴 phase、immutable image lock、實際 workload image、session ConfigMap 與 PostgreSQL Secret。`run-context` 另外保存兩個 scoped identity：`cdc_runtime_signature` 涵蓋建立保留 namespace 所需的 manifest、Risk CDC runtime、image/fingerprint 與 orchestration 輸入；`cdc_verifier_signature` 涵蓋 observer、fixture 與 focused verifier。無關 source commit 不會改變 runtime signature，因此不必重建 deployment；若只有 verifier signature 改變，會先執行快速 `test-cdc-observer-fixture-contract.sh`，再對同一個 retained runtime 執行 observer。runtime signature、namespace、image、input 或 dependency 任一漂移，或舊 `run-context` 沒有 scoped identity，都會在 observer side effect 前 fail-closed，必須重新建立完整 certification run。

成功結果會標示 `FOCUSED_DIAGNOSTIC` 且 `fullCertification=false`；即使 PASS，也只證明目前 verifier 對 retained runtime 的 CDC observer 結果，不能關閉或升級完整 certification，也不會改寫原本的 phase evidence。

### 12.1 計畫與決策

常用選項：

```text
--tag TAG
--image-transport registry|kind-load
--skip-build
--skip-compose
--skip-kubernetes
--matching-fleet-only
--keep-resources
--resume
--dry-run
```

先檢查計畫：

```bash
bash scripts/run-local-production-like-certification.sh --dry-run
```

`plan.json` 會記錄 machine-readable decision。正常完整 run 的主要 planner decisions 是：

- `EXECUTE`：本次必須真的執行 phase。
- `REUSE`：exact effective inputs 與 immutable outputs 都驗證成功，因此接受既有 PASS evidence。
- `REVALIDATE`：接受既有昂貴結果前，先重新驗證目前外部 artifact 或 registry 狀態。
- `SKIP`：operator 明確省略 requirement；這不是 reuse。

### 12.2 不要把 partial run 當成完整 certification

`--matching-fleet-only` 明確產生 PARTIAL evidence。`--skip-build`、`--skip-compose` 或 `--skip-kubernetes` 也代表 operator 主動省略 requirement。這些選項不會因 cache 中已有舊 PASS 就被升級成完整 certification。

相反地，正常 full run 中由 planner 安全判定的 `REUSE` / `REVALIDATE` 仍可得到 `PASSED`，因為 requirement 仍有可驗證 evidence，而不是被跳過。

### 12.3 Run evidence 與 reusable cache

一般 runner 的預設 run evidence directory：

```text
out/certification/local-production-like/
```

預設 reusable evidence cache：

```text
out/certification-cache/
```

Run evidence 會保存 `run-context`、`plan.json`、`evidence-manifest.json`、各 phase 的 `result.json`、`local-images.lock`、logs 與 report。即使某個 phase 從 cache reuse，該次 run 需要的 evidence 與 artifact identity 仍會 materialize 到 run directory。

因此 cache 是 performance mechanism，不是 retained certification 的 correctness dependency。刪除 reusable cache不應讓已保留的 PASS run失去 dependent certification 所需的 provenance。

### 12.4 `--keep-resources`

這只保留目前 run 擁有的 Compose project 與 Kubernetes namespace，方便現場檢查。不要因為保留資源就改用 prefix 猜測 ownership；namespace 的 `simplematch.io/lifecycle=disposable` label 才是 routine cleanup 的自動刪除依據。

### 12.5 `--resume` 只繼續同一個 interrupted run

Resume 不是跨 run cache reuse，也不是「看到舊 log 就跳過」。必須明確指定原 evidence dir 與 namespace：

```bash
export SIMPLEMATCH_CERTIFICATION_EVIDENCE_DIR='<existing-evidence-dir>'
export SIMPLEMATCH_CERTIFICATION_NAMESPACE='<existing-namespace>'
bash scripts/run-local-production-like-certification.sh --resume
```

Runner 會恢復原本的 `run_id`，並比對 namespace、cluster、trading day、image tag、image transport、source signature 與 proof profile。任一項不一致都會拒絕 resume。

Completion marker只代表「可能可以繼續」，不是無條件跳過phase。PhaseGraph會依每個phase的resume policy決定重新執行、接受run-local result、驗證current state，或在side effect無法安全判定時拒絕resume。Open Barrier這類不可安全重播的phase若留下started-but-not-completed evidence，runner會fail closed，而不是冒險發布第二次。

如果你只是要讓新的完整run利用前一次相同輸入的static/image evidence，不要加`--resume`；cross-run reuse由planner與content-addressed evidence自動處理。

### 12.6 測量 cold/warm reuse

需要量測Phase DAG reuse是否真的降低重複工作時，使用：

```bash
scripts/measure-local-certification-reuse.sh --trading-day YYYY-MM-DD
```

這個wrapper仍然呼叫同一個full production-like runner兩次，不是第二條certification pipeline。Cold run使用空的isolated evidence cache；warm run使用cold建立的cache，但仍建立fresh runtime並重新執行所有`FRESH` phases。

Measurement會檢查warm plan的policy decisions，並以warm wall-clock扣除已記錄FRESH execution time後的residual，保守估計non-FRESH與orchestration overhead。只有non-FRESH wall-clock沒有主導warm run，`acceptanceVerdict`才會是`PASS`。

單一cold/warm pair只產生`IMPROVED`、`UNCHANGED`或`REGRESSED`的wall-clock observation，不是統計效能結論。需要跨機器或長期效能claim時，應在可比較的host load下重複量測或另行定義benchmark。

詳細設計與操作說明：

- `docs/local-certification-phase-dag.md`
- `docs/local-certification-phase-dag-implementation.md`
- `docs/local-certification-reuse-measurement.md`

---

## 13. Local resilience

### 13.1 Static contract profile

```bash
bash scripts/run-local-resilience.sh --profile contract
```

這個 profile 驗證 repository-rendered topology、placement、PDB、resource、dependency、probe 等 contract，不會停止 worker，因此不能當成 runtime resilience evidence。

### 13.2 Full-local profile

```bash
bash scripts/run-local-resilience.sh --profile full-local
```

它使用 canonical `simplematch-live` cluster，建立單一 run-owned disposable namespace，並執行目前可實作的 live scenario families。未實作或環境不支援的 scenario 會保持 `INCOMPLETE`/`UNSUPPORTED`，不會被默認成 pass。

保留 run namespace：

```bash
bash scripts/run-local-resilience.sh --profile full-local --keep-resources
```

Evidence 預設寫入：

```text
out/resilience/<run-id>/
```

### 13.3 依賴生命週期 focused diagnostic

當只需要驗證 PostgreSQL/Redis/Kafka 的 worker-loss 或 Pod restart 行為時，不必重跑完整
`full-local` runner。對一個已存在、具 `disposable` ownership label 的 namespace 執行下列
其中一個 focused diagnostic：

```bash
bash scripts/run-local-resilience-dependencies.sh \
  --component postgresql --namespace <run-namespace>
bash scripts/run-local-resilience-dependencies.sh \
  --component redis --namespace <run-namespace>
bash scripts/run-local-resilience-dependencies.sh \
  --component kafka --namespace <run-namespace>
```

每次執行只注入一個明確的 fault，使用單一 300 秒 monotonic deadline；所有可能阻塞的
`kubectl`、`docker`、`kind`、Pod exec 與等待命令都套用剩餘 deadline 的 bounded timeout；失敗
清理則另有 30 秒 best-effort 上限，並在
`out/resilience/dependencies-<run-id>/` 保存一份 component report。PostgreSQL 報告必須證明
原本的 worker slot、Pod、RWO PVC/PV 與 Flyway 管理的 `risk_service.local_resilience_marker` row
都保留；該 marker 與 observer-owned 的 `risk_service.cdc_delivery_lag` 分離，不可用來偽造
CDC 健康資料。Kafka 報告必須證明
三個固定 ordinal 的 cluster/node identity、RF3 marker、兩個存活 broker 與恢復後 ISR3；
Redis worker-stop 報告必須觀察到新的 Ready Pod 已移到另一個 worker；pod-restart 則要求新的
Pod UID，並明確標示 `emptyDir` state 可遺失。Namespace、
kind cluster、worker container identity 或上述資料契約不一致時會 fail closed，且 cleanup 只
刪除本次 Kafka marker topic。

這是針對 #154/#155 的 focused local diagnostic，不是 `full-local` certification PASS，也
不宣稱跨節點 PVC takeover、production HA 或外部環境認證。完整 baseline、fault-family
編排與 parent #151 的聚合 verdict 仍由後續 runner issues 負責。

---

## 14. Read-only resource report

不要用「看起來大概幾 GB」決定是否 recycle cluster。Repository 使用 clean baseline + growth classification。

### 14.1 查看目前資源狀態

```bash
bash scripts/local-resource-report.sh
```

若 `out/local-resource-baseline.json` 存在，預設會自動比較。

### 14.2 只輸出 JSON

```bash
bash scripts/local-resource-report.sh --json
```

### 14.3 保存 snapshot

```bash
bash scripts/local-resource-report.sh --output out/local-resource-current.json
```

### 14.4 建立 baseline

一般不需要手動做，因為 `manage-simplematch-live.sh create` 會建立 baseline。需要手動建立時，cluster 必須完全 clean/idle：

```bash
bash scripts/local-resource-report.sh \
  --write-baseline out/local-resource-baseline.json
```

Baseline 綁定 **同一個 kind cluster generation**。node Docker container IDs 會形成 fingerprint；cluster 刪掉重建後，舊 baseline 不能拿來當新 cluster 的正常比較基準。

### 14.5 Growth classification

主要結果：

- `NO_CONTAINERD_GROWTH`：沒有觀察到 containerd growth。
- `ACTIVE_WORKLOAD_GROWTH`：cluster 尚有 active/run-owned workload，不能把 growth 當成 idle residue。
- `IDLE_RESIDUAL_GROWTH`：cluster 已 idle，但 containerd 儲存仍高於 baseline，可視為 recycle candidate。

`IDLE_RESIDUAL_GROWTH` 不是自動 delete 指令，也沒有固定 GB threshold。

---

## 15. Routine cleanup

日常清理使用：

```bash
bash scripts/simplematch-clean-local-disk.sh
```

預設行為：

1. 關閉指定的 local production-like Compose project 並移除其 volumes。
2. 找出有 `simplematch.io/lifecycle=disposable` label 的 namespaces。
3. 同步等待 namespace deletion 完成。
4. 確認 run-owned PV claim references 已消失。
5. 只有上述 lifecycle 完成後，才在 kind nodes 執行 unused CRI image prune。
6. 保留 reusable kind cluster、registry cache、daemon-global caches 與其他 Docker projects。

這個順序是 correctness requirement，不是效能優化。Kubernetes API observation 失敗不能被當成「資源不存在」。

### 15.1 看清理前後資源變化

```bash
bash scripts/simplematch-clean-local-disk.sh --report-details
```

### 15.2 同時刪除 canonical cluster

```bash
bash scripts/simplematch-clean-local-disk.sh --delete-cluster
```

這會透過 `manage-simplematch-live.sh delete` 執行 cluster deletion，而不是自行繞過 manager。

### 15.3 清掉 registry data

```bash
bash scripts/simplematch-clean-local-disk.sh --purge-registry
```

### 15.4 Aggressive cleanup

```bash
bash scripts/simplematch-clean-local-disk.sh --aggressive
```

這會碰到 **全 Docker daemon** 的 unused images、volumes 與 builder/buildx caches，可能影響其他專案。日常情況不要開啟。

先預覽：

```bash
bash scripts/simplematch-clean-local-disk.sh --dry-run
```

---

## 16. Hard reset

當 local environment 已嚴重漂移、需要從 repository-owned runtime state 重新開始時才使用：

```bash
bash scripts/hard-reset-local.sh
```

預設 hard reset 會移除：

- canonical `simplematch-live` kind cluster
- canonical local-certification Compose project
- 明確選定的額外 SimpleMatch clusters/projects
- local registry container 與 registry data
- 未被 container 使用的 SimpleMatch-tagged host images
- repository-generated build/evidence state

它 **不會** 預設執行 daemon-global prune，也不會直接刪 containerd snapshot metadata。

### 16.1 一律先 dry-run

```bash
bash scripts/hard-reset-local.sh --dry-run
```

### 16.2 執行時的確認

未指定 `--yes` 時，script 要求輸入：

```text
HARD-RESET-SIMPLEMATCH
```

自動化環境才考慮：

```bash
bash scripts/hard-reset-local.sh --yes
```

### 16.3 保留 registry cache

```bash
bash scripts/hard-reset-local.sh --keep-registry-cache
```

### 16.4 保留 build/evidence state

```bash
bash scripts/hard-reset-local.sh --keep-project-build-state
```

### 16.5 Daemon-global aggressive mode

```bash
bash scripts/hard-reset-local.sh --aggressive-unused-docker
```

這會清除 Docker daemon 上所有 globally unused containers、images、volumes、networks、builder 與 buildx caches，可能破壞其他專案的開發快取。除非你確定 daemon 是專用的，否則不要使用。

### 16.6 Scope ambiguity 會 fail closed

若 Docker daemon 上還存在沒有列入本次 reset scope 的 `simplematch*` kind cluster 或 Compose project，hard reset 會拒絕開始。這是刻意的安全邊界，避免共用 registry/build state 被某一個 runtime 單方面清除。

---

## 17. 建議工作流程

### 17.1 第一次建立完整 local lab

```bash
# 1. 檢查 toolchain
./gradlew --version
export VCPKG_ROOT="$HOME/vcpkg"
cmake --version
docker info
kind version
kubectl version --client

# 2. 建立 canonical cluster + registry + resource baseline
bash scripts/manage-simplematch-live.sh create
kubectl config use-context kind-simplematch-live
bash scripts/manage-simplematch-live.sh verify

# 3. 指定核准的 trading-day artifact
export SIMPLEMATCH_CERTIFICATION_TRADING_DAY=2026-08-11
export SIMPLEMATCH_MARKET_REFERENCE_DELIVERY_MANIFEST=\
tools/market-reference-builder/data/2026-08-11/delivery/manifest.yaml

# 4. 跑完整 local gate；runner 會自動 build/reuse/revalidate images
bash scripts/run-local-production-like-certification.sh

# 5. 觀察 cleanup 後的資源狀態
bash scripts/local-resource-report.sh
```

完整 certification 不需要預先手動建立全套images。若只是開發單一service或診斷image build，才使用第9節的focused image commands。

### 17.2 日常修改單一 Java service

```bash
./gradlew -q :services:risk-service:test
./gradlew --no-daemon -q staticAnalysis
bash scripts/build-local-images.sh --service risk-service --tag local
```

若要做完整 Kubernetes deploy/certification，仍應讓 image publication 產生完整受支援的 lock profile，不要把任意單 service partial lock 當成 full overlay lock。

### 17.3 日常修改 Matching

```bash
cmake --preset dev-debug
cmake --build --preset dev-debug --parallel
ctest --preset dev-debug --output-on-failure
bash scripts/build-local-images.sh --service matching --tag local
```

需要只驗證 Matching transport/fleet 時，可使用 Matching-only image preparation 與 certification profile；其 evidence boundary 必須維持 PARTIAL。

### 17.4 一般測試結束後

```bash
bash scripts/simplematch-clean-local-disk.sh --report-details
```

不要每次都 hard reset，也不要每次都 delete canonical cluster。Reusable cluster 的目的就是保留穩定 lab，並用 lifecycle cleanup + baseline growth 觀察殘留狀態。

---

## 18. 常見故障排查

### 18.1 `create` 說 cluster 已存在

`manage-simplematch-live.sh create` 不會修改既有 cluster。先：

```bash
bash scripts/manage-simplematch-live.sh verify
```

若 verify 通過就繼續使用。若確認需要 rebuild：

```bash
bash scripts/manage-simplematch-live.sh delete
bash scripts/manage-simplematch-live.sh create
```

### 18.2 Docker root 使用 NTFS / ntfs3

不要嘗試繞過 filesystem guard。將 Docker data root 移到 Linux-backed filesystem，再重建 kind cluster。

### 18.3 Certification 說 current context 不正確

```bash
kubectl config current-context
kubectl config use-context kind-simplematch-live
```

Runner 故意要求 canonical context，避免命令誤送到其他 cluster。

### 18.4 Registry verify 失敗

依序檢查：

```bash
bash scripts/manage-local-registry.sh verify --registry-only
bash scripts/manage-local-registry.sh connect --cluster simplematch-live
bash scripts/manage-local-registry.sh verify --cluster simplematch-live
```

不要把 logical registry host 改成 remote hostname；local workflow 只允許 `localhost:<port>`。

### 18.5 Publisher 說 local image 不存在

先查看 inventory：

```bash
bash scripts/build-local-images.sh --list
```

再建立缺少的 image：

```bash
bash scripts/build-local-images.sh --service <service> --tag local
```

### 18.6 Registry render 拒絕 partial lock

這通常不是 renderer bug。Full local overlay 要求完整 lock；目前另外明確支援 Matching-only profile。任意 service subset 不應被默認成有效 runtime identity。

### 18.7 Resource baseline 無法比較

如果 cluster 被刪除重建，generation fingerprint 會改變。建立新 cluster 時讓 manager 重建 baseline，不要強迫舊 baseline 與新 nodes 比較。

### 18.8 Routine cleanup 沒刪掉舊 namespace

Routine cleanup **只**相信 `simplematch.io/lifecycle=disposable` label，不再用 namespace prefix 推測 ownership。歷史 unlabeled namespace 必須先人工確認後標記，或透過明確 cluster rebuild 處理。

### 18.9 Gradle cache 唯讀

```bash
export GRADLE_USER_HOME=/tmp/simplematch-gradle-cache
```

不要把 cache permission 問題誤判成 dependency 或 application 設定問題。

### 18.10 vcpkg root 唯讀

確認：

```bash
printf '%s\n' "$VCPKG_ROOT"
test -w "$VCPKG_ROOT" && echo writable || echo read-only
```

若 root 不可寫，換成使用者擁有的 vcpkg checkout。不要修改 repository 的 CMake policy 來掩蓋 host toolchain 權限問題。

### 18.11 Resume 被拒絕

`--resume` 只允許同一個 retained run繼續。Runner會驗證原本的run identity、source signature、namespace、cluster、trading day、image identity與proof profile；其中任一項改變都應建立新run，而不是繞過resume guard。

即使run context完全相同，某些phase仍會重新執行或重新驗證current state。若不可安全重播的phase留下ambiguous started marker，runner會拒絕resume；這是保護side effect correctness，不是cache failure。

---

## 19. 操作安全規則

1. **Namespace ownership 看 label，不看 prefix。** `simplematch.io/lifecycle=disposable` 是 routine deletion authority。
2. **先等 namespace/PV lifecycle 完成，再 prune node image cache。** 不要平行化這兩個步驟。
3. **Registry runtime identity 使用 digest，不使用 mutable `:local`。** `:local` 只是 source/build identity。
4. **`kind-load` 是 compatibility fallback。** Registry path 不應執行 legacy normalizer。
5. **Cross-run reuse 不需要 `--resume`。** 新run由planner自動驗證content-addressed evidence；`--resume`只延續同一個interrupted run。
6. **`--skip-*` 不是 reuse。** 明確省略required phase會保留`PARTIAL`語意，不可用cache evidence掩蓋。
7. **不要直接刪 containerd snapshot files。** 讓 Kubernetes、kubelet 與 containerd 管理 runtime lifecycle。
8. **Routine cleanup 與 hard reset 是不同工具。** 前者維持 reusable lab；後者是重建手段。
9. **Daemon-global prune 必須是明確 aggressive opt-in。** 不能因為資源目前 unused 就假設屬於 SimpleMatch。
10. **Local pass 不等於 production certification。** Local kind、local registry、local storage 與 bounded workload 只能證明 repository-owned local contract。

---

## 20. 延伸文件

需要理解操作背後的設計與 claim boundary 時，再閱讀以下文件：

- `README.md`：系統目標與 service landscape。
- `docs/dependencies.md`：Java/Gradle、CMake、vcpkg 與 dependency policy。
- `deploy/k8s/README.md`：local/staging/production overlays、Secrets、Matching fleet 與 local cluster contract。
- `docs/local-certification-phase-dag.md`：local certification Phase DAG、reuse policy與evidence architecture。
- `docs/local-certification-phase-dag-implementation.md`：目前PhaseGraph、Planner、fingerprint、evidence與resume Interface。
- `docs/local-certification-reuse-measurement.md`：cold/warm reuse量測方式與結果解讀。
- `docs/local-registry-resource-lifecycle.md`：local registry、digest transport、baseline 與 cleanup 設計。
- `docs/production-live-certification.md`：local gate 與 staging/production certification boundary。
- `config/market-reference/README.md`：Market Reference builder。
- `docs/rm1-risk-matching-command-e2e.md`：Risk-to-Matching accepted-command E2E。
- `docs/rm1-risk-matching-restart-replay.md`：restart / equivalent replay evidence。
- `docs/cdc-publication-verification.md`：transactional outbox 與 CDC publication verification。

新增的 query-service certification 入口：[query-service certification](query-service-certification.md)。
它涵蓋 replay、Redis fallback、quiescent critical-path isolation，以及 query-service 為零副本
期間的一筆公開 FIX IOC active-processing liveness；`PASS` 必須同時具備各階段的結構化證據。

延伸文件也包含 query-service certification runbook，供操作員依 retained evidence 入口執行。

本手冊應保持「操作入口與安全邊界」導向；若 script interface 或 lifecycle ownership 改變，應同步更新本文件，而不是保留已失效的命令範例。
