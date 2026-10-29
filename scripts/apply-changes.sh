#!/usr/bin/env bash
set -Eeuo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ANDROID_SRC="${1:-$(pwd)}"

if [ ! -d "$ANDROID_SRC/.repo" ]; then
    echo "ERROR: target is not an Android repo checkout"
    exit 1
fi

cp -a \
    "$REPO_ROOT/snapshot/." \
    "$ANDROID_SRC/"

if [ -s "$REPO_ROOT/metadata/deleted-paths.txt" ]; then
    while IFS= read -r F; do
        [ -n "$F" ] || continue
        rm -rf -- "$ANDROID_SRC/$F"
    done < "$REPO_ROOT/metadata/deleted-paths.txt"
fi

echo "Custom tiro source state restored."
