#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

# Configuration (k8s targets)
REGISTRY="${REGISTRY:-edipal}"
TAG="${TAG:-latest}"
NAMESPACE="${NAMESPACE:-yvoke}"
PG_TAG="${PG_TAG:-16-0.24.0}"
PLATFORM="${PLATFORM:-linux/amd64}"

TARGET="local"
RUN_TESTS=1
DO_PUSH=1

usage() {
  cat <<'EOF'
Usage: ./redeploy.sh [local|k8s|all] [options]

Targets:
  local        Maven build -> docker compose build -> down/up          (default)
  k8s          Maven build -> amd64 images -> push -> kubectl apply
  all          Maven build ONCE, then deploy local, then k8s

Shortcuts:
  ./redeploy.sh       ==  ./redeploy.sh local
  ./redeploy-k8s.sh   ==  ./redeploy.sh k8s
  ./redeploy-all.sh   ==  ./redeploy.sh all

Options:
  --skip-tests   Maven 'package -DskipTests' instead of 'verify -Pit-tests'
  --no-push      k8s: build images but skip 'docker push'
  -h, --help     Show this help

Env overrides: REGISTRY, TAG, NAMESPACE, PG_TAG, PLATFORM
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    local|k8s|all) TARGET="$1" ;;
    --skip-tests)  RUN_TESTS=0 ;;
    --no-push)     DO_PUSH=0 ;;
    -h|--help)     usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; echo "" >&2; usage >&2; exit 1 ;;
  esac
  shift
done

DO_LOCAL=0
DO_K8S=0
case "$TARGET" in
  local) DO_LOCAL=1 ;;
  k8s)   DO_K8S=1 ;;
  all)   DO_LOCAL=1; DO_K8S=1 ;;
esac

# Step counter: 1 Maven step, +2 for local, +3 for k8s
TOTAL=1
if [[ $DO_LOCAL == 1 ]]; then TOTAL=$((TOTAL + 2)); fi
if [[ $DO_K8S == 1 ]]; then TOTAL=$((TOTAL + 3)); fi
STEP=0

step() {
  STEP=$((STEP + 1))
  echo ""
  echo "══════════════════════════════════════════"
  echo "  ${STEP}/${TOTAL}  $1"
  echo "══════════════════════════════════════════"
}

# ── Maven: built once, shared by every target ─────────────────────────────────
# Both the local and the amd64 image only COPY target/*.jar, so a single Maven
# run produces the artifact for both deployments.
if [[ $RUN_TESTS == 1 ]]; then
  step "Maven clean verify & test (including IT tests)"
else
  step "Maven clean package (tests skipped)"
fi

for i in {1..5}; do
  if rm -rf target/; then
    break
  fi
  echo "Retrying rm -rf target/ ($i/5)..."
  sleep 1
done

if [[ $RUN_TESTS == 1 ]]; then
  ./mvnw verify -Pit-tests
else
  ./mvnw package -DskipTests
fi

# ── Local ─────────────────────────────────────────────────────────────────────
# Local runs before k8s on purpose: if the stack does not come up here, we stop
# before pushing anything to the registry.
if [[ $DO_LOCAL == 1 ]]; then
  step "Docker compose build"
  docker compose build

  step "Docker compose down + up"
  docker compose down
  docker compose up -d
fi

# ── Kubernetes ────────────────────────────────────────────────────────────────
if [[ $DO_K8S == 1 ]]; then
  step "Building Docker images (${PLATFORM})"
  echo "Building App image..."
  docker build --platform "${PLATFORM}" -t "${REGISTRY}/yvoke-app:${TAG}" .

  echo "Building DB migration image..."
  docker build --platform "${PLATFORM}" -t "${REGISTRY}/yvoke-db-migration:${TAG}" -f docker/db/Dockerfile .

  echo "Building Custom Postgres image..."
  docker build --platform "${PLATFORM}" -t "${REGISTRY}/yvoke-postgres-pg_search:${PG_TAG}" ./docker/postgres

  if [[ $DO_PUSH == 1 ]]; then
    step "Pushing Docker images"
    docker push "${REGISTRY}/yvoke-app:${TAG}"
    docker push "${REGISTRY}/yvoke-db-migration:${TAG}"
    docker push "${REGISTRY}/yvoke-postgres-pg_search:${PG_TAG}"
  else
    step "Pushing Docker images (skipped: --no-push)"
  fi

  step "Deploying to Kubernetes"
  # Build and apply manifests (SOPS/KSOPS decryption will be executed)
  kustomize build --enable-alpha-plugins --enable-exec k8s/app | kubectl apply -f -

  # Force restart of the application deployment to pull the latest image if already running
  kubectl rollout restart deployment/yvoke-app -n "${NAMESPACE}"
fi

echo ""
if [[ $DO_LOCAL == 1 ]]; then
  echo "✅ Redeployed successfully to local docker compose"
  docker compose ps
fi
if [[ $DO_K8S == 1 ]]; then
  echo "✅ Redeployed successfully to Kubernetes in namespace: ${NAMESPACE}"
fi
