#!/usr/bin/env bash
# Kept as an entry point for muscle memory; the real logic lives in redeploy.sh.
# Applies the release k8s/app/kustomization.yaml declares — builds and pushes nothing.
# Use `./redeploy.sh all` to rebuild the local stack and deploy k8s together.
set -euo pipefail
exec "$(dirname "$0")/redeploy.sh" k8s "$@"
