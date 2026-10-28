# Certification reuse alternatives

This assessment led to the category C reduction on 2026-10-28.
The owner selected ordinary build-tool caches and fresh execution of selected certification phases.
Custom historical PASS reuse, same-run resume, output lineage, and reuse measurement retired together.

The [contributor workflow comparison](certification-user-workflow-options.md) contains the verified Pulsar, Strimzi, and Spark Kubernetes Operator examples.
The [current execution contract](../verification/local-certification-phase-dag.md) defines the implemented behavior.

A test that changes an input and observes a planner decision tested behavior, even when grep read its output.
Those tests retired because their cache feature retired.
Necessary command failure, current-result, source, image, and resource-ownership checks remain.

The earlier comparison and removed tests remain available in Git at source `6bb24f6fc28edf516c3aa6bcc45abe50fee29af8`.
