#!/usr/bin/env python3
# .antigravity/scripts/check_steering.py
# Verifies if structural codebase changes occurred that require updating steering docs.

import os
import subprocess
import re
import sys

def get_git_changes():
    # Check if we have HEAD (non-empty repository check)
    has_head = subprocess.run(["git", "rev-parse", "HEAD"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0
    if not has_head:
        # Brand new repo, compare status
        res = subprocess.run(["git", "status", "--porcelain"], capture_output=True, text=True)
        changed = []
        for line in res.stdout.splitlines():
            if not line:
                continue
            path = line[3:].strip()
            # Handle renamed files (e.g. R  old_path -> new_path)
            if ('R' in line[:2]) and ' -> ' in path:
                path = path.split(' -> ')[1].strip()
            changed.append(path)
        return changed
        
    # Get tracked modified & staged changes
    res_diff = subprocess.run(["git", "diff", "--name-only", "HEAD"], capture_output=True, text=True)
    tracked_changes = [line.strip() for line in res_diff.stdout.splitlines() if line]

    # Get untracked files
    res_untracked = subprocess.run(["git", "ls-files", "--others", "--exclude-standard"], capture_output=True, text=True)
    untracked_changes = [line.strip() for line in res_untracked.stdout.splitlines() if line]

    return list(set(tracked_changes + untracked_changes))

def get_documented_packages(structure_content):
    lines = structure_content.splitlines()
    start_idx = -1
    for i, line in enumerate(lines):
        if "java/de/palsoftware/yvoke/" in line:
            start_idx = i
            break
    if start_idx == -1:
        return set()
        
    start_line = lines[start_idx]
    start_indent_str = start_line.replace('│', ' ')
    m_start = re.search(r'([a-zA-Z0-9_\-]+)/', start_line)
    if not m_start:
        return set()
    start_folder = m_start.group(1)
    start_indent = len(start_indent_str.split(start_folder + '/')[0])

    documented = set()
    stack = []  # list of tuples: (indent_level, folder_name)
    
    for line in lines[start_idx+1:]:
        # Stop if we hit another header
        if line.startswith("#"):
            break
            
        if not line.strip() or '/' not in line:
            continue
            
        indent_str = line.replace('│', ' ')
        m = re.search(r'([a-zA-Z0-9_\-]+)/', line)
        if not m:
            continue
        folder = m.group(1)
        
        # Calculate indent up to the folder name
        indent = len(indent_str.split(folder + '/')[0])
        
        # Stop if we exit the java package sub-hierarchy
        if indent <= start_indent:
            break
            
        while stack and stack[-1][0] >= indent:
            stack.pop()
            
        stack.append((indent, folder))
        
        # Reconstruct path
        full_path = "/".join([item[1] for item in stack])
        documented.add(full_path)
        
    return documented

def main():
    print("🔍 Checking for structural changes in the codebase...")
    
    # Normalize working directory to repo root
    script_dir = os.path.dirname(os.path.realpath(__file__))
    os.chdir(os.path.join(script_dir, "../.."))
    
    try:
        changes = get_git_changes()
    except Exception as e:
        print(f"❌ Error getting git changes: {e}")
        sys.exit(1)
        
    config_changes = []
    package_errors = []
    
    # 1. Check direct configuration and migration files
    config_pattern = re.compile(
        r'(pom\.xml|docker/db/migration/|src/main/resources/application.*\.yml|'
        r'src/main/resources/.*\.properties|docker-compose.*\.yml|Dockerfile)',
        re.IGNORECASE
    )
    for file in changes:
        if config_pattern.search(file):
            config_changes.append(file)
            
    # 2. Package validations (undocumented & obsolete)
    struct_file = ".antigravity/steering/structure.md"
    if os.path.exists(struct_file):
        with open(struct_file, "r") as f:
            struct_content = f.read()
        documented = get_documented_packages(struct_content)
        
        # Check for new Java packages not documented in structure.md (ignore deleted files)
        java_files = [
            f for f in changes 
            if f.startswith("src/main/java/de/palsoftware/yvoke/") and f.endswith(".java") and os.path.exists(f)
        ]
        undocumented = set()
        for file in java_files:
            rel_path = file.replace("src/main/java/de/palsoftware/yvoke/", "")
            parts = rel_path.split("/")[:-1]
            if not parts:
                continue
            pkg_path = "/".join(parts)
            if pkg_path not in documented:
                undocumented.add(pkg_path)
                
        for pkg in sorted(undocumented):
            package_errors.append(f"Undocumented package: de.palsoftware.yvoke.{pkg.replace('/', '.')}")
            
        # Check for documented packages that no longer exist in the workspace
        base_dir = "src/main/java/de/palsoftware/yvoke"
        for pkg_path in sorted(documented):
            full_pkg_dir = os.path.join(base_dir, pkg_path)
            if not os.path.isdir(full_pkg_dir):
                package_errors.append(f"Obsolete/Deleted package in structure.md: de.palsoftware.yvoke.{pkg_path.replace('/', '.')}")
    else:
        print(f"⚠️  Warning: {struct_file} not found. Cannot verify packages.")

    # Package errors are hard failures that must always block verification
    if package_errors:
        print("❌ ERROR: The following package documentation mismatches were found:")
        for err in package_errors:
            print(f" - {err}")
        print("\nPlease update '.antigravity/steering/structure.md' to align with the codebase.")
        sys.exit(2)

    # Configuration changes require at least one steering file to be updated
    if config_changes:
        steering_updated = any(f.startswith(".antigravity/steering/") for f in changes)
        if steering_updated:
            print("✅ Structural changes detected, and steering documents under .antigravity/steering/ have been updated.")
            print("Verification successful.")
            sys.exit(0)
        else:
            print("⚠️  WARNING: The following structural files or configuration settings have changed:")
            for change in config_changes:
                print(f" - {change}")
            print("")
            print("Please ensure you update the corresponding steering documents under .antigravity/steering/:")
            print(" - For database migrations (schema/tables/indices/keys) -> update 'structure.md' and/or 'tech.md'")
            print(" - For Maven pom.xml (dependencies/plugins/versions) -> update 'tech.md'")
            print("")
            print("This check prevents context drift and ensures future agent runs start with perfect codebase alignment.")
            sys.exit(2)
    else:
        print("✅ No structural files changed. Steering documents are aligned.")
        sys.exit(0)

if __name__ == "__main__":
    main()
