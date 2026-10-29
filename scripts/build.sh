#!/usr/bin/env bash
set -Ee -o pipefail

SRC="${1:-$(pwd)}"

cd "$SRC"

export OUT_DIR=out
export CCACHE_DIR="${CCACHE_DIR:-/mnt/axion-cache/ccache}"
export USE_CCACHE=1

source build/envsetup.sh
lunch lineage_tiro-bp3a-userdebug
m bacon -j12
