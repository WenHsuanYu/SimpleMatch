#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"

# shellcheck source=scripts/lib/local-certification-phase-graph.sh
source "$script_dir/lib/local-certification-phase-graph.sh"
# shellcheck source=scripts/lib/local-certification-fingerprint.sh
source "$script_dir/lib/local-certification-fingerprint.sh"

fail() {
  printf 'artifact fingerprint contract failed: %s\n' "$*" >&2
  exit 1
}

fixture_root="$(mktemp -d)"
trap 'rm -rf "$fixture_root"' EXIT

write_fixture_file() {
  local relative_path="$1"
  mkdir -p "$fixture_root/$(dirname -- "$relative_path")"
  printf 'fixture:%s:v1\n' "$relative_path" >"$fixture_root/$relative_path"
}

for path in \
  build.gradle.kts \
  settings.gradle.kts \
  gradlew \
  gradlew.bat \
  build-logic/plugin.txt \
  shared-java/shared.txt \
  proto/contracts.proto \
  gradle/libs.versions.toml \
  services/account-service/source.txt \
  scripts/build-local-images.sh \
  scripts/publish-local-images.sh \
  scripts/validate-matching-producer-contract.sh \
  scripts/lib/local-image-inventory.sh \
  scripts/lib/local-image-transport.sh \
  scripts/lib/local-registry.sh \
  scripts/lib/matching-topic-profile.sh \
  scripts/lib/local-certification-images.sh \
  scripts/lib/local-certification-kafka.sh \
  scripts/lib/local-certification-artifacts.sh \
  scripts/lib/local-certification-fingerprint.sh \
  scripts/testdata/matching-topic-profile/profile.txt \
  config/kafka/producer.properties; do
  write_fixture_file "$path"
done
chmod 0755 "$fixture_root/gradlew"
git -C "$fixture_root" init -q
git -C "$fixture_root" add .
repo_root="$fixture_root"
image_tag=local

_certification_spring_toolchain_identity() {
  printf '%s\n' \
    'builder\ttest-builder@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' \
    'runImage\ttest-run@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb' \
    'pullPolicy\tALWAYS'
}

simplematch_local_image_inventory_entry() {
  case "$1" in
    account-service)
      printf '%s\n' 'spring|account-service|services/account-service|account-service'
      ;;
    *) return 1 ;;
  esac
}

simplematch_local_image_inventory_source_image() {
  printf '%s:%s\n' "$1" "$2"
}

certification_source_image_identity() {
  printf '%s\n' 'sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc'
}

simplematch_registry_endpoint() {
  printf '%s\n' 'localhost:5001'
}

image_before="$(certification_image_input_fingerprint account-service)" ||
  fail 'application image fingerprint could not be calculated'
registry_before="$(certification_phase_fingerprint registry-publish/account-service)" ||
  fail 'registry publication fingerprint could not be calculated'
kafka_before="$(certification_phase_fingerprint kafka-producer-contract)" ||
  fail 'Kafka producer fingerprint could not be calculated'

printf '%s\n' 'fixture:artifact-seam:v2' \
  >"$fixture_root/scripts/lib/local-certification-artifacts.sh"

image_after="$(certification_image_input_fingerprint account-service)" ||
  fail 'application image fingerprint could not be recalculated'
registry_after="$(certification_phase_fingerprint registry-publish/account-service)" ||
  fail 'registry publication fingerprint could not be recalculated'
kafka_after="$(certification_phase_fingerprint kafka-producer-contract)" ||
  fail 'Kafka producer fingerprint could not be recalculated'

[[ "$image_before" != "$image_after" ]] ||
  fail 'artifact seam change did not invalidate application image evidence'
[[ "$registry_before" != "$registry_after" ]] ||
  fail 'artifact seam change did not invalidate registry publication evidence'
[[ "$kafka_before" != "$kafka_after" ]] ||
  fail 'artifact seam change did not invalidate Kafka producer evidence'

printf '%s\n' 'Artifact adapter fingerprint contracts are valid.'