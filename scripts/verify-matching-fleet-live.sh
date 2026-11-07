#!/usr/bin/env bash
set -euo pipefail

namespace="${SIMPLEMATCH_NAMESPACE:-production}"
statefulset_name="${SIMPLEMATCH_MATCHING_STATEFULSET:-matching}"
kubectl_bin="${KUBECTL:-kubectl}"
allow_shared_node=false
local_image_reference=""

usage() {
  printf '%s\n' \
    'Usage: verify-matching-fleet-live.sh [options]' \
    '' \
    '  --namespace NAME       Kubernetes namespace (default: SIMPLEMATCH_NAMESPACE or production).' \
    '  --statefulset NAME     Matching StatefulSet (default: SIMPLEMATCH_MATCHING_STATEFULSET or matching).' \
    '  --kubectl PATH         kubectl executable (default: KUBECTL or kubectl).' \
    '  --allow-shared-node    Local-only topology mode; permit 15 logical owners to share nodes.' \
    '  --allow-local-image IMAGE' \
    '                         kind-load fallback; require this exact non-digest local image.' \
    '' \
    'The gate requires 15 Ready pods, 15 current Lease holders and 15 Bound' \
    'ReadWriteOncePod PVCs. Registry mode requires real digest-pinned images;' \
    'the explicit kind-load fallback may instead require one exact local image reference.'
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --namespace) namespace="$2"; shift 2 ;;
    --statefulset) statefulset_name="$2"; shift 2 ;;
    --kubectl) kubectl_bin="$2"; shift 2 ;;
    --allow-shared-node) allow_shared_node=true; shift ;;
    --allow-local-image) local_image_reference="${2:?--allow-local-image requires an image reference}"; shift 2 ;;
    --help|-h) usage; exit 0 ;;
    *) usage >&2; printf 'Unknown option: %s\n' "$1" >&2; exit 2 ;;
  esac
done

command -v "$kubectl_bin" >/dev/null 2>&1 || {
  printf 'Cannot find kubectl executable: %s\n' "$kubectl_bin" >&2
  exit 1
}
command -v jq >/dev/null 2>&1 || {
  printf '%s\n' 'jq is required for Kubernetes JSON validation.' >&2
  exit 1
}

temporary_directory="$(mktemp -d)"
trap 'rm -rf "$temporary_directory"' EXIT

"$kubectl_bin" -n "$namespace" get statefulset "$statefulset_name" -o json \
  > "$temporary_directory/statefulset.json"
"$kubectl_bin" -n "$namespace" get pods \
  -l app.kubernetes.io/name=matching -o json \
  > "$temporary_directory/pods.json"
"$kubectl_bin" -n "$namespace" get leases \
  -l app.kubernetes.io/name=matching -o json \
  > "$temporary_directory/leases.json"
"$kubectl_bin" -n "$namespace" get pvc -o json \
  > "$temporary_directory/pvcs.json"

jq --exit-status --null-input \
  --arg namespace "$namespace" \
  --arg stsName "$statefulset_name" \
  --arg allowShared "$allow_shared_node" \
  --arg localImage "$local_image_reference" \
  --slurpfile sts "$temporary_directory/statefulset.json" \
  --slurpfile pods "$temporary_directory/pods.json" \
  --slurpfile leases "$temporary_directory/leases.json" \
  --slurpfile pvcs "$temporary_directory/pvcs.json" '
    def require($ok; $message): if $ok then true else error($message) end;
    def pod_name($i): $stsName + "-" + ($i | tostring);
    def lease_name($i): "matching-partition-" + (if $i < 10 then "0" else "" end)
      + ($i | tostring);
    def pvc_name($i): "matching-baseline-" + pod_name($i);
    def epoch:
      (capture("^(?<utc>[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2})(?<fraction>\\.[0-9]+)?(?<zone>Z|[+-][0-9]{2}:[0-9]{2})$")) as $value
      | (($value.utc + "Z") | fromdateiso8601)
        + (($value.fraction // "0") | tonumber)
        - (if $value.zone == "Z" then 0 else
            (($value.zone[1:3] | tonumber) * 3600
              + ($value.zone[4:6] | tonumber) * 60)
            * (if $value.zone[0:1] == "+" then 1 else -1 end)
          end);
    [range(0;15)] as $ordinals
    | ($pods[0].items // [] | map(select(.metadata.labels["app.kubernetes.io/name"] == "matching"))) as $matching
    | ($leases[0].items // []) as $fleetLeases
    | ($pvcs[0].items // []) as $fleetPvcs
    | [
      require(([$sts,$pods,$leases,$pvcs] | all(.[]; length == 1)); "kubectl must return one JSON document per resource"),
      require(($sts[0].spec.replicas == 15);
        ($namespace + "/" + $stsName + " must declare 15 replicas")),
      require(($matching | length) == 15; "expected exactly 15 Matching pods"),
      require(([$matching[] | .metadata.name] | sort)
        == ($ordinals | map(pod_name(.)) | sort); "Matching pod ordinals differ from 0-14"),
      require(all($matching[]; any(.status.conditions[]?; .type == "Ready" and .status == "True"));
        "every Matching pod must have Ready=True"),
      require(all($matching[]; (.spec.nodeName | type == "string" and length > 0));
        "every Matching pod must have a named node"),
      require(($allowShared == "true") or ([$matching[].spec.nodeName] | unique | length) == 15;
        "the 15 Matching pods must use distinct nodes"),
      require(([$matching[] | .metadata.labels["apps.kubernetes.io/pod-index"]] | sort)
        == ($ordinals | map(tostring) | sort); "Matching pod-index labels must cover 0-14"),
      require(all($matching[];
        (.spec.containers[0].image // "") as $image
        | if $localImage != "" then $image == $localImage
          else ($image | test("@sha256:[0-9a-f]{64}\\z"; "i")
            and (test("@sha256:a{64}\\z"; "i") | not)) end);
        "Matching image is not the exact selected image or a real digest"),
      require(([$fleetLeases[] | .metadata.name] | sort)
        == ($ordinals | map(lease_name(.)) | sort); "expected the 15 fixed Matching Leases"),
      ($ordinals[] as $i
        | ([$matching[] | select(.metadata.name == pod_name($i))] | .[0]) as $pod
        | ([$fleetLeases[] | select(.metadata.name == lease_name($i))] | .[0]) as $lease
        | ([$fleetPvcs[] | select(.metadata.name == pvc_name($i))]) as $pvc
        | require(($pod.metadata.uid | type == "string" and length > 0);
            (pod_name($i) + " lacks a Pod UID")),
          require($lease.spec.leaseDurationSeconds == 15;
            (lease_name($i) + " must use a 15-second Lease")),
          require($lease.spec.holderIdentity == (pod_name($i) + ":" + $pod.metadata.uid);
            (lease_name($i) + " has a different Lease holder or Pod UID")),
          require((try (($lease.spec.renewTime | epoch) + 15 > now) catch false);
            (lease_name($i) + " is expired or has an invalid renewTime")),
          require(($pvc | length) == 1; ("missing or duplicate PVC " + pvc_name($i))),
          require($pvc[0].status.phase == "Bound"; (pvc_name($i) + " is not Bound")),
          require(any($pvc[0].spec.accessModes[]?; . == "ReadWriteOncePod");
            (pvc_name($i) + " must use ReadWriteOncePod"))
      )
    ] | all(.)
  ' >/dev/null

if [[ "$allow_shared_node" == true ]]; then
  node_summary='logical owners may share nodes'
else
  node_summary='15 distinct nodes'
fi
if [[ -n "$local_image_reference" ]]; then
  image_summary="exact kind-load image $local_image_reference"
else
  image_summary='digest-pinned images'
fi
printf 'Matching fleet certification passed: 15 Ready pods, 15 Lease holders, 15 RWOP PVCs, %s, and %s in %s.\n' \
  "$image_summary" "$node_summary" "$namespace"
