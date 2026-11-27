#!/bin/sh
set -eu

repo=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
zstd_revision=f8745da6ff1ad1e7bab384bd1f9d742439278e99
lz4_revision=ebb370ca83af193212df4dcbadcc5d87bc0de2f0
work=${1:-$(mktemp -d)}
if [ "$#" -eq 0 ]; then
    trap 'rm -rf -- "$work"' EXIT
fi
if [ ! -d "$work/zstd/.git" ]; then
    git clone --depth=1 --branch v1.5.7 https://github.com/facebook/zstd.git "$work/zstd"
fi
test "$(git -C "$work/zstd" rev-parse HEAD)" = "$zstd_revision"

if [ ! -d "$work/lz4/.git" ]; then
    git clone --depth=1 --branch v1.10.0 https://github.com/lz4/lz4.git "$work/lz4"
fi
test "$(git -C "$work/lz4" rev-parse HEAD)" = "$lz4_revision"

cc=${CC:-aarch64-linux-gnu-gcc}
ar=${AR:-aarch64-linux-gnu-ar}
make -C "$work/zstd/lib" -j"${JOBS:-4}" libzstd.a CC="$cc" AR="$ar" \
    ZSTD_LIB_DEPRECATED=0 ZSTD_LIB_DICTBUILDER=1
"$cc" -O2 -Wall -Wextra -Werror -static -s -I"$work/zstd/lib" -I"$work/lz4/lib" \
    "$repo/tools/zram_trainer.c" "$work/lz4/lib/lz4.c" "$work/zstd/lib/libzstd.a" \
    -pthread -o "$work/zram_trainer"
install -Dm755 "$work/zram_trainer" "$repo/app/src/main/assets/arm64/zram_trainer"
install -Dm644 "$work/zstd/LICENSE" "$repo/app/src/main/assets/arm64/ZSTD-LICENSE"
install -Dm644 "$work/lz4/lib/LICENSE" "$repo/app/src/main/assets/arm64/LZ4-LICENSE"
