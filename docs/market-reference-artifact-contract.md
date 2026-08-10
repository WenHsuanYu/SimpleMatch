# Market Reference Artifact contract

`market_reference.json` is one canonical JSON envelope shared by Risk and every Matching owner. It
is a startup input, not a Kafka record, database row, or runtime lookup result.

## Identity and envelope

The artifact's identity is:

```text
artifactIdentity = YYYY-MM-DD + ":" + contentSha256
contentSha256    = SHA-256(exact canonical UTF-8 JSON bytes)
```

`contentSha256` is a lowercase 64-character value delivered in a separate
`market_reference.sha256` file. It is deliberately not embedded in the JSON, which avoids a
self-referential checksum. Java verifies the external checksum before parsing, and the native
loader performs the same ordering before a Matching process can become ready.

| Envelope section | Responsibility |
| --- | --- |
| `metadata` | Schema/release version, trading day, routing algorithm version, and source provenance. |
| `marketRules` | Reusable Phase 1 TWD rules, including the 1,000-share board lot and normalized tick table. |
| `marketSnapshot` | Complete company-registry instrument universe, eligibility/reason, market rule, and final price bands. |
| `routingPolicy` | Fixed topology and the one eligible-instrument-to-partition assignment set. |

`PRELIMINARY` candidates contain the same universe, eligibility, rules, and routing but no final
reference/limit prices. They can never be mounted for startup. A `FINAL` artifact requires those
price facts, one route for every eligible instrument, no route for an unsupported one, 15 partitions,
and no partition with more than 150 instruments.

## Stable routing

The algorithm version is `stable-least-loaded-v1`. On a baseline build, eligible instruments sort
by `(venueMic, symbol)` and go to the least-loaded partition; equal loads choose the lower partition
ID. With a prior approved artifact, still-eligible instruments retain their original partition,
removed instruments disappear, and only new eligible instruments use the least-loaded rule. More
than 2,250 eligible instruments fails the build rather than moving routes or growing partitions.

The builder writes a bounded review summary containing partition loads and route changes. The
canonical artifact itself keeps instrument facts and routing assignments in separate sections so a
route cannot be duplicated or disagree in two places.

## Verification implementations

`shared-java:market-reference-contract` owns the canonical codec, checksum, structural validation,
and stable allocator. Its fixture is also consumed by
`matching-engine`'s native `MarketReferenceArtifactLoader` test. The native loader rejects an
invalid checksum before attempting JSON parsing, and rejects a non-final or mismatched trading-day
artifact before a Matching owner may start.

The eventual Risk and Matching startup integrations are deliberately separate work in #126 and
#127. This contract supplies their shared input and fail-closed validator; it does not claim those
runtimes already load it.

## Retention and delivery

An approved final build writes one non-overwritable directory below:

```text
config/market-reference/approved/YYYY-MM-DD/
├── market_reference.json
├── market_reference.sha256
├── approval-report.json
└── delivery/
    └── manifest.yaml
```

The builder chooses delivery only after re-verifying the exact bytes and external checksum:

- at or below 900 KiB, it emits an immutable Kubernetes ConfigMap with binary artifact and checksum
  entries;
- above 900 KiB, it requires a digest-pinned OCI data image and writes its payload/Containerfile
  contract beside the delivery manifest.

Both forms expose `/etc/simplematch/market-reference/market_reference.json` and its sibling
checksum file. The generated manifest is a deployment fragment for the future Risk/Matching
workloads; full Kubernetes rollout ownership is tracked separately in #138.
