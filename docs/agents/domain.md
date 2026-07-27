# Domain Docs

How engineering skills should consume this repo's domain documentation.

## Before exploring, read these

- `CONTEXT.md` at the repository root; or
- `CONTEXT-MAP.md` at the root if it exists, then the relevant context files it points to;
- ADRs in `docs/adr/`.

If these files do not exist, proceed silently. `/domain-modeling` creates them only when real terminology or architectural decisions need recording.

## File structure

This is a single-context repository:

/
├── CONTEXT.md
├── docs/adr/
└── src/

## Use the glossary's vocabulary

Use terms from `CONTEXT.md` in issue titles, refactor proposals, hypotheses, and test names. If a needed concept is absent, reconsider whether the term is already known under another name or note the gap for `/domain-modeling`.

## Flag ADR conflicts

Surface any conflict with an existing ADR rather than silently overriding it.
