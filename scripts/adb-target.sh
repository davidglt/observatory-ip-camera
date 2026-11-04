#!/usr/bin/env bash
# Copyright (C) 2026 David González López-Tercero
# SPDX-License-Identifier: GPL-3.0-or-later
# See LICENSE in the repository root for licensing details.

# Resolve the adb TARGET (serial or host:port) for Observatory IP Camera tooling in Termux over SSH.
#
# Precedence: -s/--target SERIAL, then the existing TARGET variable, then the single authorized device
# listed by `adb devices -l`. Every candidate is validated with `adb -s X get-state` and must report
# "device". Ambiguous, unauthorized, offline and missing targets are rejected; an explicit or existing
# TARGET that fails is never silently replaced (use --refresh to ignore a stale TARGET and re-resolve).
#
# Run it:     T=$(bash scripts/adb-target.sh) && export TARGET="$T"
#             Data (the serial only) goes to stdout, diagnostics and identity/model go to stderr.
# Source it:  source scripts/adb-target.sh; adbt_export [options]
#             adbt_export sets and exports TARGET in the calling shell. On failure it unsets TARGET so no
#             stale value remains, and returns the nonzero code. Sourcing only defines adbt_* functions: it
#             never exits and never changes shell options, traps or the working directory.
#
# Exit codes: 0 ok, 2 usage, 3 required command missing, 10 no/missing device, 11 ambiguous,
# 12 unauthorized/offline/unusable, 13 wireless-debugging activation failed or timed out.
# 14 mDNS fallback backend failed or timed out.
# This script never pairs, never scans networks, never disconnects devices and never restarts the adb server.
# Wireless-debugging activation is only attempted with --wireless-on. ADB (default: adb) selects the adb binary.

adbt_usage() {
    cat <<'USAGE'
Usage: adb-target.sh [-s|--target SERIAL] [--refresh] [--mdns] [--wireless-on] [-h|--help]
  -s, --target SERIAL  use exactly this target; it must be in state "device" (no fallback)
  --refresh            ignore an existing TARGET and re-resolve from `adb devices -l`
                       (a notice is printed if the result differs from the old TARGET)
  --mdns               opt-in: if no authorized device is connected, discover and reconnect to the
                       single local IPv4 wireless device advertised by `adb mdns services`; if adb
                       explicitly lacks mDNS support, use Python zeroconf instead
                       (discovery is untrusted; never pairs; refuses when several are advertised)
  --wireless-on        opt-in: if no usable device is found, run `wireless-debugging on` with a
                       bounded timeout, then re-read and validate adb devices
                       (with --mdns, discovery/reconnection is tried first; only no services permits
                       activation; backend failures and ambiguity are reported without activation)
                       The wrapper changes settings only on the local phone where it is installed.
Prints the resolved serial on stdout; identity, model and errors on stderr.
Source this file and call `adbt_export [options]` to set/export TARGET in the current shell.
ADBT_WIRELESS_ON_TIMEOUT sets the bounded command timeout in seconds (default: 45; range: 1-300).
ADB_DISCOVERY_PYTHON selects the Python interpreter (default: $HOME/.venvs/adb-discovery/bin/python).
ADB_MDNS_TIMEOUT sets the total zeroconf discovery deadline in seconds (default: 15; range: 1-60).
USAGE
}

adbt_say() { printf 'adb-target: %s\n' "$*" >&2; }

# adbt_validate ADB SERIAL: 0 if state is "device"; else a message and 10 (missing) or 12 (unusable).
adbt_validate() {
    local adb=$1 serial=$2 out
    out=$("$adb" -s "$serial" get-state 2>&1)
    out=${out//$'\r'/}
    case $out in
        device) return 0 ;;
        *unauthorized*) adbt_say "target '$serial' is unauthorized: accept the authorization prompt on the device, then retry"; return 12 ;;
        *offline*) adbt_say "target '$serial' is offline: reconnect it (for example adb connect), then retry"; return 12 ;;
        *'not found'*|'') adbt_say "target '$serial' was not found in adb devices"; return 10 ;;
        *) adbt_say "target '$serial' is not usable (get-state: $out)"; return 12 ;;
    esac
}

adbt_prop() { local v; v=$("$1" -s "$2" shell getprop "$3" 2>/dev/null) || v=; v=${v//$'\r'/}; printf '%s' "${v:-unknown}"; }

adbt_identity() {
    local adb=$1 serial=$2
    adbt_say "target=$serial manufacturer=$(adbt_prop "$adb" "$serial" ro.product.manufacturer) model=$(adbt_prop "$adb" "$serial" ro.product.model) device_serial=$(adbt_prop "$adb" "$serial" ro.serialno)"
}

# adbt_list ADB: lines "serial<TAB>state" from `adb devices -l`.
adbt_list() {
    local out line serial state rest
    out=$("$1" devices -l 2>/dev/null) || return 1
    while IFS= read -r line; do
        line=${line//$'\r'/}
        case $line in ''|'List of devices'*|'*'*) continue ;; esac
        read -r serial state rest <<<"$line"
        [[ -n $serial && -n $state ]] && printf '%s\t%s\n' "$serial" "$state"
    done <<<"$out"
    return 0
}

adbt_valid_local_endpoint() {
    local endpoint=$1 address port a b c d first second
    [[ $endpoint =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}:[0-9]{1,5}$ ]] || return 1
    address=${endpoint%:*}; port=${endpoint##*:}
    (( 10#$port >= 1 && 10#$port <= 65535 )) || return 1
    IFS=. read -r a b c d <<<"$address"
    (( 10#$a <= 255 && 10#$b <= 255 && 10#$c <= 255 && 10#$d <= 255 )) || return 1
    first=$((10#$a)); second=$((10#$b))
    (( first == 10 || (first == 172 && second >= 16 && second <= 31) \
        || (first == 192 && second == 168) || (first == 169 && second == 254) \
        || first == 127 ))
}

# adbt_zeroconf_discover: print validated local endpoints; Python helper uses a total deadline.
adbt_zeroconf_discover() {
    local python=${ADB_DISCOVERY_PYTHON:-${HOME}/.venvs/adb-discovery/bin/python}
    local seconds=${ADB_MDNS_TIMEOUT:-15} outer script_dir helper out rc endpoint
    command -v "$python" >/dev/null 2>&1 || {
        adbt_say "Python interpreter not found: '$python'; set ADB_DISCOVERY_PYTHON to a Python with zeroconf installed"
        return 14
    }
    command -v timeout >/dev/null 2>&1 || {
        adbt_say "timeout command not found; install a timeout utility to bound mDNS discovery"
        return 14
    }
    [[ $seconds =~ ^[0-9]{1,2}$ ]] && (( 10#$seconds >= 1 && 10#$seconds <= 60 )) || {
        adbt_say "ADB_MDNS_TIMEOUT must be an integer from 1 to 60 seconds"
        return 14
    }
    seconds=$((10#$seconds)); outer=$((seconds + 5))
    script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P) || {
        adbt_say "cannot locate the mDNS discovery helper"
        return 14
    }
    helper="$script_dir/adb-mdns-discover.py"
    [[ -r $helper ]] || { adbt_say "mDNS discovery helper is missing: $helper"; return 14; }
    if out=$(timeout -s INT -k 2s "${outer}s" "$python" "$helper" "$seconds"); then
        :
    else
        rc=$?
        case $rc in
            124|137) adbt_say "zeroconf discovery exceeded its ${seconds}s deadline"; return 14 ;;
            12) adbt_say "zeroconf dependency is missing; install it manually in \$HOME/.venvs/adb-discovery or set ADB_DISCOVERY_PYTHON to an interpreter where it is installed"; return 14 ;;
            13) adbt_say "zeroconf discovery backend failed; see the Python diagnostic above"; return 14 ;;
            11) adbt_say "zeroconf timed out resolving discovered ADB mDNS services"; return 14 ;;
            10) adbt_say "zeroconf found no usable local ADB mDNS services"; return 10 ;;
            *) adbt_say "zeroconf discovery failed (exit $rc); see the Python diagnostic above"; return 14 ;;
        esac
    fi
    local -a endpoints=()
    while IFS= read -r endpoint; do
        [[ -n $endpoint ]] || continue
        if adbt_valid_local_endpoint "$endpoint"; then endpoints+=("$endpoint")
        else adbt_say "zeroconf helper returned an invalid or non-local endpoint '$endpoint'"; return 14
        fi
    done <<<"$out"
    if (( ${#endpoints[@]} == 0 )); then
        adbt_say "zeroconf found no usable local ADB mDNS services"
        return 10
    fi
    printf '%s\n' "${endpoints[@]}"
}

# adbt_connect_discovered ADB ENDPOINT: connect only to one discovered local service and verify it.
adbt_connect_discovered() {
    local adb=$1 endpoint=$2 listing serial state found=0
    ADBT_MDNS_TARGET=
    adbt_say "connecting to discovered local ADB mDNS endpoint $endpoint (discovery is not identity verification)"
    "$adb" connect "$endpoint" >&2 || true
    listing=$(adbt_list "$adb") || { adbt_say "adb devices failed after connecting to discovered endpoint"; return 10; }
    while IFS=$'\t' read -r serial state; do
        [[ $serial == "$endpoint" ]] || continue
        found=1
        break
    done <<<"$listing"
    (( found )) || { adbt_say "discovered endpoint '$endpoint' is not listed by adb after connect"; return 10; }
    adbt_validate "$adb" "$endpoint" || return $?
    ADBT_MDNS_TARGET=$endpoint
}

# adbt_mdns_reconnect ADB: prefer adb mDNS; use zeroconf only when adb says mDNS is unsupported.
adbt_mdns_reconnect() {
    local adb=$1 out line type addr rest lower endpoint rc service_count=0
    local -a addrs=()
    ADBT_MDNS_TARGET=
    if out=$("$adb" mdns services 2>&1); then
        while IFS= read -r line; do
            line=${line//$'\r'/}
            read -r _ type addr rest <<<"$line"
            [[ $type == _adb-tls-connect._tcp* && -n $addr ]] || continue
            service_count=$((service_count + 1))
            if adbt_valid_local_endpoint "$addr"; then
                addrs+=("$addr")
            else
                adbt_say "ignoring invalid or non-local adb mdns endpoint '$addr'"
            fi
        done <<<"$out"
        if (( ${#addrs[@]} == 0 )); then
            if (( service_count )); then
                adbt_say "adb mdns services advertised ADB services but none had a valid local endpoint"
                return 14
            fi
            adbt_say "adb mdns services succeeded but discovered no ADB services"
            return 10
        fi
    else
        lower=${out,,}
        if [[ $lower == *"unknown command"* || $lower == *"not supported"* \
            || $lower == *"unsupported"* || $lower == *"unrecognized"* ]]; then
            adbt_say "adb mdns services is unsupported; falling back to external Python zeroconf discovery"
            out=$(adbt_zeroconf_discover) || return $?
            while IFS= read -r addr; do [[ -n $addr ]] && addrs+=("$addr"); done <<<"$out"
        else
            adbt_say "adb mdns services backend failed: ${out:-no diagnostic}"
            return 14
        fi
    fi
    local -a unique=()
    for addr in "${addrs[@]}"; do
        local duplicate=0
        for endpoint in "${unique[@]}"; do [[ $endpoint == "$addr" ]] && duplicate=1
        done
        (( duplicate )) || unique+=("$addr")
    done
    if (( ${#unique[@]} == 0 )); then
        adbt_say "mDNS discovery returned no usable local endpoints"
        return 10
    fi
    if (( ${#unique[@]} > 1 )); then
        adbt_say "several local wireless devices were discovered (${unique[*]}); pass -s explicitly"
        return 11
    fi
    adbt_connect_discovered "$adb" "${unique[0]}"
}

# adbt_wireless_on: explicitly enable the Android wireless-debugging setting using the installed wrapper.
adbt_wireless_on() {
    local seconds=${ADBT_WIRELESS_ON_TIMEOUT:-45} rc
    command -v wireless-debugging >/dev/null 2>&1 || {
        adbt_say "wireless-debugging command not found (install its wrapper yourself; nothing is installed automatically)"
        return 3
    }
    command -v timeout >/dev/null 2>&1 || {
        adbt_say "timeout command not found; install a timeout utility to bound wireless-debugging on"
        return 3
    }
    [[ $seconds =~ ^[0-9]{1,3}$ ]] || {
        adbt_say "ADBT_WIRELESS_ON_TIMEOUT must be an integer from 1 to 300 seconds"
        return 2
    }
    seconds=$((10#$seconds))
    (( seconds >= 1 && seconds <= 300 )) || {
        adbt_say "ADBT_WIRELESS_ON_TIMEOUT must be an integer from 1 to 300 seconds"
        return 2
    }
    adbt_say "requesting wireless debugging ON via wireless-debugging (timeout ${seconds}s)"
    timeout -k 2s "${seconds}s" wireless-debugging on >&2
    rc=$?
    if (( rc == 124 || rc == 137 )); then
        adbt_say "wireless-debugging on timed out after ${seconds}s; check the wrapper and retry"
        return 13
    fi
    if (( rc != 0 )); then
        adbt_say "wireless-debugging on failed (exit $rc); check its installation, permission and app status"
        return 13
    fi
    return 0
}

# adbt_resolve [options]: print the resolved serial on stdout.
adbt_resolve() {
    local adb=${ADB:-adb} explicit="" refresh=0 mdns=0 wireless_on=0 rc listing candidate state ADBT_MDNS_TARGET=
    while (( $# )); do
        case $1 in
            -s|--target) [[ $# -ge 2 && -n $2 ]] || { adbt_say "$1 needs a value"; return 2; }; explicit=$2; shift 2 ;;
            --refresh) refresh=1; shift ;;
            --mdns) mdns=1; shift ;;
            --wireless-on) wireless_on=1; shift ;;
            *) adbt_say "unknown option: $1"; return 2 ;;
        esac
    done
    command -v "$adb" >/dev/null 2>&1 || { adbt_say "adb not found (install android-tools; nothing is installed automatically)"; return 3; }
    if [[ -n $explicit ]]; then
        if adbt_validate "$adb" "$explicit"; then
            :
        else
            rc=$?
            (( wireless_on )) || return "$rc"
            adbt_wireless_on || return $?
            listing=$(adbt_list "$adb") || { adbt_say "adb devices failed after wireless-debugging on"; return 10; }
            local found_explicit=0
            while IFS=$'\t' read -r candidate state; do
                if [[ $candidate == "$explicit" ]]; then found_explicit=1; break; fi
            done <<<"$listing"
            (( found_explicit )) || {
                adbt_say "explicit target '$explicit' was not listed after wireless-debugging on; refusing to select another target"
                return 10
            }
        fi
        adbt_validate "$adb" "$explicit" || return $?
        adbt_identity "$adb" "$explicit"
        printf '%s\n' "$explicit"
        return 0
    fi
    local old=${TARGET:-}
    if [[ -n $old && $refresh -eq 0 ]]; then
        adbt_validate "$adb" "$old" || { rc=$?; adbt_say "existing TARGET is not usable; fix it, pass -s, or use --refresh to re-resolve"; return $rc; }
        adbt_identity "$adb" "$old"
        printf '%s\n' "$old"
        return 0
    fi
    [[ -n $old ]] && adbt_say "ignoring existing TARGET='$old' (--refresh)"
    local listing serial state
    local -a ready=() other=()
    listing=$(adbt_list "$adb") || { adbt_say "adb devices failed"; return 10; }
    while IFS=$'\t' read -r serial state; do
        [[ -n $serial ]] || continue
        if [[ $state == device ]]; then ready+=("$serial"); else other+=("$serial [$state]"); fi
    done <<<"$listing"
    if (( ${#ready[@]} == 0 && mdns )); then
        adbt_mdns_reconnect "$adb"
        rc=$?
        if (( rc != 0 )); then
            (( wireless_on && rc == 10 )) || return "$rc"
        else
            listing=$(adbt_list "$adb") || { adbt_say "adb devices failed"; return 10; }
            ready=(); other=()
            while IFS=$'\t' read -r serial state; do
                [[ -n $serial ]] || continue
                if [[ $state == device ]]; then ready+=("$serial"); else other+=("$serial [$state]"); fi
            done <<<"$listing"
        fi
    fi
    if [[ -n ${ADBT_MDNS_TARGET:-} ]]; then
        local mdns_ready=0
        for serial in "${ready[@]}"; do
            [[ $serial == "$ADBT_MDNS_TARGET" ]] && mdns_ready=1
        done
        (( mdns_ready )) || {
            adbt_say "discovered mDNS target '$ADBT_MDNS_TARGET' is no longer an authorized device; refusing to select another target"
            return 12
        }
    fi
    if (( ${#ready[@]} == 0 && wireless_on )); then
        adbt_wireless_on || return $?
        ready=(); other=()
        listing=$(adbt_list "$adb") || { adbt_say "adb devices failed"; return 10; }
        while IFS=$'\t' read -r serial state; do
            [[ -n $serial ]] || continue
            if [[ $state == device ]]; then ready+=("$serial"); else other+=("$serial [$state]"); fi
        done <<<"$listing"
    fi
    if (( ${#ready[@]} == 0 )); then
        if (( ${#other[@]} )); then
            adbt_say "no authorized device; found: ${other[*]} (authorize unauthorized devices on the phone, reconnect offline ones)"
            return 12
        fi
        adbt_say "no device found in adb devices -l (connect one, or use --mdns for an already paired wireless device)"
        return 10
    fi
    if (( ${#ready[@]} > 1 )); then
        adbt_say "ambiguous: several authorized devices (${ready[*]}); pass -s SERIAL"
        return 11
    fi
    adbt_validate "$adb" "${ready[0]}" || return $?
    (( ${#other[@]} )) && adbt_say "not usable, ignored: ${other[*]}"
    [[ -n $old && $old != "${ready[0]}" ]] && adbt_say "notice: TARGET changes from '$old' to '${ready[0]}'"
    adbt_identity "$adb" "${ready[0]}"
    printf '%s\n' "${ready[0]}"
}

# adbt_export [options]: resolve and export TARGET in the calling shell; unset TARGET on failure.
adbt_export() {
    local a resolved rc
    for a in "$@"; do
        if [[ $a == -h || $a == --help ]]; then adbt_usage >&2; return 0; fi
    done
    if resolved=$(adbt_resolve "$@"); then
        export TARGET="$resolved"
        return 0
    else
        rc=$?
        unset TARGET
        return "$rc"
    fi
}

# Executed (not sourced): print the serial.
if ! (return 0 2>/dev/null); then
    for adbt_arg in "$@"; do
        if [[ $adbt_arg == -h || $adbt_arg == --help ]]; then adbt_usage; exit 0; fi
    done
    adbt_resolve "$@"
    exit $?
fi
