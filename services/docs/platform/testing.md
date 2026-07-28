# Testing

This is the canonical target specification for SimpleMatch's verification layers.

## Verification layers

- Unit tests cover isolated matching, risk, validation, and encoding rules.
- Integration tests cover service wiring, persistence, messaging, and protocol compatibility where real dependencies are
  needed for confidence.
- Certification and smoke tests exercise externally visible service entry points, including the FIX gateway when it is
  in scope.
- Documentation navigation checks verify the reader-visible target-document paths independently of Java, native,
  database, or service runtime checks.

## Scope discipline

Each change runs the narrowest relevant check first, followed by the affected module or service suite. Java, native, and
Flyway validation remain required when those respective implementation surfaces change; documentation-only work uses its
Markdown navigation and formatting seams instead.

Current commands, CI execution records, phase gates, and certification evidence are execution-state material. They
intentionally remain outside this target specification so the target verification model does not claim a particular run
has happened.
