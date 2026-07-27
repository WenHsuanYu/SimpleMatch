# README and Target Documentation Refactor

## Problem Statement

SimpleMatch's root README currently combines a project introduction with a large
set of target-architecture specifications, protocol details, operational
guidance, and implementation-oriented material. A reader cannot quickly tell
what the project is, where the intended architecture is documented, which
specification is authoritative, or whether a linked document describes a target
design or execution status.

The project is a monorepo and will remain one. Its cross-cutting specifications
need a durable home alongside the services, while service-owned specifications
need to remain close to their owning service. Moving the material must preserve
inbound documentation links and must not duplicate maintained specifications.

## Solution

Make the root README a concise introduction to SimpleMatch's intended
architecture. Create a cross-cutting target-documentation root under the
services area, organized into architecture, contracts, and platform concerns;
keep service-owned target specifications under their owning service.

Give every concern one canonical document. Make the cross-cutting documentation
index the stable entry point, use relative Markdown links, and retain forwarding
pages at prior locations when a document with inbound links moves. Keep
repository workflow guidance, implementation plans, phase gates, certification
evidence, and task tracking outside the target-specification tree.

## User Stories

1. As a prospective contributor, I want a short project introduction, so that I can quickly understand SimpleMatch's purpose and intended architecture.
2. As a technical evaluator, I want the README to describe the target architecture without claiming incomplete work is implemented, so that I can assess the project accurately.
3. As a reader of the README, I want one high-level data-flow diagram, so that I can understand the major interactions before reading detailed specifications.
4. As a reader of the README, I want a concise service landscape, so that I can see each service's responsibility and runtime without reading detailed contracts.
5. As a contributor, I want a stable cross-cutting documentation index, so that I can reliably discover target specifications.
6. As an architect, I want cross-cutting architecture concerns grouped together, so that system topology, boundaries, ordering, eventing, and reliability decisions remain coherent.
7. As an integration engineer, I want Kafka, gRPC, FIX, and compatibility rules grouped as contracts, so that I can find the authoritative cross-service behavior.
8. As a platform engineer, I want data model, database topology, configuration, development environment, deployment, and verification guidance grouped together, so that platform concerns have a clear home.
9. As a service owner, I want service-owned target specifications located with the service, so that ownership is obvious and local changes remain discoverable.
10. As a documentation maintainer, I want each technical concern to have one canonical source, so that future changes do not create drift through copied specifications.
11. As a maintainer following an old link, I want a forwarding page after a document moves, so that known inbound references do not fail abruptly.
12. As a documentation author, I want new links to target canonical documents or the stable index, so that forwarding pages remain compatibility-only.
13. As a delivery lead, I want execution plans, phase gates, certification evidence, and task tracking kept separate from target specifications, so that target design is not confused with current implementation status.
14. As a reviewer, I want the documentation refactor to avoid runtime and contract changes, so that a documentation reorganization cannot silently alter product behavior.
15. As a contributor, I want every changed Markdown link checked from the repository's documentation entry points, so that path and heading regressions are found before merge.
16. As a maintainer, I want the README and documentation indexes checked as one repository-level experience, so that readers can navigate from the landing page to every canonical specification.
17. As a future editor, I want the target-documentation taxonomy documented in the specification, so that additions are classified consistently rather than creating an unstructured catch-all area.
18. As a release owner, I want the refactor to be reversible as a documentation-only change, so that it can be rolled back without affecting services, schemas, or builds.

## Implementation Decisions

- The repository remains a monorepo. This work does not create or require additional repositories.
- The root README describes only the intended architecture. Its scope is project purpose, goals and non-goals, one high-level data-flow diagram, a concise service landscape, and a link to the stable documentation index.
- The cross-cutting target-documentation root is organized into three areas: architecture, contracts, and platform.
- Architecture owns system topology, service boundaries, end-to-end data flow, ordering and latency trade-offs, CQRS and eventing posture, and reliability and consistency rules.
- Contracts own cross-service Kafka, gRPC, FIX, and compatibility and versioning rules.
- Platform owns target data model, database topology, configuration, development environment, deployment, observability, testing, and troubleshooting specifications.
- A target specification owned by one service belongs with that service. Cross-cutting documents link to it rather than duplicating it.
- One independently meaningful concern has one canonical document. Document splitting must follow concern ownership, not the old README chapter sequence.
- The cross-cutting documentation index is the stable entry point from the README. It indexes the three areas and their canonical documents.
- Markdown links are relative. A moved document with known inbound links leaves a brief forwarding page at its former location; no new document links target a forwarding page.
- Existing database, configuration, and dependency documents must be classified by content during migration. Target-specification content moves to its canonical concern; repository workflow and execution-state content remains outside the target-documentation tree.
- Existing implementation plans, phase gates, certification evidence, agent guidance, and task tracking remain repository-level execution material.
- The refactor changes documentation organization and wording only. It does not change service behavior, schemas, APIs, build configuration, deployment configuration, or the intended architecture itself.
- No domain glossary or architectural decision record currently constrains this work. The information architecture is documented in this PRD rather than creating an ADR because it is a reversible documentation convention.

## Testing Decisions

- The single highest verification seam is a repository-level Markdown link check that starts from the README and documentation indexes, resolves changed relative targets, and verifies referenced headings. This tests the reader-visible navigation behavior rather than document implementation details.
- The link check must assert that the stable index reaches each canonical target specification, that known moved paths have forwarding pages, and that changed links do not target forwarding pages.
- Documentation review must verify the README's externally visible five-concern boundary and its intended-architecture wording.
- The changed-document set must be checked for duplicated maintained specifications by comparing each migrated concern with its declared canonical document.
- `git diff --check` is a required formatting gate.
- There is no existing Markdown link-checker in this repository, so implementation should add the narrowest repository-level documentation verifier needed for this refactor. It must not inspect or require Java, native, database, or service runtime behavior.
- Existing CI already recognizes documentation-only changes as distinct from Java and native changes; this refactor should preserve that separation. Java, Gradle, Flyway, CMake, smoke, and end-to-end checks are not required unless the implementation unexpectedly changes non-documentation files.

## Out of Scope

- Splitting the monorepo or distributing documentation across separate repositories.
- Re-designing services, data flow, Kafka topics, gRPC APIs, FIX behavior, schemas, or reliability mechanisms.
- Updating runtime code, build logic, CI behavior, deployment configuration, or generated artifacts.
- Converting execution plans, phase gates, certification evidence, or task tracking into target specifications.
- Verifying external websites or links outside the repository as part of the local documentation link seam.

## Further Notes

- The implementation should migrate the README in small reviewable slices: create the stable indexes, create canonical documents, migrate content, add forwarding pages, then reduce the README and update navigation.
- The initial concern inventory includes system boundaries, ordering and latency, eventing and CQRS, reliability and consistency, Kafka events, gRPC APIs, FIX gateway behavior, data model, database architecture, development environment, deployment, and operations and verification.
- The current documentation graph identifies the per-service schema database architecture as a cross-cutting concern. The graph does not model Markdown link topology, so link verification must operate directly on the repository's Markdown files.
- Keep the final refactor in a dedicated documentation-only commit. Reverting that commit restores the prior navigation and content locations without changing runtime behavior.
