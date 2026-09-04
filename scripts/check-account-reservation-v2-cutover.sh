#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

production_caller_paths=(
  services/risk-service/src/main
  services/quickfix-gateway/src/main
  services/persistence/src/main
  services/market-data-projection/src/main
  services/marketdata-publisher/src/main
  services/query-service/src/main
)

if rg -n \
  --glob '*.java' \
  --glob '*.kt' \
  --glob '*.yaml' \
  --glob '*.properties' \
  'com\.simplematch\.contracts\.account\.v1|AccountServiceGrpc\.new' \
  "${production_caller_paths[@]}"; then
  echo "A non-Account production path still calls the Account v1 RPC." >&2
  exit 1
fi

if rg -n \
  --glob '*.java' \
  'AccountGrpcService|com\.simplematch\.contracts\.account\.v1' \
  services/account-service/src/main; then
  echo "Account production source still exposes the retired v1 RPC." >&2
  exit 1
fi

if [[ -e proto/account_service.proto ]]; then
  echo "The retired Account v1 protobuf contract still exists." >&2
  exit 1
fi

if rg -n \
  --glob '*.java' \
  --glob '*.kt' \
  'AccountServiceGrpc|com\.simplematch\.contracts\.account\.v1' \
  services/risk-service/src/main; then
  echo "Risk production source still references the retained Account v1 RPC." >&2
  exit 1
fi

if rg -n \
  --glob '*.java' \
  --glob '*.kt' \
  --glob '*.yaml' \
  --glob '*.properties' \
  'AccountServiceGrpc\.new' \
  services/account-service/src/main; then
  echo "Account v1 client construction must not be added to Account production code." >&2
  exit 1
fi

if ! rg -q 'AccountReservationServiceGrpc\.newBlockingStub' \
  services/risk-service/src/main/java/com/simplematch/riskservice/admission/GrpcAccountReservationClient.java; then
  echo "Risk production wiring does not use the Account v2 reservation stub." >&2
  exit 1
fi

if ! rg -q 'AccountReservationV2GrpcService' \
  services/account-service/src/main/java/com/simplematch/accountservice/grpc/GrpcServerConfiguration.java; then
  echo "Account production wiring does not register the v2 reservation service." >&2
  exit 1
fi

if ! rg -q 'account-service:50051' deploy/k8s/simplematch-platform-configmap.yaml; then
  echo "Kubernetes platform configuration does not expose the Account v2 target." >&2
  exit 1
fi

if ! rg -q -U 'name: SIMPLEMATCH_GRPC_SECURITY_TLS_ENABLED\r?\n\s+value: "true"' \
  deploy/k8s/overlays/secure-java-services-patch.yaml; then
  echo "Secure Kubernetes overlays do not enable gRPC TLS." >&2
  exit 1
fi

echo "Account reservation v2 source and configuration cutover guard passed."
