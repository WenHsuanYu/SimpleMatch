# Use one Routing Policy per trading day

Status: superseded by ADR 0008; supersedes ADR 0005.

Market Reference publishes exactly one authoritative, immutable Routing Policy for each
Asia/Taipei trading day. Operators may stage and validate the next trading day's complete policy
during non-trading maintenance, but activation and the trading readiness barrier remain separate
decisions. Readiness requires the declared partition topology, complete and unique instrument
assignments, and the required Risk Admission and Matching projections to agree before admission
opens. After that barrier opens, topology and assignments cannot change until the trading day
closes; cross-trading-day reassignment remains allowed. Intraday policy intervals and one-step
stage-and-activate behavior are not supported because they create an unnecessary route-mutation
model inside one continuous trading session.

Staging proves the candidate policy's intrinsic validity and may occur before its declared Kafka
and Matching capacity has been provisioned. Designating the candidate as authoritative is a
separate operation and remains blocked until that capacity has been validated. Each consumer owns
and reports installation readiness for the exact trading day and policy identity; Market Reference
does not report readiness on a consumer's behalf. A repository-owned Operational Coordination
boundary composes policy authority, consumer installation, transport topology, and health into the
trading readiness decision.

If a required dependency fails after trading readiness opens, new admission closes while the
authoritative policy remains immutable. Existing Admissions retain and recover through their
durably recorded policy identity and partition. SimpleMatch does not activate an emergency
replacement policy or reroute work within the same trading day.
