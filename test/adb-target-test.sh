#!/usr/bin/env bash
# Tests scripts/adb-target.sh against a fake adb. Run: bash test/adb-target-test.sh
set -uo pipefail
cd "$(dirname "$0")/.."
ROOT=$PWD
T=$(mktemp -d "${TMPDIR:-/tmp}/adbt test.XXXXXX")
trap 'rm -rf "$T"' EXIT
export FAKE_LOG="$T/calls" FAKE_DEVICES="$T/devices" FAKE_MDNS="$T/mdns"
printf '#!/usr/bin/env bash\nexec bash "%s/test/fake-adb.sh" "$@"\n' "$ROOT" > "$T/adb"; chmod +x "$T/adb"
export ADB="$T/adb"
unset TARGET
H="$ROOT/scripts/adb-target.sh"
fails=0
setdev() { : > "$FAKE_LOG"; rm -f "$FAKE_LOG.bad"; printf '%s\n' "$@" > "$FAKE_DEVICES"; : > "$FAKE_MDNS"; }
check() { # name expected_rc expected_stdout stderr_pattern -- cmd...
    local name=$1 rc_want=$2 out_want=$3 pat=$4; shift 4
    local out rc
    out=$("$@" 2>"$T/err"); rc=$?
    if [[ $rc -ne $rc_want || $out != "$out_want" ]] || ! grep -qE -- "$pat" "$T/err" || [[ -e $FAKE_LOG.bad ]]; then
        echo "FAIL: $name (rc=$rc out='$out')"; cat "$T/err"; cat "$FAKE_LOG.bad" 2>/dev/null; fails=$((fails+1))
    else echo "ok: $name"; fi
}
run() { bash "$H" "$@"; }

setdev "A1 device"
check "single device + identity" 0 A1 'model=FakePhone' run
setdev "A1 device" "B2 device"
check "multiple devices refused" 11 "" 'ambiguous' run
setdev
check "no device" 10 "" 'no device found' run
setdev "A1 offline"
check "offline refused" 12 "" 'no authorized device.*offline' run
setdev "A1 unauthorized"
check "unauthorized refused" 12 "" 'unauthorized' run
setdev "A1 unauthorized" "B2 device"
check "unique authorized among unauthorized" 0 B2 'not usable, ignored' run
setdev "A1 device" "B2 device"
check "explicit -s wins" 0 B2 'target=B2' run -s B2
check "explicit missing no fallback" 10 "" 'not found' run -s ZZ
setdev "A1 device" "B2 unauthorized"
check "explicit unauthorized no fallback" 12 "" 'unauthorized' run --target B2
setdev "A1 device"
check "usage error" 2 "" 'unknown option' run --bogus
export TARGET=B2
setdev "A1 device" "B2 device"
check "existing TARGET used (not first)" 0 B2 'target=B2' run
check "explicit overrides TARGET" 0 A1 'target=A1' run -s A1
setdev "A1 device"
check "stale TARGET not replaced" 10 "" 'existing TARGET is not usable.*--refresh' run
check "refresh re-resolves with notice" 0 A1 "TARGET changes from 'B2' to 'A1'" run --refresh
setdev "A1 device" "C3 device"
check "refresh with several is ambiguous" 11 "" 'ambiguous' run --refresh
unset TARGET
# mdns
setdev
printf 'List of discovered mdns services\nadb-XYZ\t_adb-tls-connect._tcp.\t192.168.1.20:41234\n' > "$FAKE_MDNS"
check "no mdns without flag" 10 "" 'no device found' run
check "mdns reconnect" 0 192.168.1.20:41234 'reconnecting' run --mdns
grep -qx 'connect 192.168.1.20:41234' "$FAKE_LOG" || { echo "FAIL: mdns connect call"; fails=$((fails+1)); }
setdev
printf 'a\t_adb-tls-connect._tcp.\t192.168.1.20:1\nb\t_adb-tls-connect._tcp.\t192.168.1.21:2\n' > "$FAKE_MDNS"
check "several mdns refused" 11 "" 'several wireless' run --mdns
setdev
printf 'a\t_adb-tls-connect._tcp.\t8.8.8.8:1\nc\t_adb-tls-pairing._tcp.\t192.168.1.5:2\n' > "$FAKE_MDNS"
check "non-local/pairing mdns ignored" 10 "" 'non-local' run --mdns
# source behavior
setdev "A1 device"
src_out=$(bash -c '
    set -u; trap "echo TRAP" EXIT
    cd /; before=$PWD; opts=$-; tr=$(trap -p EXIT)
    TARGET=stale
    source "$1"
    [[ $PWD == "$before" && $- == "$opts" && $(trap -p EXIT) == "$tr" ]] || echo CHANGED
    adbt_export 2>/dev/null; echo "rc=$? T=${TARGET-unset}"
    adbt_export; echo "rc=$? T=${TARGET-unset}"
    adbt_export -s NOPE 2>/dev/null; echo "rc=$? T=${TARGET-unset}"
    echo done' _ "$H" 2>/dev/null)
[[ $src_out == $'rc=10 T=unset\nrc=0 T=A1\nrc=10 T=unset\ndone\nTRAP' ]] && echo "ok: source behavior" || { echo "FAIL: source behavior: $src_out"; fails=$((fails+1)); }
t=$(bash "$H" 2>/dev/null) && [[ $t == A1 ]] && echo "ok: export via command substitution" || { echo "FAIL: cmd subst"; fails=$((fails+1)); }
setdev
if t=$(bash "$H" 2>/dev/null); then echo "FAIL: failure-safe"; fails=$((fails+1)); else echo "ok: failure-safe assignment"; fi
exit $(( fails > 0 ))
