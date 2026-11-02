# Archived development contract scripts

S5 retired the completed Phase 5 and Phase 6/7 source and checklist checks.
Their source remains in Git history.
The [S5 disposition](../../docs/plans/shell-test-reduction-s5.md) records remaining owners and intentional verification losses.

The functional transport, rendering, resource-report, live kind, resilience,
and cluster-manager tests remain under `scripts/` because they validate
externally meaningful behavior rather than temporary implementation shape.
`normalize-local-images-for-kind.sh` also remains active because `kind-load`
is still an explicitly supported compatibility fallback.

S5 also retired the transaction Markdown-heading framework and completed Account v2 cutover guard.
Transaction policy review and existing Account/Risk behavior tests remain required.

Category E retired the lifecycle and registry source-check suites.
Existing command tests retain API-failure, manager-refusal, and publisher-lock safety checks.
