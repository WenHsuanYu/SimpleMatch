# Retire pre-release compatibility after repository cutover

Status: accepted.

SimpleMatch is an isolated, actively developed project with no external consumers, production
release, or historical production data. Migration-era contracts, schema branches, and adapters may
therefore be removed after every repository producer, consumer, persisted representation, test,
and compatibility inventory has moved to the replacement path; preserving source, wire, or schema
compatibility with those retired paths is not required. New Risk Admissions must persist the
authoritative Routing Policy identity and explicit partition together, so the clean pre-release
schema does not represent policy-less or unassigned Admissions. This decision does not remove
legitimate long-lived boundaries such as FIX anti-corruption adapters, Gateway WAL mapping and
versioning, bounded-context translators, or compatibility checks for contracts that remain active.
