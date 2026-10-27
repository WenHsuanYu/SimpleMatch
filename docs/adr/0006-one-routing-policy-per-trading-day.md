# Use one Routing Policy per trading day

Status: superseded by ADR 0008. Supersedes ADR 0005.

Market Reference publishes exactly one authoritative, immutable Routing Policy for each
Asia/Taipei trading day. Operators may stage and validate the next trading day's complete policy
during non-trading maintenance. Activation and the trading readiness barrier remain separate
decisions. Before admission opens, readiness requires agreement on three conditions:

- The declared partition topology.
- Complete and unique instrument assignments.
- The required Risk Admission and Matching projections.

After the barrier opens, topology and assignments cannot change until the trading day closes.
Reassignment between trading days remains allowed. The design does not support intraday policy
intervals or a single operation that stages and activates a policy. Those features would add an
unnecessary model for route changes within one continuous trading session.

Staging proves the candidate policy's intrinsic validity. Operators may stage a policy before they
provision its declared Kafka and Matching capacity. Designating the candidate as authoritative
remains a separate operation. That operation remains blocked until capacity validation succeeds.
Each consumer owns and reports installation readiness for the exact trading day and policy
identity. Market Reference does not report readiness on a consumer's behalf.

Operational Coordination is a repository-owned boundary. It combines policy authority, consumer
installation, transport topology, and health to decide trading readiness.

If a required dependency fails after trading readiness opens, new admission closes while the
authoritative policy remains immutable. Existing Admissions retain and recover through their
durably recorded policy identity and partition. SimpleMatch does not activate an emergency
replacement policy or reroute work within the same trading day.
