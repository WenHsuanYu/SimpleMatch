# README and Target Documentation Refactor Audit

> Archived on 2026-09-04. This is a completed documentation audit, not a current navigation
> authority. See [`docs/archive/README.md`](README.md) for the active replacements.

This execution-side audit records the completion criteria for GitHub issue #9. It is not a target
specification.

## Navigation

The repository Markdown navigation check starts from `README.md` and
`services/docs/README.md`. It resolves local relative file and heading links, then follows each
Markdown target recursively. The accompanying test also requires the stable entry point, each
cross-cutting area, its canonical documents, and the service-owned entry points.

## Canonical target specifications

| Concern                 | Canonical entry point                      |
|-------------------------|--------------------------------------------|
| Architecture            | `services/docs/architecture/README.md`     |
| Cross-service contracts | `services/docs/contracts/README.md`        |
| Platform                | `services/docs/platform/README.md`         |
| Account ownership       | `services/account-service/docs/README.md`  |
| Risk ownership          | `services/risk-service/docs/README.md`     |
| FIX gateway ownership   | `services/quickfix-gateway/docs/README.md` |
| Persistence ownership   | `services/persistence/docs/README.md`      |

The root README is an intended-architecture landing page and links only to the stable
`services/docs/README.md` entry point for technical specifications.

## Compatibility paths

No standalone target document was moved from a known inbound path: the detailed material was
extracted from README sections. Therefore no forwarding page is needed for those section moves.

`docs/database-architecture.md` and `docs/config.md` remain at their existing paths as
implementation and runbook guides. Each links to its canonical target specification under
`services/docs/platform/`, so existing inbound links remain valid without creating a second
maintained target source.

## Audit result

The navigation check and its test pass. The refactor commits change Markdown documentation and the
narrow link-check scripts only; they introduce no runtime, schema, protocol, build, deployment, or
generated-artifact changes.
