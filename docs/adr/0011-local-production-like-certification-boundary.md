# Separate Local Certification From Environment Promotion

**Status: accepted**

SimpleMatch treats the repository-owned local production-like gate as the current completion
boundary. The gate must exercise local runtime images. It must exercise production-shaped Kafka,
PostgreSQL, Redis, Debezium, and Kubernetes contracts. It must also exercise restart, replay, and
end-to-end contracts.

Staging and production remain separate deployment templates. Those templates retain placeholders
for registry references, digests, endpoints, CIDRs, and credentials. Local image digests provide
evidence only. They do not serve as approved promotion identities. External registry publication
and production certification therefore do not block the local release inventory.

This separation keeps local verification executable and repeatable. It does not weaken the later
promotion contract. Render a template only after its environment-owned values are supplied.
