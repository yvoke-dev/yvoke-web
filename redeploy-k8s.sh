#!/usr/bin/env bash
# Kept as an entry point for muscle memory; the real logic lives in redeploy.sh.
# Use `./redeploy.sh all` to build Maven once and deploy local + k8s together.
set -euo pipefail
exec "$(dirname "$0")/redeploy.sh" k8s "$@"
