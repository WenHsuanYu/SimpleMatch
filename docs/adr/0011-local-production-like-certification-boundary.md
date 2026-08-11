# Separate Local Certification From Environment Promotion

**Status: accepted**

SimpleMatch treats the repository-owned local production-like gate as the current completion
boundary. It must exercise local runtime images and production-shaped Kafka, PostgreSQL, Redis,
Debezium, Kubernetes, restart, replay, and end-to-end contracts; staging and production remain
separate deployment templates with placeholders for registry references, digests, endpoints, CIDRs,
and credentials. Local image digests are retained as evidence only and are never treated as approved
promotion identities, so external registry publication and production certification do not block the
local release inventory.

This separation keeps local verification executable and repeatable without weakening the later
promotion contract: a template may be rendered only after its environment-owned values are supplied.
