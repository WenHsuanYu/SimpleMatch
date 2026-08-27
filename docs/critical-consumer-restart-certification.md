# Critical Matching Event Consumer Restart Certification

This procedure adds a repository-owned deployment certification step for the
Account, Persistence, and QuickFIX Gateway consumers of `matching.events`. It is
intentionally narrower than the remaining completion gate for issues #130,
#131, and #132: the procedure proves durable state reconstruction across process
and dependency restarts, but it does not claim successful record production or
consumption while PostgreSQL or Kafka is unavailable.

## Test seam

The test seam is the deployed system interface. The procedure observes only
Kubernetes workload identity, Kafka topic end offsets, and durable PostgreSQL
state. It does not call consumer implementation methods, replace internal
collaborators, or inspect in-memory fields. This keeps the consumer modules
independent of deployment certification and lets internal implementations change
without rewriting the scenario.

The scenario requires an existing disposable namespace created by the local
production-like workflow. It refuses to run against a namespace that does not
have `simplematch.io/lifecycle=disposable`, because the test deliberately
replaces application, PostgreSQL, and Kafka Pods.

## Preconditions

Before running this procedure, the namespace must have processed real
`matching.events` traffic. Each of the three consumers must therefore have at
least one durable partition-progress row. All unresolved quarantine counts and
QuickFIX pending delivery intents must be zero. An idle namespace is rejected so
a successful result cannot be produced without exercising the consumer path at
least once.

Run:

```bash
scripts/run-critical-consumer-restart-certification.sh \
  --namespace <local-production-like-namespace> \
  --evidence-dir out/certification/critical-consumer-restart
```

The evidence directory must be empty. The script captures the baseline, replaces
Account, Persistence, and QuickFIX Gateway Pods, replaces the PostgreSQL Pod,
and then replaces one Kafka broker Pod. After each step it verifies that durable
consumer positions are unchanged and healthy. Kafka `matching.events` end
offsets must also remain unchanged during the explicitly no-traffic dependency
restart windows.

## Evidence and claim boundary

A passing `verdict.json` proves that all three consumers can reconstruct their
durable progress after application Pod replacement, PostgreSQL restart preserves
the consumer state, one Kafka broker replacement preserves the same state, no
unresolved consumer quarantine remains, and no QuickFIX delivery intent is
stranded.

It does not prove that new records are produced while PostgreSQL is unavailable,
that records are consumed while Kafka is unavailable, or that a retained FIX
client session receives resend traffic after disconnect. Those scenarios remain
required before issues #130-#132 can be closed. Recording this distinction is
intentional: repository CI may validate the orchestration contract, but CI is
not deployment evidence for failure behavior that it does not execute.
