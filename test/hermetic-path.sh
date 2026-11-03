#!/usr/bin/env bash
# Sourced by shell tests. hermetic_path DIR builds DIR with wrapper scripts for an allowlist of standard tools only,
# resolved from the inherited PATH, and prints DIR. Tests then use PATH="<fakes>:DIR" so installed user tools
# (wireless-debugging, adb, aapt2, apksigner, keytool, ...) are never reachable and "command not found" is real.
HERMETIC_TOOLS="bash sh env git python3 timeout sleep date mktemp rm mkdir cp mv ln chmod cat grep egrep sed awk tr cut sort uniq head tail wc tee ls dirname basename readlink realpath stat id uname hostname sha256sum cmp diff touch printf echo kill ps find xargs true false test expr seq od curl mkfifo nc"
hermetic_path() {
    local dir=$1 t real
    mkdir -p "$dir"
    for t in $HERMETIC_TOOLS; do
        real=$(type -P "$t" 2>/dev/null) || continue
        printf '#!/bin/sh\nexec "%s" "$@"\n' "$real" > "$dir/$t"
        chmod +x "$dir/$t"
    done
}
# hermetic_sentinels DIR LOG creates fake "installed" real tools that record any invocation in LOG.
hermetic_sentinels() {
    local dir=$1 log=$2 t
    mkdir -p "$dir"
    for t in wireless-debugging adb aapt2 apksigner keytool; do
        printf '#!/bin/sh\necho "SENTINEL %s $*" >> "%s"\nexit 99\n' "$t" "$log" > "$dir/$t"
        chmod +x "$dir/$t"
    done
}
