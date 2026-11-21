#!/bin/sh
set -eu

repo=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
revision=f8745da6ff1ad1e7bab384bd1f9d742439278e99
work=${1:-$(mktemp -d)}
if [ "$#" -eq 0 ]; then
    trap 'rm -rf -- "$work"' EXIT
fi
if [ ! -d "$work/zstd/.git" ]; then
    git clone --depth=1 --branch v1.5.7 https://github.com/facebook/zstd.git "$work/zstd"
fi
test "$(git -C "$work/zstd" rev-parse HEAD)" = "$revision"

cc=${CC:-aarch64-linux-gnu-gcc}
ar=${AR:-aarch64-linux-gnu-ar}
make -C "$work/zstd/lib" -j"${JOBS:-4}" libzstd.a CC="$cc" AR="$ar" \
    ZSTD_LIB_DEPRECATED=0 ZSTD_LIB_DICTBUILDER=1
"$cc" -O2 -Wall -Wextra -Werror -static -s -I"$work/zstd/lib" \
    "$repo/tools/zram_trainer.c" "$work/zstd/lib/libzstd.a" -pthread -o "$work/zram_trainer"
install -Dm755 "$work/zram_trainer" "$repo/app/src/main/assets/arm64/zram_trainer"
install -Dm644 "$work/zstd/LICENSE" "$repo/app/src/main/assets/arm64/ZSTD-LICENSE"
