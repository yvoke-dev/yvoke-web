#!/usr/bin/env bash
# The single derivation of the build version. Prints exactly one line.
#
#   exact tag on a clean tree -> the tag, VERBATIM        (a release: 1.0.0 -> 1.0.0)
#   anything else             -> <describe>-SNAPSHOT      (never a release)
#
# The version is the tag with nothing added and nothing stripped. That is the whole point: the
# image tag, the jar's build-info, the MCP handshake, the CI gate and the rollback command are all
# separate uses of this one string, and any transformation here would be a second derivation that
# can disagree with them. Callers: redeploy.sh and .github/workflows/release.yml.
#
# Every non-release output ends in -SNAPSHOT so "is this a release?" stays a substring test rather
# than a judgement. Note the tree-cleanliness check is `git status --porcelain`, which counts
# UNTRACKED files too — `git describe --dirty` does not, so anything generated into the working
# tree must be git-ignored or every later build derives a SNAPSHOT.
#
# Deliberately not `set -e`: the fallbacks are the point. Outside a work tree (a source tarball, a
# container build context) it still prints a usable version, and it must never abort redeploy.sh,
# which runs under `set -euo pipefail`.
set -uo pipefail
cd "$(dirname "$0")"

# The VALUE matters, not just the exit code: inside a bare repository `git rev-parse` prints
# "false" and exits 0, so testing the status alone would let a treeless checkout through.
[ "$(git rev-parse --is-inside-work-tree 2>/dev/null)" = "true" ] || { echo "0.0.0-SNAPSHOT"; exit 0; }

# `dirty` is assigned in the condition so that a FAILING `git status` is not mistaken for a clean
# tree. That distinction is asymmetric and worth the extra variable: every other branch degrades to
# -SNAPSHOT when git misbehaves, but this one degrades to a RELEASE version — the single output
# this script must never produce by accident.
if exact="$(git describe --tags --exact-match 2>/dev/null)" \
    && dirty="$(git status --porcelain 2>/dev/null)" \
    && [ -z "$dirty" ]; then
  echo "$exact"
  exit 0
fi

# `--always` keeps an untagged or freshly cloned repository building: bare `git describe --tags`
# exits non-zero when no tag is reachable, which is the state this repository is in until the
# first release.
described="$(git describe --tags --always --dirty 2>/dev/null)"
echo "${described:-0.0.0}-SNAPSHOT"
