# Executed by Kernel Adiutor's root shell in a subshell.
ka_zram_error() {
    printf 'KA ZRAM: %s\n' "$1"
    exit 1
}

ka_zram_swaps=$(cat "$ka_zram_proc_swaps") || ka_zram_error "Cannot inspect active swap"
if printf '%s\n' "$ka_zram_swaps" | grep -Eq '^([^[:space:]]*/)?zram0[[:space:]]'; then
    if [ "$ka_zram_boot" = 1 ]; then
        printf 'KA ZRAM: already active; keeping running configuration\n'
        exit 0
    fi
    swapoff "$ka_zram_block" || ka_zram_error "Cannot drain zram0"
fi

ka_zram_streams=$(cat "$ka_zram_dir/max_comp_streams" 2>/dev/null)
printf '1\n' > "$ka_zram_dir/reset" || ka_zram_error "Reset failed"
[ "$ka_zram_bytes" -gt 0 ] || exit 0

if grep -qw lz4 "$ka_zram_dir/comp_algorithm" 2>/dev/null; then
    printf 'lz4\n' > "$ka_zram_dir/comp_algorithm" || ka_zram_error "Cannot select LZ4"
fi
if [ -n "$ka_zram_streams" ]; then
    printf '%s\n' "$ka_zram_streams" > "$ka_zram_dir/max_comp_streams" 2>/dev/null || :
fi

ka_zram_dict_size=0
# The primary user's backing path is available even if /sdcard is late.
if [ ! -f "$ka_zram_dictionary" ] && [ -f "$ka_zram_dictionary_fallback" ]; then
    ka_zram_dictionary=$ka_zram_dictionary_fallback
fi
if [ -w "$ka_zram_dir/comp_dictionary_size" ] &&
   [ -w "$ka_zram_dir/comp_dictionary" ] && [ -f "$ka_zram_dictionary" ]; then
    ka_zram_length=$(stat -c %s "$ka_zram_dictionary" 2>/dev/null)
    case "$ka_zram_length" in
        ''|*[!0-9]*) ka_zram_length=0 ;;
    esac
    if [ "$ka_zram_length" -ge 8 ] && [ "$ka_zram_length" -le 65536 ] &&
       printf '%s\n' "$ka_zram_length" > "$ka_zram_dir/comp_dictionary_size" &&
       cat "$ka_zram_dictionary" > "$ka_zram_dir/comp_dictionary"; then
        ka_zram_dict_size=$ka_zram_length
    else
        printf 'KA ZRAM: dictionary upload failed; using plain compression\n'
        printf '0\n' > "$ka_zram_dir/comp_dictionary_size" ||
            ka_zram_error "Cannot clear incomplete dictionary"
    fi
fi

if ! printf '%s\n' "$ka_zram_bytes" > "$ka_zram_dir/disksize"; then
    [ "$ka_zram_dict_size" -gt 0 ] || ka_zram_error "Cannot initialize zram0"
    printf 'KA ZRAM: dictionary initialization failed; retrying plain LZ4\n'
    printf '0\n' > "$ka_zram_dir/comp_dictionary_size" ||
        ka_zram_error "Cannot clear dictionary"
    printf '%s\n' "$ka_zram_bytes" > "$ka_zram_dir/disksize" ||
        ka_zram_error "Cannot initialize plain ZRAM"
    ka_zram_dict_size=0
fi

mkswap "$ka_zram_block" >/dev/null || ka_zram_error "mkswap failed"
swapon "$ka_zram_block" || ka_zram_error "swapon failed"
printf 'KA ZRAM: active, disk=%s bytes, dictionary=%s bytes\n' \
    "$ka_zram_bytes" "$ka_zram_dict_size"
