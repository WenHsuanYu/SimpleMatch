# Verification CI scope decision

Owner decision: 2026-10-29, after S0 and before S1.
This decision supersedes the mandatory complete-CI portions of [specification #196](https://github.com/WenHsuanYu/SimpleMatch/issues/196).
Complete local automation remains required.

## Staging assessment

The [S0 resource assessment](shell-test-reduction-s0/resources.md) records a 41.7421875 GiB full-profile request envelope.
Compose and Kubernetes already execute in separate stages.
Splitting their jobs therefore cannot remove that Kubernetes request envelope.
Fifteen concurrent Matching main containers alone request 30 GiB.
Required fleet and isolation scenarios need the complete fixed fleet during their observations.
Splitting those owners into independent jobs would not prove the same scenario.

Focused service and dependency scenarios can run separately.
That reduces CI scope and concurrent demand, while complete local verification retains the full requirement set.
The current source provides no requirement-preserving staging plan that fits every complete scenario on a standard hosted runner.
This is a declared-capacity assessment. It does not measure actual RSS or predict an exact runtime failure.

## Adopted scope

Use focused business verification for push-triggered CI.
Prioritize authenticated HTTP behavior, durable database effects, exact event delivery, and regression cases for changed behavior.
Preserve required static analysis, native checks, migrations, and bounded Kubernetes lifecycle checks where applicable.
S6 must select suites from the requirement ledger and measure each selected job's resource and timeout budget.
Shared infrastructure changes must include every affected selected suite.

Complete CI is no longer an acceptance requirement or a reason to acquire larger runner capacity.
A manual workflow may select bounded business or deployed scenarios with explicit scope.
It must not label their aggregate as complete local certification.
Do not purchase capacity, adopt a self-hosted runner, or weaken local requirements through this decision.
Reconsider complete CI only if a later measured staging design preserves all required scenario topologies within the approved environment.

The S0 artifacts retain their original analysis. Their former complete-CI gate is superseded by this decision.
S1 only records this policy change. S6 owns workflow implementation and final selected-CI acceptance.
