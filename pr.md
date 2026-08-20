已依你要求的順序完成：**先定修改規格 → 再實作 → 最後建立 Draft PR**。

這次修正的核心規格是：不改 Kafka Connect topology、replica 數、connector config 或既有 timeout，只改善 certification 的 REST orchestration、fail-fast 與 evidence。原本 `/status` polling 使用 `curl -fsS`，connector 剛註冊後短暫 404 會印出 `curl: (22)`，即使後續其實成功；同時 `risk-service-outbox-status.json` 只存了 `jq` 的 `true`，導致 evidence 很難判斷真正狀態。

實作後的行為是：

* HTTP transport error、HTTP status、connector lifecycle state 分開判斷。
* `/status` 的 **404 視為註冊後可重試的暫態狀態**，不再產生誤導性的 `curl: (22)`。
* `risk-service-outbox-status.json` 現在保存**原始 Kafka Connect status JSON**。
* connector/task 一旦進入 `FAILED`，立即停止，不再白等完整 polling timeout。
* malformed JSON、非預期 HTTP status、PUT update failure 都立即失敗。
* 409 existing connector → PUT update 現在也會保存 response 並驗證 HTTP code。
* 真正失敗時會集中 dump：
  `kafka-connect-connectors.json`、registration response、update response、status JSON、port-forward log。

我建立的分支是：

```text
agent/fix-kafka-connect-certification-diagnostics
```

commit：

```text
0b326f54aca9b4b11421ed28cb50192360daac70
```

並已建立 **Draft PR #172**。

另外差異自查時有抓到一個非核心 incidental diff：

```diff
- printf 'REUSE ...' "$phase" "$marker_path"
+ printf 'REUSE ...' "$phase" "$output_path"
```

它只影響 `run_capture --resume` 時顯示哪個路徑，不影響控制流程，但**不在這次修改規格內**，所以我已在 PR 裡明確標出，建議 merge 前還原，保持 PR scope 純粹。

目前這個執行環境沒有 `gh`，也無法透過一般網路 clone private repository，因此無法在這裡實際跑 `bash -n` 或完整 certification。GitHub 目前也尚未回報這個 commit 的 workflow run。你本機 review 時我建議至少先跑：

```bash
bash -n scripts/run-local-production-like-certification.sh

SIMPLEMATCH_CERTIFICATION_EVIDENCE_DIR=/home/alexyu/SimpleMatch/out/certification/rm1-20260817 \
SIMPLEMATCH_CERTIFICATION_NAMESPACE=simplematch-rm1-20260817 \
scripts/run-local-production-like-certification.sh --resume
```

修正後，如果只是 Kafka Connect distributed state 尚未同步完成，log 應該**不再出現假的 `curl: (22) 404`**；如果是真的 connector failure，則會留下完整原始 status，例如 `FAILED` state、task trace，以及當下 connector inventory，而不是只有 `true`。

