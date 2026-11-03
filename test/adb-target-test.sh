#!/usr/bin/env bash
# Tests scripts/adb-target.sh against a fake adb. Run: bash test/adb-target-test.sh
set -uo pipefail
cd "$(dirname "$0")/.."
ROOT=$PWD
T=$(mktemp -d "${TMPDIR:-/tmp}/adbt test.XXXXXX")
trap 'rm -rf "$T"' EXIT
export FAKE_LOG="$T/calls" FAKE_DEVICES="$T/devices" FAKE_MDNS="$T/mdns" FAKE_WIRELESS_LOG="$T/wireless-calls"
printf '#!/usr/bin/env bash\nexec bash "%s/test/fake-adb.sh" "$@"\n' "$ROOT" > "$T/adb"; chmod +x "$T/adb"
cat > "$T/wireless-debugging" <<'WIRELESS'
#!/usr/bin/env bash
echo "$*" >> "$FAKE_WIRELESS_LOG"
case ${FAKE_WIRELESS_MODE:-success} in
    fail) echo "fake wrapper failed"; exit 7 ;;
    timeout) sleep 30 ;;
    no-device) echo "Operation: on" ;;
    multiple) printf 'A1 device\nB2 device\n' > "$FAKE_DEVICES" ;;
    success) printf '%s\n' "$FAKE_WIRELESS_DEVICE" > "$FAKE_DEVICES" ;;
esac
echo "fake wrapper human-readable output"
WIRELESS
chmod +x "$T/wireless-debugging"
mkdir -p "$T/zeroconf"
cat > "$T/zeroconf/zeroconf.py" <<'PY'
import os

def mark(value):
    with open(os.environ["FAKE_ZC_MARKER"], "a") as output:
        output.write(value + "\n")

class IPVersion:
    V4Only = "v4"

class Info:
    def __init__(self, port, addresses):
        self.port = port
        self.addresses = addresses
    def parsed_addresses(self, _version):
        return self.addresses

class Zeroconf:
    def __init__(self, **_kwargs):
        mark("created")
    def get_service_info(self, _type, name, timeout):
        mode = os.environ.get("FAKE_ZC_MODE")
        if mode == "timeout":
            return None
        if mode in ("backend-fail", "resolve-fail"):
            raise RuntimeError("mock resolution failure")
        entries = {
            "valid": {"one": (41234, ["192.168.1.20"])},
            "duplicate": {"one": (41234, ["192.168.1.20"]), "two": (41234, ["192.168.1.20"])},
            "multiple": {"one": (41234, ["192.168.1.20"]), "two": (41235, ["192.168.1.21"])},
            "bad-port": {"one": (65536, ["192.168.1.20"])},
            "bad-address": {"one": (41234, ["999.1.1.1", "8.8.8.8"])},
        }
        port, addresses = entries[mode][name]
        return Info(port, addresses)
    def close(self):
        mark("closed")

class ServiceBrowser:
    def __init__(self, _zeroconf, _type, listener):
        mode = os.environ.get("FAKE_ZC_MODE")
        if mode == "browser-fail":
            raise RuntimeError("mock browser failure")
        services = {
            "valid": ["one"],
            "duplicate": ["one", "two"],
            "multiple": ["one", "two"],
            "bad-port": ["one"],
            "bad-address": ["one"],
            "timeout": ["stuck"],
            "resolve-fail": ["stuck"],
        }
        for name in services.get(mode, []):
            listener.add_service(None, None, name)
        mark("browser")
    def cancel(self):
        mark("cancelled")
PY
cat > "$T/python-stdlib" <<PY
#!/usr/bin/env bash
exec env -u PYTHONPATH "$(command -v python3)" -S "\$@"
PY
chmod +x "$T/python-stdlib"
. "$ROOT/test/hermetic-path.sh"
SENTINEL_LOG="$T/sentinel-calls"; : > "$SENTINEL_LOG"
hermetic_sentinels "$T/installed" "$SENTINEL_LOG"
PATH="$T/installed:$PATH"   # simulates a user's installed real tools in the inherited PATH
hermetic_path "$T/stdtools"
export PATH="$T:$T/stdtools"
export ADB="$T/adb"
unset TARGET
H="$ROOT/scripts/adb-target.sh"
fails=0
setdev() {
    : > "$FAKE_LOG"; : > "$FAKE_WIRELESS_LOG"; rm -f "$FAKE_LOG.bad"
    printf '%s\n' "$@" > "$FAKE_DEVICES"; : > "$FAKE_MDNS"
    unset FAKE_MDNS_UNSUPPORTED FAKE_MDNS_FAIL FAKE_WIRELESS_MODE FAKE_WIRELESS_DEVICE ADBT_WIRELESS_ON_TIMEOUT
    unset ADB_DISCOVERY_PYTHON ADB_MDNS_TIMEOUT PYTHONPATH FAKE_ZC_MODE FAKE_ZC_MARKER FAKE_CONNECT_STATE FAKE_CONNECT_EXTRA
}
mock_zeroconf() {
    export FAKE_ZC_MODE=$1 ADB_DISCOVERY_PYTHON=python3 PYTHONPATH="$T/zeroconf"
    export FAKE_ZC_MARKER="$T/zeroconf-resources" ADB_MDNS_TIMEOUT="${2:-1}"
    : > "$FAKE_ZC_MARKER"
}
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
check "mdns reconnect" 0 192.168.1.20:41234 'connecting to discovered' run --mdns
grep -qx 'connect 192.168.1.20:41234' "$FAKE_LOG" || { echo "FAIL: mdns connect call"; fails=$((fails+1)); }
grep -q 'target=192.168.1.20:41234.*model=FakePhone' "$T/err" && echo "ok: mdns target identity verified" || { echo "FAIL: mdns identity missing"; fails=$((fails+1)); }
setdev
export TARGET=STALE
check "stale TARGET blocks mDNS without refresh" 10 "" 'existing TARGET is not usable.*--refresh' run --mdns
! grep -q '^mdns ' "$FAKE_LOG" && echo "ok: stale TARGET did not trigger mDNS" || { echo "FAIL: stale TARGET triggered mDNS"; fails=$((fails+1)); }
setdev
export TARGET=STALE
printf 'List\nservice\t_adb-tls-connect._tcp.\t192.168.1.20:41234\n' > "$FAKE_MDNS"
check "refresh re-resolves stale TARGET through mDNS" 0 192.168.1.20:41234 'TARGET changes from .STALE. to .192.168.1.20:41234.' run --refresh --mdns
unset TARGET
setdev
printf 'a\t_adb-tls-connect._tcp.\t192.168.1.20:1\nb\t_adb-tls-connect._tcp.\t192.168.1.21:2\n' > "$FAKE_MDNS"
check "several mdns refused" 11 "" 'several local wireless' run --mdns
setdev
printf 'a\t_adb-tls-connect._tcp.\t8.8.8.8:1\nc\t_adb-tls-pairing._tcp.\t192.168.1.5:2\n' > "$FAKE_MDNS"
check "non-local/pairing mdns ignored" 14 "" 'non-local' run --mdns
setdev
printf 'a\t_adb-tls-connect._tcp.\t192.168.1.20:65536\nb\t_adb-tls-connect._tcp.\t999.1.1.1:1234\n' > "$FAKE_MDNS"
check "native adb malformed ports and addresses rejected" 14 "" 'none had a valid local endpoint' run --mdns
setdev
FAKE_MDNS_UNSUPPORTED=1 ADB_DISCOVERY_PYTHON="$T/no-python" check "missing Python for unsupported adb mdns" 14 "" 'Python interpreter not found' run --mdns
[[ ! -s $FAKE_WIRELESS_LOG ]] && echo "ok: unsupported mdns does not activate wireless debugging" || { echo "FAIL: mdns implicitly activated wireless debugging"; fails=$((fails+1)); }
setdev
FAKE_MDNS_FAIL=1 check "failing adb mdns backend is reported" 14 "" 'backend failed.*unavailable' run --mdns
setdev
check "native adb mdns with zero services does not use fallback" 10 "" 'discovered no ADB services' run --mdns
setdev
check "explicit target never invokes mdns discovery" 10 "" 'not found' run -s MISSING --mdns
! grep -q '^mdns ' "$FAKE_LOG" && echo "ok: explicit target preserved without discovery" || { echo "FAIL: explicit target invoked mDNS"; fails=$((fails+1)); }
setdev
export FAKE_MDNS_UNSUPPORTED=1; mock_zeroconf valid
check "zeroconf fallback resolves valid local endpoint" 0 192.168.1.20:41234 'discovered local ADB mDNS endpoint' run --mdns
grep -qx 'connect 192.168.1.20:41234' "$FAKE_LOG" && echo "ok: fallback connects only resolved endpoint" || { echo "FAIL: fallback connect target"; fails=$((fails+1)); }
[[ $(wc -l < "$FAKE_ZC_MARKER") -eq 4 ]] && grep -qx cancelled "$FAKE_ZC_MARKER" && grep -qx closed "$FAKE_ZC_MARKER" && echo "ok: zeroconf resources closed" || { echo "FAIL: zeroconf resources not closed"; cat "$FAKE_ZC_MARKER"; fails=$((fails+1)); }
setdev
export FAKE_MDNS_UNSUPPORTED=1; mock_zeroconf zero
check "zeroconf zero services distinct from backend failure" 10 "" 'no _adb-tls-connect._tcp.local. services' run --mdns
grep -qx closed "$FAKE_ZC_MARKER" && echo "ok: zero-service resources closed" || { echo "FAIL: zero-service cleanup"; fails=$((fails+1)); }
setdev
export FAKE_MDNS_UNSUPPORTED=1; mock_zeroconf multiple
check "multiple zeroconf endpoints are ambiguous" 11 "" 'several local wireless devices' run --mdns
! grep -q '^connect ' "$FAKE_LOG" && echo "ok: ambiguous zeroconf endpoints were not connected" || { echo "FAIL: ambiguous connect"; fails=$((fails+1)); }
setdev
export FAKE_MDNS_UNSUPPORTED=1; mock_zeroconf duplicate
check "duplicate zeroconf endpoints deduplicated" 0 192.168.1.20:41234 'discovered local ADB mDNS endpoint' run --mdns
[[ $(grep -c '^connect 192.168.1.20:41234$' "$FAKE_LOG") == 1 ]] && echo "ok: duplicate endpoint connected once" || { echo "FAIL: duplicate connection"; fails=$((fails+1)); }
setdev
export FAKE_MDNS_UNSUPPORTED=1; mock_zeroconf bad-port
check "zeroconf malformed port rejected" 14 "" 'discovered services had no valid local IPv4 endpoints' run --mdns
setdev
export FAKE_MDNS_UNSUPPORTED=1; mock_zeroconf bad-address
check "zeroconf malformed and non-local addresses rejected" 14 "" 'discovered services had no valid local IPv4 endpoints' run --mdns
setdev
export FAKE_MDNS_UNSUPPORTED=1; mock_zeroconf timeout
start_time=$(date +%s)
check "zeroconf resolution deadline is bounded" 14 "" 'timed out resolving' run --mdns
(( $(date +%s) - start_time < 5 )) && echo "ok: discovery respected its total deadline" || { echo "FAIL: discovery exceeded its total deadline"; fails=$((fails+1)); }
grep -qx cancelled "$FAKE_ZC_MARKER" && grep -qx closed "$FAKE_ZC_MARKER" && echo "ok: timed-out resources closed" || { echo "FAIL: timeout cleanup"; fails=$((fails+1)); }
setdev
export FAKE_MDNS_UNSUPPORTED=1; mock_zeroconf browser-fail
check "zeroconf backend failure is reported" 14 "" 'backend failed.*mock browser failure' run --mdns
grep -qx closed "$FAKE_ZC_MARKER" && echo "ok: backend failure resources closed" || { echo "FAIL: backend cleanup"; fails=$((fails+1)); }
setdev
export FAKE_MDNS_UNSUPPORTED=1; mock_zeroconf resolve-fail
check "zeroconf resolver backend failure distinguished from timeout" 14 "" 'service resolution backend failed' run --mdns
grep -qx closed "$FAKE_ZC_MARKER" && echo "ok: resolver failure resources closed" || { echo "FAIL: resolver cleanup"; fails=$((fails+1)); }
setdev
FAKE_MDNS_UNSUPPORTED=1 ADB_DISCOVERY_PYTHON="$T/python-stdlib" check "missing zeroconf package is actionable" 14 "" 'zeroconf dependency is missing' run --mdns
setdev
export FAKE_MDNS_UNSUPPORTED=1; mock_zeroconf valid
FAKE_CONNECT_STATE=unauthorized check "discovered but unauthorized device rejected" 12 "" 'unauthorized' run --mdns
setdev
export FAKE_MDNS_UNSUPPORTED=1; mock_zeroconf valid
FAKE_CONNECT_STATE=offline check "discovered but offline device rejected" 12 "" 'offline' run --mdns
setdev
export FAKE_MDNS_UNSUPPORTED=1; mock_zeroconf valid
FAKE_CONNECT_EXTRA="USB device" check "unrelated attached device makes selection ambiguous" 11 "" 'ambiguous' run --mdns
grep -qx 'connect 192.168.1.20:41234' "$FAKE_LOG" && echo "ok: unrelated device was not connected" || { echo "FAIL: wrong mdns connect target"; fails=$((fails+1)); }
setdev "A1 device"
check "valid explicit target skips wireless activation" 0 A1 'target=A1' run -s A1 --wireless-on
[[ ! -s $FAKE_WIRELESS_LOG ]] && echo "ok: valid explicit target preserved without recovery" || { echo "FAIL: recovery ran for valid explicit target"; fails=$((fails+1)); }
# Explicit wireless-debugging recovery uses only the external wrapper, then re-reads adb devices.
setdev
check "wireless recovery is opt-in" 10 "" 'no device found' run
[[ ! -s $FAKE_WIRELESS_LOG ]] && echo "ok: no implicit wireless activation" || { echo "FAIL: implicit wireless activation"; fails=$((fails+1)); }
setdev
mv "$T/wireless-debugging" "$T/wireless-debugging.disabled"
check "missing wireless wrapper" 3 "" 'wireless-debugging command not found' run --wireless-on
[[ ! -s $SENTINEL_LOG ]] && echo "ok: missing wrapper did not reach installed wrapper" || { echo "FAIL: installed wrapper reached"; fails=$((fails+1)); }
mv "$T/wireless-debugging.disabled" "$T/wireless-debugging"
setdev
FAKE_WIRELESS_MODE=fail check "wireless wrapper failure" 13 "" 'wireless-debugging on failed' run --wireless-on
setdev
FAKE_WIRELESS_MODE=timeout ADBT_WIRELESS_ON_TIMEOUT=1 check "wireless wrapper timeout" 13 "" 'timed out after 1s' run --wireless-on
setdev
FAKE_WIRELESS_MODE=no-device check "setting on without usable adb fails" 10 "" 'no device found' run --wireless-on
grep -qx 'on' "$FAKE_WIRELESS_LOG" && echo "ok: wrapper ran for explicit recovery" || { echo "FAIL: wrapper call missing"; fails=$((fails+1)); }
setdev
FAKE_WIRELESS_DEVICE="192.0.2.2:5555 device" check "wireless recovery resolves changed port" 0 192.0.2.2:5555 'target=192.0.2.2:5555' run --refresh --wireless-on
grep -qx 'on' "$FAKE_WIRELESS_LOG" && echo "ok: recovery invoked once" || { echo "FAIL: recovery invocation"; fails=$((fails+1)); }
setdev
export FAKE_MDNS_UNSUPPORTED=1; mock_zeroconf zero
FAKE_WIRELESS_DEVICE="192.0.2.4:5555 device" check "combined mdns then wireless recovery" 0 192.0.2.4:5555 'target=192.0.2.4:5555' run --mdns --wireless-on
grep -qx 'on' "$FAKE_WIRELESS_LOG" && echo "ok: combined flags explicitly enabled recovery" || { echo "FAIL: combined recovery invocation"; fails=$((fails+1)); }
setdev
FAKE_MDNS_FAIL=1 check "backend failure does not trigger explicit wireless fallback" 14 "" 'backend failed' run --mdns --wireless-on
[[ ! -s $FAKE_WIRELESS_LOG ]] && echo "ok: backend failure did not activate wireless debugging" || { echo "FAIL: backend failure activated wireless debugging"; fails=$((fails+1)); }
setdev
FAKE_WIRELESS_DEVICE="192.0.2.2:5555 device" check "explicit target is not replaced after recovery" 10 "" 'refusing to select another target' run -s OLD --wireless-on
setdev "A1 device" "B2 device"
check "wireless option refuses ambiguous current devices" 11 "" 'ambiguous' run --wireless-on
[[ ! -s $FAKE_WIRELESS_LOG ]] && echo "ok: ambiguity does not activate wireless debugging" || { echo "FAIL: activated with ambiguous devices"; fails=$((fails+1)); }
setdev
FAKE_WIRELESS_MODE=multiple check "wireless recovery refuses multiple devices" 11 "" 'ambiguous' run --wireless-on
setdev
export TARGET=OLD
check "stale TARGET is not replaced by wireless option" 10 "" 'existing TARGET is not usable.*--refresh' run --wireless-on
[[ ! -s $FAKE_WIRELESS_LOG ]] && echo "ok: stale TARGET requires refresh before recovery" || { echo "FAIL: stale TARGET triggered recovery"; fails=$((fails+1)); }
FAKE_WIRELESS_DEVICE="192.0.2.3:5555 device" check "refresh and wireless recovery replace stale target" 0 192.0.2.3:5555 'target=192.0.2.3:5555' run --refresh --wireless-on
unset TARGET
# source behavior
setdev "A1 device"
src_out=$(bash -c '
    set -u; trap "echo TRAP" EXIT
    cd /; before=$PWD; opts=$-; tr=$(trap -p EXIT)
    TARGET=stale
    source "$1"
    [[ $PWD == "$before" && $- == "$opts" && $(trap -p EXIT) == "$tr" ]] || echo CHANGED
    [[ ${ADBT_MDNS_TARGET+x} ]] && echo LEAKED
    adbt_export 2>/dev/null; echo "rc=$? T=${TARGET-unset}"
    adbt_export; echo "rc=$? T=${TARGET-unset}"
    adbt_export -s NOPE 2>/dev/null; echo "rc=$? T=${TARGET-unset}"
    adbt_export --refresh --wireless-on 2>/dev/null; echo "rc=$? T=${TARGET-unset}"
    echo done' _ "$H" 2>/dev/null)
[[ $src_out == $'rc=10 T=unset\nrc=0 T=A1\nrc=10 T=unset\nrc=0 T=A1\ndone\nTRAP' ]] && echo "ok: source behavior" || { echo "FAIL: source behavior: $src_out"; fails=$((fails+1)); }
setdev
export FAKE_WIRELESS_DEVICE="192.0.2.5:5555 device"
src_recovery=$(bash -c '
    set -u; TARGET=stale; source "$1"
    adbt_export --refresh --wireless-on 2>/dev/null; echo "rc=$? T=${TARGET-unset}"' _ "$H" 2>/dev/null)
[[ $src_recovery == 'rc=0 T=192.0.2.5:5555' ]] && echo "ok: sourced wireless recovery exports changed target" || { echo "FAIL: sourced recovery: $src_recovery"; fails=$((fails+1)); }
setdev
export FAKE_WIRELESS_MODE=no-device
src_failure=$(bash -c '
    set -u; TARGET=stale; source "$1"
    adbt_export --refresh --wireless-on 2>/dev/null; echo "rc=$? T=${TARGET-unset}"' _ "$H" 2>/dev/null)
[[ $src_failure == 'rc=10 T=unset' ]] && echo "ok: sourced wireless failure unsets TARGET" || { echo "FAIL: sourced wireless failure: $src_failure"; fails=$((fails+1)); }
setdev "A1 device"
t=$(bash "$H" 2>/dev/null) && [[ $t == A1 ]] && echo "ok: export via command substitution" || { echo "FAIL: cmd subst"; fails=$((fails+1)); }
setdev
if t=$(bash "$H" 2>/dev/null); then echo "FAIL: failure-safe"; fails=$((fails+1)); else echo "ok: failure-safe assignment"; fi
[[ ! -s $SENTINEL_LOG ]] && echo "ok: installed real tools in inherited PATH were never invoked" || { echo "FAIL: installed tool invoked"; cat "$SENTINEL_LOG"; fails=$((fails+1)); }
exit $(( fails > 0 ))
