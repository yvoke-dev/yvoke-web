#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

# What version THIS working tree is: an exact git tag on a clean tree yields that tag verbatim,
# anything else yields a -SNAPSHOT. It stamps the locally built jar (-Drevision below), which is what
# the sidebar footer and /actuator/info report while developing.
#
# It does not CHOOSE a release version and it no longer names an image. Releases are cut by
# .github/workflows/release.yml, which bumps the newest tag; this reports what the tree already is,
# which is why that workflow runs this very script as its final gate — the check is only worth
# anything because it exercises the derivation a local build would use.
VERSION="$(./release-version.sh)"

NAMESPACE="${NAMESPACE:-yvoke}"

TARGET="local"
RUN_TESTS=1

usage() {
  cat <<'EOF'
Usage: ./redeploy.sh [local|k8s|all] [options]

Targets:
  local        Maven build -> docker compose build -> down/up          (default)
  k8s          Apply the release that k8s/app/kustomization.yaml declares
  all          local, then k8s

Shortcuts:
  ./redeploy.sh       ==  ./redeploy.sh local
  ./redeploy-k8s.sh   ==  ./redeploy.sh k8s
  ./redeploy-all.sh   ==  ./redeploy.sh all

Options:
  --skip-tests   Maven 'package -DskipUnitTests=true -DskipJsTests=true' instead of
                 'verify -Pit-tests'   (-DskipTests is a no-op here: the pom wires surefire's
                 skipTests to the skipUnitTests property)
  -h, --help     Show this help

Env overrides: NAMESPACE

This script builds and pushes NO images. All three are published by GitHub Actions — the app and
migration images by the Release, the Postgres image by "Publish Postgres Image" — and the tracked
manifest selects which release the k8s target applies. Rollback is therefore a checkout:

    git checkout <previous-version> && ./redeploy.sh k8s

That runs the checked-out tag's OWN copy of this script. Releases cut before the push was removed
(up to 1.0.0) still carry the build-and-push version, which needs `--deploy-only` there instead.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    local|k8s|all) TARGET="$1" ;;
    --skip-tests)  RUN_TESTS=0 ;;
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

# The release the tracked manifest declares — read for the deploy banner, and checked because every
# newTag must agree. They are the code and the schema of one release, and a skew between them is the
# failure with no loud symptom, since Flyway ignores future migrations by default.
if [[ $DO_K8S == 1 ]]; then
  MANIFEST_TAGS="$(awk '/newTag:/ {gsub(/["[:space:]]/, "", $2); print $2}' k8s/app/kustomization.yaml | sort -u)"
  if [[ "$(printf '%s\n' "${MANIFEST_TAGS}" | grep -c .)" -ne 1 ]]; then
    echo "k8s/app/kustomization.yaml does not pin every image to one version:" >&2
    grep -n 'newTag:' k8s/app/kustomization.yaml >&2
    exit 1
  fi
  MANIFEST_TAG="${MANIFEST_TAGS}"
fi

# Step counter: 3 for local (Maven + compose build + compose up), 1 for k8s (the apply)
TOTAL=0
if [[ $DO_LOCAL == 1 ]]; then TOTAL=$((TOTAL + 3)); fi
if [[ $DO_K8S == 1 ]]; then TOTAL=$((TOTAL + 1)); fi
STEP=0

step() {
  STEP=$((STEP + 1))
  echo ""
  echo "══════════════════════════════════════════"
  echo "  ${STEP}/${TOTAL}  $1"
  echo "══════════════════════════════════════════"
}

# ── Local ─────────────────────────────────────────────────────────────────────
# Maven runs only for the local target now. The k8s target deploys images that CI built from a
# tagged commit, so building a jar here would produce an artifact nothing consumes — and one that,
# on a dirty tree, is not even the code being deployed.
if [[ $DO_LOCAL == 1 ]]; then
  echo "Local build version: ${VERSION}"

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
    ./mvnw verify -Pit-tests -Drevision="${VERSION}"
  else
    # -DskipUnitTests, not -DskipTests: the pom sets surefire's <skipTests>${skipUnitTests}</skipTests>,
    # and a plugin <configuration> value overrides the -DskipTests user property — so --skip-tests ran
    # the entire unit suite while reporting that tests were skipped. -DskipJsTests is separate again,
    # because the JS tier runs through exec-maven-plugin rather than surefire.
    ./mvnw package -DskipUnitTests=true -DskipJsTests=true -Drevision="${VERSION}"
  fi

  step "Docker compose build"
  docker compose build

  step "Docker compose down + up"
  docker compose down
  docker compose up -d
fi

# ── Kubernetes ────────────────────────────────────────────────────────────────
# Apply only. Every image this manifest references is published by GitHub Actions, so there is
# nothing to build here and nothing to push — which is also what makes this path safe to run from
# any machine: it cannot produce an image, so it cannot produce a wrong one.
if [[ $DO_K8S == 1 ]]; then
  step "Deploying to Kubernetes (${MANIFEST_TAG})"
  # The tracked manifest already declares the release, so the deploy is a plain apply — no overlay
  # is generated and nothing is computed here. That is what makes `kustomize build k8s/app` a
  # complete deploy from any machine, and what a pull-based GitOps controller would read.
  # Build and apply manifests (SOPS/KSOPS decryption will be executed)
  kustomize build --enable-alpha-plugins --enable-exec k8s/app | kubectl apply -f -

  # No `kubectl rollout restart` here on purpose: it existed only to force a re-pull of a moving
  # tag. Now the tag changes per release, so the pod template changes and the apply rolls by
  # itself — and an apply that changes nothing correctly reports nothing, which is the signal
  # that tells you whether a deploy actually deployed.
  kubectl rollout status deployment/yvoke-app -n "${NAMESPACE}" --timeout=5m
fi

echo ""
if [[ $DO_LOCAL == 1 ]]; then
  echo "✅ Redeployed successfully to local docker compose"
  docker compose ps
fi
if [[ $DO_K8S == 1 ]]; then
  echo "✅ Deployed ${MANIFEST_TAG} to Kubernetes in namespace: ${NAMESPACE}"
fi
