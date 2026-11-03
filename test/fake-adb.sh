#!/usr/bin/env bash
# Fake adb for tests (no device). State files are chosen through environment variables:
#   FAKE_LOG calls log, FAKE_DEVICES lines "serial state", FAKE_MDNS `adb mdns services` output,
#   FAKE_INSTALL (success|fail), FAKE_INSTALLED (1 if the package is installed), FAKE_VERSION ("name code").
echo "$*" >> "$FAKE_LOG"
case "$1" in
    devices) echo "List of devices attached"; while read -r s st; do [[ -n $s ]] && echo "$s	$st	product:x model:Fake"; done < "$FAKE_DEVICES"; exit 0 ;;
    mdns)
        if [[ ${FAKE_MDNS_UNSUPPORTED:-} == 1 ]]; then echo "adb: unknown command mdns services" >&2; exit 1; fi
        if [[ ${FAKE_MDNS_FAIL:-} == 1 ]]; then echo "adb mdns backend unavailable" >&2; exit 1; fi
        cat "$FAKE_MDNS" 2>/dev/null; exit 0 ;;
    connect)
        echo "connected to $2"
        echo "$2 ${FAKE_CONNECT_STATE:-device}" >> "$FAKE_DEVICES"
        [[ -z ${FAKE_CONNECT_EXTRA:-} ]] || echo "$FAKE_CONNECT_EXTRA" >> "$FAKE_DEVICES"
        exit 0 ;;
    kill-server|disconnect|pair|start-server) echo "FORBIDDEN: $*" >> "$FAKE_LOG.bad"; exit 1 ;;
esac
[[ $1 == -s ]] || { echo "adb called without -s: $*" >> "$FAKE_LOG.bad"; exit 1; }
S=$2; shift 2
ST=$(awk -v s="$S" '$1==s {print $2}' "$FAKE_DEVICES")
case "$1" in
    get-state)
        case $ST in
            device) echo device ;;
            offline) echo "error: device offline" >&2; exit 1 ;;
            unauthorized) echo "error: device unauthorized." >&2; exit 1 ;;
            *) echo "error: device '$S' not found" >&2; exit 1 ;;
        esac ;;
    install) [[ ${FAKE_INSTALL:-success} == success ]] && echo Success || { echo "Failure [INSTALL_FAILED_VERSION_DOWNGRADE]"; exit 1; } ;;
    shell)
        case "$2 $3" in
            "getprop ro.product.model") echo FakePhone ;;
            "getprop ro.product.manufacturer") echo FakeCo ;;
            "getprop ro.serialno") echo "SER-$S" ;;
            "pm path") [[ ${FAKE_INSTALLED:-0} == 1 ]] && echo "package:/data/app/x/base.apk" ;;
            "dumpsys package") read -r n c <<<"${FAKE_VERSION:-}"; echo "    versionCode=$c minSdk=30 targetSdk=33"; echo "    versionName=$n" ;;
        esac ;;
esac
exit 0
