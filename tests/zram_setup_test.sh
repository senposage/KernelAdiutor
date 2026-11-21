#!/bin/sh
set -eu

script="$(dirname "$0")/../app/src/main/assets/t227u_zram_setup.sh"
sh -n "$script"
work=$(mktemp -d)
trap 'rm -f "$work"/*; rmdir "$work"' EXIT
ka_zram_dir=$work
ka_zram_bytes=2097152000
ka_zram_boot=1
ka_zram_block=/dev/block/zram0
ka_zram_proc_swaps="$work/swaps"
ka_zram_dictionary="$work/input.dict"
ka_zram_dictionary_fallback="$work/fallback.dict"

swapoff() {
    printf 'swapoff\n' >> "$work/actions"
    return 1
}
mkswap() {
    test "$(cat "$work/disksize")" = "$ka_zram_bytes"
    printf 'mkswap\n' >> "$work/actions"
}
swapon() {
    printf 'swapon\n' >> "$work/actions"
}

fixture() {
    printf 'Filename Type Size Used Priority\n' > "$work/swaps"
    printf '0\n' > "$work/disksize"
    printf '0\n' > "$work/comp_dictionary_size"
    printf '0\n' > "$work/reset"
    printf '[lzo] lz4 zstd\n' > "$work/comp_algorithm"
    printf '3\n' > "$work/max_comp_streams"
    : > "$work/comp_dictionary"
    : > "$work/actions"
    rm -f "$ka_zram_dictionary" "$ka_zram_dictionary_fallback"
}

fixture
dd if=/dev/zero of="$ka_zram_dictionary_fallback" bs=4096 count=4 2>/dev/null
( . "$script" )
test "$(cat "$work/comp_dictionary_size")" = 16384
cmp "$work/comp_dictionary" "$ka_zram_dictionary_fallback"
test "$(cat "$work/actions")" = "$(printf 'mkswap\nswapon')"

fixture
printf 'default dictionary' > "$ka_zram_dictionary"
selected="$work/Asphalt sessions.dict"
printf 'selected dictionary' > "$selected"
( ka_zram_dictionary=$selected; . "$script" )
cmp "$work/comp_dictionary" "$selected"

fixture
printf 'default dictionary' > "$ka_zram_dictionary"
( ka_zram_dictionary=; ka_zram_dictionary_fallback=; . "$script" )
test "$(cat "$work/comp_dictionary_size")" = 0
test ! -s "$work/comp_dictionary"
rm "$selected"

fixture
( . "$script" )
test "$(cat "$work/comp_dictionary_size")" = 0
test "$(cat "$work/disksize")" = "$ka_zram_bytes"

fixture
dd if=/dev/zero of="$ka_zram_dictionary" bs=4096 count=17 2>/dev/null
( . "$script" )
test "$(cat "$work/comp_dictionary_size")" = 0
test "$(cat "$work/disksize")" = "$ka_zram_bytes"

fixture
dd if=/dev/zero of="$ka_zram_dictionary" bs=4096 count=4 2>/dev/null
rm "$work/comp_dictionary"
ln -s /dev/full "$work/comp_dictionary"
( . "$script" )
test "$(cat "$work/comp_dictionary_size")" = 0
test "$(cat "$work/disksize")" = "$ka_zram_bytes"
rm "$work/comp_dictionary"

fixture
printf '/dev/block/zram0 partition 2047996 512 -2\n' >> "$work/swaps"
( . "$script" )
test ! -s "$work/actions"
test "$(cat "$work/reset")" = 0

ka_zram_boot=0
if ( . "$script" ); then
    printf 'Expected swapoff failure\n' >&2
    exit 1
fi
test "$(cat "$work/actions")" = swapoff
test "$(cat "$work/reset")" = 0
printf 'PASS: named and plain selection, staging, fallback, boot protection and failed swapoff\n'
