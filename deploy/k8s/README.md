# Kubernetes Configuration

Spring services import `simplematch-platform-config`, their service ConfigMap, and their service
Secret through Spring Cloud Kubernetes Config Data. Apply the ConfigMaps before starting a workload.
Provision each `{service}-secrets`
Secret outside Git and grant its service account only `get` access to named ConfigMaps and Secrets.

For `staging` and `production`, the Secret must contain
`simplematch.postgres.dsn`; no ConfigMap may define that key. The quickfix-gateway StatefulSet is
the reference deployment: it enables the non-optional Kubernetes Config Data import, selects
`production`, and uses the narrowly scoped RBAC manifest.

The Debezium connector ConfigMap contains only non-sensitive connector settings. Inject
`RISK_SERVICE_POSTGRES_USER` and
`RISK_SERVICE_POSTGRES_PASSWORD` into the Kafka Connect worker from a Secret. After a configuration
change, roll the relevant workload. Configuration reload is intentionally disabled.
