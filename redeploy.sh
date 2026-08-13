#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

# What version THIS working tree is: an exact git tag on a clean tree yields that tag verbatim,
# anything else yields a -SNAPSHOT. The same string stamps the jar (-Drevision below) and tags both
# images, so the code and the schema built here can never disagree about which release they are.
#
# Note this does not CHOOSE a release version — .github/workflows/release.yml does that, by bumping
# the newest tag. This reports what the tree already is, which is why the release workflow runs the
# very same script as its final gate: the check is only worth anything because it exercises the
# derivation a local build would use, rather than a reimplementation of it.
VERSION="$(./release-version.sh)"

# Configuration (k8s targets)
REGISTRY="${REGISTRY:-edipal}"
# NOT overridable. It was, and the override defeated every guard below at once: they all tested TAG
# while the jar was stamped from VERSION, so `TAG=1.0.0 ./redeploy.sh k8s` on a dirty tree built a
# SNAPSHOT jar, wrapped it in release-labelled images and pushed them over the published ones —
# reporting success throughout. An argument that does not exist cannot be misused; deliberate
# throwaway pushes go through ALLOW_SNAPSHOT_PUSH, and rollback is a git checkout.
TAG="$VERSION"
NAMESPACE="${NAMESPACE:-yvoke}"
PG_TAG="${PG_TAG:-16-0.24.0}"
PLATFORM="${PLATFORM:-linux/amd64}"

TARGET="local"
RUN_TESTS=1
DO_PUSH=1
DEPLOY_ONLY=0

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
  --skip-tests   Maven 'package -DskipUnitTests=true -DskipJsTests=true' instead of
                 'verify -Pit-tests'   (-DskipTests is a no-op here: the pom wires surefire's
                 skipTests to the skipUnitTests property)
  --no-push      k8s: build images but skip 'docker push'
  --deploy-only  k8s: skip build and push; apply the release the tracked manifest declares.
                 Rollback is a checkout, because the manifest is what selects the version:
                     git checkout <previous-version> && ./redeploy.sh k8s --deploy-only
  -h, --help     Show this help

Env overrides: REGISTRY, NAMESPACE, PG_TAG, PLATFORM, ALLOW_SNAPSHOT_PUSH, ALLOW_TAG_OVERWRITE

The version is derived by ./release-version.sh (an exact git tag on a clean tree, else a
-SNAPSHOT) and is used verbatim for the jar and for both image tags. It is deliberately NOT
overridable: releases are cut by the Release workflow on GitHub, not from here.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    local|k8s|all) TARGET="$1" ;;
    --skip-tests)  RUN_TESTS=0 ;;
    --no-push)     DO_PUSH=0 ;;
    --deploy-only) DEPLOY_ONLY=1 ;;
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

# --deploy-only applies the release the tracked manifest declares, so it must not rebuild anything:
# the point of a rollback is that the images already exist in the registry, byte for byte. It is a
# k8s-only path; refusing it for `local` keeps it from looking like it did something it cannot do.
if [[ $DEPLOY_ONLY == 1 && $DO_LOCAL == 1 ]]; then
  echo "--deploy-only applies to the k8s target only (local builds from the working tree)." >&2
  exit 1
fi

# The release the tracked manifest declares. Every newTag must agree: they are the code and the
# schema of one release, and a skew between them is the failure with no loud symptom, since Flyway
# ignores future migrations by default. Reading only the first one and calling the equality check
# below "the pair check" would have left that agreement enforced nowhere on this path.
MANIFEST_TAGS="$(awk '/newTag:/ {gsub(/["[:space:]]/, "", $2); print $2}' k8s/app/kustomization.yaml | sort -u)"
if [[ "$(printf '%s\n' "${MANIFEST_TAGS}" | grep -c .)" -ne 1 ]]; then
  echo "k8s/app/kustomization.yaml does not pin every image to one version:" >&2
  grep -n 'newTag:' k8s/app/kustomization.yaml >&2
  exit 1
fi
MANIFEST_TAG="${MANIFEST_TAGS}"

# The registry half of the image reference is overridable while the manifest's is not, so an
# override would push to one repository and deploy from another — images that exist and a cluster
# that cannot pull them.
if [[ $DO_K8S == 1 ]] && ! grep -q "newName: ${REGISTRY}/" k8s/app/kustomization.yaml; then
  echo "REGISTRY='${REGISTRY}' is not what k8s/app/kustomization.yaml deploys from:" >&2
  grep -n 'newName:' k8s/app/kustomization.yaml >&2
  exit 1
fi

# A published tag must identify exactly one set of bytes forever: the manifests pull with
# IfNotPresent, so overwriting a tag leaves nodes serving whatever they already cached. Every
# non-release derivation ends in -SNAPSHOT, which keeps this a substring test rather than a
# judgement call. Checked up front, before the Maven build, so a tree that cannot be released
# fails in a second instead of after a full integration run.
if [[ $DO_K8S == 1 && $DEPLOY_ONLY == 0 && $DO_PUSH == 1 \
      && "${TAG}" == *SNAPSHOT* && "${ALLOW_SNAPSHOT_PUSH:-0}" != 1 ]]; then
  echo "Refusing to push '${TAG}': not an exact git tag on a clean tree." >&2
  echo "Commit and tag first, or set ALLOW_SNAPSHOT_PUSH=1 for a deliberate throwaway push." >&2
  exit 1
fi

# git decides the version; k8s/app/kustomization.yaml records it. They are a source and a mirror,
# so a disagreement means the images about to be built are not the ones the cluster will pull —
# the manifest would deploy a release that was never built, and nothing else in the system checks.
# (Skipped for --deploy-only, whose whole job is to apply a release built earlier.)
if [[ $DO_K8S == 1 && $DEPLOY_ONLY == 0 && "${ALLOW_SNAPSHOT_PUSH:-0}" != 1 \
      && "${TAG}" != "${MANIFEST_TAG}" ]]; then
  echo "Version mismatch: building '${TAG}' but k8s/app/kustomization.yaml pins '${MANIFEST_TAG}'." >&2
  echo "Both newTag values must be set to the release version and committed BEFORE the tag is" >&2
  echo "created, so the tagged commit declares the release it deploys." >&2
  exit 1
fi

# Step counter: 1 Maven step, +2 for local, +3 for k8s (only the apply when --deploy-only)
TOTAL=0
if [[ $DEPLOY_ONLY == 0 ]]; then TOTAL=$((TOTAL + 1)); fi
if [[ $DO_LOCAL == 1 ]]; then TOTAL=$((TOTAL + 2)); fi
if [[ $DO_K8S == 1 && $DEPLOY_ONLY == 1 ]]; then TOTAL=$((TOTAL + 1)); fi
if [[ $DO_K8S == 1 && $DEPLOY_ONLY == 0 ]]; then TOTAL=$((TOTAL + 3)); fi
STEP=0

step() {
  STEP=$((STEP + 1))
  echo ""
  echo "══════════════════════════════════════════"
  echo "  ${STEP}/${TOTAL}  $1"
  echo "══════════════════════════════════════════"
}

echo "Release version: ${VERSION}   (image tag: ${TAG})"

# ── Maven: built once, shared by every target ─────────────────────────────────
# Both the local and the amd64 image only COPY target/yvoke.jar (the pom's <finalName>, so the
# name does not move with the version), so a single Maven run produces the artifact for both
# deployments.
if [[ $DEPLOY_ONLY == 0 ]]; then
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
  if [[ $DEPLOY_ONLY == 0 ]]; then
    # "A published tag is never overwritten" is what lets the manifests pull with IfNotPresent —
    # and it was, until now, stated in four places and enforced in none. Worse, the mirror guard
    # above makes overwriting the ONLY thing this path can do: TAG must equal the version the
    # manifest declares, which the release workflow has normally already published. So probe the
    # registry rather than trusting the rule. Images are published by CI; reaching this code at
    # all means something unusual is happening.
    if [[ $DO_PUSH == 1 && "${ALLOW_TAG_OVERWRITE:-0}" != 1 ]]; then
      for image in yvoke-app yvoke-db-migration; do
        if docker manifest inspect "${REGISTRY}/${image}:${TAG}" >/dev/null 2>&1; then
          echo "Refusing to overwrite ${REGISTRY}/${image}:${TAG}, which is already published." >&2
          echo "Nodes pull with IfNotPresent, so they would keep serving the bytes they cached." >&2
          echo "Cut a new release, or set ALLOW_TAG_OVERWRITE=1 if you truly mean to replace it." >&2
          exit 1
        fi
      done
    fi

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
  fi

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
  echo "✅ Redeployed successfully to Kubernetes in namespace: ${NAMESPACE}"
fi
