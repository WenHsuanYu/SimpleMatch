# Retire pre-release compatibility after repository cutover

Status: accepted.

SimpleMatch is an isolated project under active development. It has no external consumers,
production release, or historical production data. Migration-era contracts, schema branches, and
adapters may be removed after the complete repository migration. Every producer, consumer,
persisted representation, test, and compatibility inventory must use the replacement path first.
Source, wire, and schema compatibility with retired paths is not required.

New Risk Admissions must persist the authoritative Routing Policy identity and explicit partition
together. The clean pre-release schema therefore does not represent policy-less or unassigned
Admissions.

This decision retains legitimate boundaries that remain necessary:

- FIX anti-corruption adapters.
- Gateway WAL mapping and versioning.
- Translators between bounded contexts.
- Compatibility checks for contracts that remain active.
