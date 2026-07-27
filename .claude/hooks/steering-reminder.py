#!/usr/bin/env python3
# Stop hook: runs check_steering.py once and nudges to update steering docs
# if structural drift is detected. Guards against a Stop-hook loop by only
# firing a single reminder per turn (stop_hook_active).
import json
import os
import subprocess
import sys

try:
    data = json.load(sys.stdin)
except Exception:
    data = {}

# Already reminded once this turn — let Claude stop instead of looping.
if data.get("stop_hook_active"):
    sys.exit(0)

project_dir = os.environ.get("CLAUDE_PROJECT_DIR", os.getcwd())
script = os.path.join(project_dir, ".antigravity", "scripts", "check_steering.py")
if not os.path.exists(script):
    sys.exit(0)

res = subprocess.run(
    [sys.executable, script], capture_output=True, text=True, cwd=project_dir
)
if res.returncode != 0:
    detail = ((res.stdout or "") + (res.stderr or "")).strip()
    print(
        "Steering drift detected (yvoke SDD Phase 5). Update the relevant "
        ".antigravity/steering/*.md docs before finishing:\n\n" + detail,
        file=sys.stderr,
    )
    sys.exit(2)  # exit 2 = block stop this once, surface message to Claude

sys.exit(0)
