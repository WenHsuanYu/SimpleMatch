# Retired Shell certification implementation

Issue #202 S5 P5b removed the second 62-phase execution framework.
The former phase graph, root runner, bootstrap, Connect/Kafka/Kubernetes/job/workload
adapters, and generic execution reporter are no longer runnable.
`./gradlew verifyAll` owns complete-local selection.

The final cutover also retired the legacy CDC-focused helpers. The old continuation
could not create valid evidence after the aggregate producer disappeared. Its helpers
checked namespace ownership, image locks, scoped provenance, and phase evidence.
No current Shell helper exposes that historical interface. `verifyCdc` retains
broker/connector transport recovery, not the deployed Risk CDC observer contract.
The original framework is available in Git history.
