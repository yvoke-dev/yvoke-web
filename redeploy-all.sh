#!/usr/bin/env bash
# Deploy to local docker compose AND Kubernetes from a single Maven build.
# Thin entry point; the real logic lives in redeploy.sh.
set -euo pipefail
exec "$(dirname "$0")/redeploy.sh" all "$@"
