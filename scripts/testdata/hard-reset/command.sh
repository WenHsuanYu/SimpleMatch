#!/bin/bash
set -eu

# External CLI doubles only. The real hard-reset and manager scripts run in an
# isolated repository with a PATH that cannot reach host Docker/kind commands.
tool="${0##*/}"
case "$tool:$*" in
  'docker:info'|'docker:system df') exit 0 ;;
  'docker:compose version') exit 1 ;;
  'docker:ps -aq --filter label=io.x-k8s.kind.cluster='*)
    [[ "$HARD_RESET_SCENARIO" != observation-failed ]] || exit 42
    ;;
  'docker:ps -aq --filter label=io.x-k8s.kind.cluster') ;;
  'docker:ps '*|'docker:volume ls -q'|'docker:network ls '*|'docker:image ls '*) exit 0 ;;
  'docker:inspect simplematch-local-registry'|'docker:volume inspect '*) exit 1 ;;
  'docker:inspect '*)
    case "$*" in
      *io.x-k8s.kind.cluster*) printf '%s\n' simplematch-live ;;
      *io.x-k8s.kind.role*)
        if [[ "$HARD_RESET_SCENARIO" == invalid-role ]]; then
          printf '%s\n' invalid
        else
          printf '%s\n' control-plane
        fi
        ;;
    esac
    exit 0
    ;;
  'docker:rm '*)
    printf '%s\n' 'generic container deletion' >> "$HARD_RESET_CALLS"
    printf '%s\n' deleted > "$HARD_RESET_STATE"
    exit 0
    ;;
  'kind:get clusters')
    [[ "$HARD_RESET_SCENARIO" != discovery-failed ]] || exit 42
    if [[ -s "$HARD_RESET_STATE" ]]; then exit 0; fi
    printf '%s\n' simplematch-live
    exit 0
    ;;
  'kind:get nodes --name simplematch-live')
    printf '%s\n' simplematch-live-control-plane
    exit 0
    ;;
  'kind:delete cluster --name simplematch-live')
    printf '%s\n' 'verified kind deletion' >> "$HARD_RESET_CALLS"
    printf '%s\n' deleted > "$HARD_RESET_STATE"
    exit 0
    ;;
  *)
    printf 'unexpected command: %s %s\n' "$tool" "$*" >> "$HARD_RESET_CALLS"
    exit 99
    ;;
esac

# Both cluster-listing forms observe the same fake Docker inventory.
if [[ ! -s "$HARD_RESET_STATE" ]]; then
  printf '%s\n' simplematch-live-control-plane
fi
