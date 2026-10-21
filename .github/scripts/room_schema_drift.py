"""Room schema drift: compares the schema files Room exported during the build with the committed ones.

Usage: python3 -I room_schema_drift.py <schema dir relative to the current directory>

Fails (exit 1, error annotation) when a committed schema file now has another identity hash (an entity
changed without a new database version) or when the build produced a schema file that is not committed
(new version without its schema in the repository). Formatting differences are ignored.
"""
import json
import os
import subprocess
import sys


def identity(text):
    try:
        return json.loads(text)["database"]["identityHash"]
    except (ValueError, KeyError, TypeError):
        return None


def main():
    root = sys.argv[1]
    problems = []
    for dirpath, _, files in os.walk(root):
        for name in sorted(files):
            if not name.endswith(".json"):
                continue
            path = os.path.join(dirpath, name)
            with open(path, encoding="utf-8") as f:
                built = identity(f.read())
            shown = subprocess.run(["git", "show", "HEAD:./" + path], capture_output=True, text=True)
            if shown.returncode != 0:
                problems.append(f"{path}: produced by the build but not committed")
                continue
            committed = identity(shown.stdout)
            if committed != built:
                problems.append(f"{path}: identity hash {committed} committed, {built} built")
    if problems:
        print("::error title=Room schema drift::" + " | ".join(problems)
              + " – raise the database version, add a migration and commit the exported schema")
        return 1
    print("Room schemas unchanged")
    return 0


if __name__ == "__main__":
    sys.exit(main())
