#!/usr/bin/env bash
# Deploy to local docker compose AND Kubernetes.
# Thin entry point; the real logic lives in redeploy.sh.
# Maven builds only for the local stack: the k8s half applies images CI already published.
set -euo pipefail
exec "$(dirname "$0")/redeploy.sh" all "$@"
