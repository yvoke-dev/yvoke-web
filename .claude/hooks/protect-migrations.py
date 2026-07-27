#!/usr/bin/env python3
# PreToolUse hook (Edit|Write): blocks modifying an EXISTING Flyway migration.
# New V<N>__<name>.sql files are allowed; overwriting/editing existing ones is not.
import json
import os
import re
import sys

try:
    data = json.load(sys.stdin)
except Exception:
    sys.exit(0)

if data.get("tool_name", "") not in ("Edit", "Write"):
    sys.exit(0)

file_path = data.get("tool_input", {}).get("file_path", "")
if not file_path:
    sys.exit(0)

norm = file_path.replace("\\", "/")
if re.search(r"docker/db/migration/V.*\.sql$", norm) and os.path.exists(file_path):
    print(
        f"BLOCKED: '{file_path}' is an existing Flyway migration. "
        "Never modify an existing migration script (yvoke hard rule). "
        "Create a new docker/db/migration/V<N>__<name>.sql instead.",
        file=sys.stderr,
    )
    sys.exit(2)  # exit 2 = deny the tool call, feed stderr back to Claude

sys.exit(0)
