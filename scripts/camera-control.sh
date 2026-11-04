#!/usr/bin/env bash
# Copyright (C) 2026 David González López-Tercero
# SPDX-License-Identifier: GPL-3.0-or-later
# See LICENSE in the repository root for licensing details.

# Non-touch control of Observatory IP Camera over authorized adb (for SSH-only use in Termux).
# Always requires an explicit adb target; never auto-selects a device and has no default port.
set -uo pipefail

PKG=local.termux.observatorycamera
APP_PORT="${APP_PORT:-}"
ADB="${ADB:-adb}"
TIMEOUT=20
WAKE=1
FORCE=0
OUT=
IN=
LINES=60
CONFIG=
PASSWORD_FILE=
SAN=
CERT_TMP=
GENERATE_TMP=
TARGET="${TARGET:-}"

EX_USAGE=2 EX_FAIL=3 EX_STOPPED=4 EX_TIMEOUT=5 EX_INCONSISTENT=6 EX_BUSY=7 EX_DEVICE=10 EX_PERM=11

usage() {
    cat <<USAGE
Usage: camera-control.sh (-s|--target TARGET | TARGET env) [options] SUBCOMMAND

Subcommands:
  start              start the configured HTTP/HTTPS listeners (needs POST_NOTIFICATIONS)
  stop               stop the network listeners (service only; the app is not force-stopped)
  status             report app-side state and probe the real listener
  capture [--out F]  capture one JPEG now; optionally fetch it after fresh-capture evidence
  fetch --out FILE   copy the stored snapshot.jpg (the latest stored file, NOT proof of a fresh capture)
  logs               show recent diagnostics (control/camera/HTTP logs, permissions, service state)
  continuous-start   start sustained JPEG capture (camera foreground service, needs CAMERA + POST_NOTIFICATIONS)
  continuous-stop    stop sustained capture and release the camera
  continuous-status  state, session frames, requested target and actual FPS, last capture time/age, processing diagnostics
  config-show        show the persistent capture/overlay settings
  config-set K=V ... validate and save capture, overlay, telemetry-panel and MQTT settings atomically
                     target_fps=positive FPS (one decimal place); fields=location,datetime,fps,temperature,humidity,pressure,battery,battery_temperature,charging,thermal|none
                     MQTT credentials are stored separately in app-private storage; applied on next frame/server refresh
  config-reset       restore default settings
  config-export --out FILE   save the live camera.properties (normalized) to FILE on this host
  config-import --in FILE    upload FILE, validate it completely on the device, then replace the live settings
                     (invalid file: nothing changes, exit 2 with the reason). Applied from the next frame.
  config-validate    validate directly edited camera.properties without changing it
  config-reload      validate and reload camera, MQTT and network settings
  network-apply      apply saved listener settings without restarting capture
  certificate-generate --san IP:127.0.0.1,DNS:camera.example
                     generate an explicit one-year self-signed identity and install it app-privately
  certificate-import --in FILE --password-file FILE
                     validate and atomically install a PKCS#12 identity; never pass password contents as arguments
  certificate-status show certificate SHA-256 fingerprint and expiry (never private key material)
  certificate-export --out FILE export only the public PEM certificate
  admin-token-show   provision if needed and print the persistent HTTP settings token (explicit local control only)
  admin-token-rotate invalidate the previous HTTP settings token and print a new one
  setup-permissions  grant CAMERA and POST_NOTIFICATIONS via 'pm grant' (only when you run it)

Options: --timeout SECONDS (default 20), --no-wake, --force (fetch overwrite), --out FILE, --in FILE,
         --password-file FILE, --san IP:ADDRESS,DNS:NAME, --lines N
TARGET is the exact adb serial or host:port of a device you have verified (see 'adb devices').
Exit codes: 0 ok, 2 usage, 3 failure, 4 stopped, 5 timeout, 6 inconsistent, 7 camera busy, 10 device/app, 11 permission.
USAGE
}

die() { local code=$1; shift; echo "ERROR: $*" >&2; exit "$code"; }
adbt() { "$ADB" -s "$TARGET" "$@"; }

SUB=
while [[ $# -gt 0 ]]; do
    case "$1" in
        -s|--target) [[ $# -ge 2 ]] || die $EX_USAGE "$1 needs a value"; TARGET=$2; shift 2 ;;
        --timeout) [[ $# -ge 2 ]] || die $EX_USAGE "--timeout needs a value"; TIMEOUT=$2; shift 2 ;;
        --in) [[ $# -ge 2 ]] || die $EX_USAGE "--in needs a value"; IN=$2; shift 2 ;;
        --out) [[ $# -ge 2 ]] || die $EX_USAGE "--out needs a value"; OUT=$2; shift 2 ;;
        --password-file) [[ $# -ge 2 ]] || die $EX_USAGE "--password-file needs a value"; PASSWORD_FILE=$2; shift 2 ;;
        --san) [[ $# -ge 2 ]] || die $EX_USAGE "--san needs a value"; SAN=$2; shift 2 ;;
        --lines) [[ $# -ge 2 ]] || die $EX_USAGE "--lines needs a value"; LINES=$2; shift 2 ;;
        --no-wake) WAKE=0; shift ;;
        --force) FORCE=1; shift ;;
        -h|--help|help) usage; exit 0 ;;
        start|stop|status|capture|fetch|logs|setup-permissions|continuous-start|continuous-stop|continuous-status|config-show|config-set|config-reset|config-export|config-import|config-validate|config-reload|network-apply|certificate-generate|certificate-import|certificate-status|certificate-export|admin-token-show|admin-token-rotate)
            [[ -z $SUB ]] || die $EX_USAGE "Only one subcommand allowed."; SUB=$1; shift ;;
        *=*)
            [[ $SUB == config-set ]] || die $EX_USAGE "Unexpected assignment '$1' (only valid after config-set)."
            [[ $1 =~ ^([a-z_]+=[A-Za-z0-9_.,/#+-]+|mqtt_host=|fields=)$ ]] || die $EX_USAGE "Invalid assignment '$1': keys are lowercase and values contain only supported setting characters"
            CONFIG="${CONFIG:+$CONFIG }$1"; shift ;;
        *) usage >&2; die $EX_USAGE "Unknown argument: $1" ;;
    esac
done
[[ -n $SUB ]] || { usage >&2; die $EX_USAGE "Missing subcommand."; }
[[ -n $TARGET ]] || die $EX_USAGE "No target. Pass -s TARGET or set TARGET to the verified adb device; this script never auto-selects a device."
[[ $TARGET =~ ^[A-Za-z0-9][A-Za-z0-9._:-]*$ ]] || die $EX_USAGE "Invalid target '$TARGET'."
[[ $TIMEOUT =~ ^[0-9]+$ && $TIMEOUT -ge 1 && $TIMEOUT -le 300 ]] || die $EX_USAGE "--timeout must be 1-300."
[[ $LINES =~ ^[0-9]+$ ]] || die $EX_USAGE "--lines must be a number."
[[ -z $APP_PORT || ( $APP_PORT =~ ^[0-9]+$ && $APP_PORT -ge 1 && $APP_PORT -le 65535 ) ]] || die $EX_USAGE "APP_PORT must be 1-65535."
case $SUB in fetch) [[ -n $OUT ]] || die $EX_USAGE "fetch needs --out FILE." ;; esac
[[ $SUB != config-export || -n $OUT ]] || die $EX_USAGE "config-export needs --out FILE."
if [[ $SUB == config-import || $SUB == certificate-import ]]; then
    [[ -n $IN ]] || die $EX_USAGE "config-import needs --in FILE."
    [[ -f $IN && -r $IN ]] || die $EX_USAGE "Cannot read $IN."
    if [[ $SUB == config-import ]]; then
        (( $(wc -c < "$IN") <= 8192 )) || die $EX_USAGE "$IN is larger than 8192 bytes."
    else
        [[ -n $PASSWORD_FILE && -f $PASSWORD_FILE && -r $PASSWORD_FILE ]] \
            || die $EX_USAGE "certificate-import needs a readable --password-file."
        (( $(wc -c < "$IN") <= 2097152 )) || die $EX_USAGE "$IN is larger than 2097152 bytes."
        (( $(wc -c < "$PASSWORD_FILE") <= 256 )) || die $EX_USAGE "Certificate password file is larger than 256 bytes."
    fi
fi
[[ $SUB != certificate-export || -n $OUT ]] || die $EX_USAGE "certificate-export needs --out FILE."
if [[ $SUB == certificate-generate ]]; then
    [[ -n $SAN ]] || die $EX_USAGE "certificate-generate needs --san IP:ADDRESS,DNS:NAME."
    [[ $SAN =~ ^(IP:[0-9.]+|DNS:[A-Za-z0-9.-]+)(,(IP:[0-9.]+|DNS:[A-Za-z0-9.-]+))*$ ]] \
        || die $EX_USAGE "Invalid SAN list; use comma-separated IP:IPv4 and DNS:name entries."
fi
if [[ $SUB == config-set ]]; then
    [[ -n $CONFIG ]] || die $EX_USAGE "config-set needs at least one KEY=VALUE."
    (( ${#CONFIG} <= 1024 )) || die $EX_USAGE "config-set assignments are too long."
fi

state=$(adbt get-state 2>&1) || die $EX_DEVICE "Target '$TARGET' is not usable: $state"
[[ $state == device ]] || die $EX_DEVICE "Target '$TARGET' state is '$state', expected 'device' (authorize it first)."
adbt shell pm path "$PKG" 2>/dev/null | grep -q '^package:' || die $EX_DEVICE "$PKG is not installed on $TARGET. Install: adb -s TARGET install -r dist/observatory-ip-camera.apk"

new_id() { echo "c$(date +%s)-$$-$RANDOM"; }

perm_state() { # granted | denied | unknown
    local line
    line=$(adbt shell dumpsys package "$PKG" 2>/dev/null | grep -m1 "$1: granted=")
    case $line in
        *granted=true*) echo granted ;;
        *granted=false*) echo denied ;;
        *) echo unknown ;;
    esac
}

wake() { [[ $WAKE -eq 1 ]] && adbt shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1; return 0; }

# Fresh CONTROL log lines for one request id.
control_lines() { adbt logcat -d -v time -s ObservatoryControl:I '*:S' 2>/dev/null | grep -F "id=$1 " ; }

am_start() { # args passed to am start; fail on shell errors / permission denial
    local out
    out=$(adbt shell am start -W "$@" 2>&1)
    if grep -qiE 'Error|Exception|Permission Denial' <<<"$out"; then
        echo "$out" >&2
        die $EX_DEVICE "am start failed (see above). ControlActivity requires the adb shell or root; other callers are rejected."
    fi
}

# send_control CMD ID [ASSIGNMENTS]; assignments were whitelisted above and are single-quoted for the device shell.
send_control() {
    wake
    if [[ -n ${3:-} ]]; then am_start -n "$PKG/.ControlActivity" --es command "$1" --es request_id "$2" --es config "'$3'"
    else am_start -n "$PKG/.ControlActivity" --es command "$1" --es request_id "$2"; fi
}

FWD_PORT=
cleanup() {
    [[ -n $FWD_PORT ]] && adbt forward --remove "tcp:$FWD_PORT" >/dev/null 2>&1
    FWD_PORT=
    [[ -n $CERT_TMP ]] && rm -rf "$CERT_TMP"
    CERT_TMP=
    [[ -n $GENERATE_TMP ]] && rm -rf "$GENERATE_TMP"
    GENERATE_TMP=
}
trap cleanup EXIT

# Read only non-secret network properties; missing keys preserve the release defaults.
read_network_config() {
    local properties
    properties=$(adbt exec-out run-as "$PKG" cat files/camera.properties 2>/dev/null) || properties=
    property() {
        local key=$1 fallback=$2 value
        value=$(awk -F= -v key="$key" '$1==key {value=substr($0,index($0,"=")+1)} END {print value}' <<<"$properties")
        printf '%s' "${value:-$fallback}"
    }
    HTTP_ENABLED=$(property http_enabled on)
    HTTP_PORT=${APP_PORT:-$(property http_port 8080)}
    HTTPS_ENABLED=$(property https_enabled off)
    HTTPS_PORT=$(property https_port 8443)
    LISTEN_ADDRESSES=$(property listen_addresses 0.0.0.0)
}

probe_certificate() {
    [[ -n $CERT_TMP && -s $CERT_TMP ]] && return 0
    CERT_TMP=$(mktemp) || return 1
    if ! adbt exec-out run-as "$PKG" cat "files/https-server.pem" > "$CERT_TMP" 2>/dev/null || [[ ! -s $CERT_TMP ]]; then
        rm -f "$CERT_TMP"
        CERT_TMP=
        return 1
    fi
    chmod 600 "$CERT_TMP"
}

probe_endpoint() { # scheme address port
    local scheme=$1 address=$2 port=$3 probe_address local_port head connect_host tls_host san_dns
    probe_address=$address
    PROBE_ENABLED=$((PROBE_ENABLED + 1))
    if [[ $address == 0.0.0.0 || $address == 127.0.0.1 ]]; then
        probe_address=127.0.0.1
        if [[ -n $FWD_PORT ]]; then adbt forward --remove "tcp:$FWD_PORT" >/dev/null 2>&1 || true; FWD_PORT=; fi
        FWD_PORT=$(adbt forward tcp:0 "tcp:$port" 2>/dev/null | tr -dc '0-9')
        [[ -n $FWD_PORT ]] || { PROBE_DETAIL="${PROBE_DETAIL:+$PROBE_DETAIL, }$scheme://$address:$port=forward-error"; return; }
        connect_host=127.0.0.1
        local_port=$FWD_PORT
    else
        connect_host=$address
        local_port=$port
    fi
    if [[ $scheme == https ]]; then
        if ! probe_certificate; then
            PROBE_DETAIL="${PROBE_DETAIL:+$PROBE_DETAIL, }https://$address:$port=certificate-unavailable"
            return
        fi
        command -v openssl >/dev/null || die $EX_DEVICE "OpenSSL is required to verify HTTPS certificate hostnames (pkg install openssl-tool)."
        san_dns=$(openssl x509 -in "$CERT_TMP" -noout -ext subjectAltName 2>/dev/null \
            | grep -oE 'DNS:[A-Za-z0-9.-]+' | head -n 1 | cut -d: -f2)
        tls_host=${san_dns:-$probe_address}
        head=$(curl --cacert "$CERT_TMP" --connect-to "$tls_host:$port:$connect_host:$local_port" \
            -s -m 3 -I "https://$tls_host:$port/snapshot.jpg" 2>/dev/null | tr -d '\r') || true
    else
        head=$(curl --connect-to "$probe_address:$port:$connect_host:$local_port" \
            -s -m 3 -I "http://$probe_address:$port/snapshot.jpg" 2>/dev/null | tr -d '\r') || true
    fi
    if grep -qi '^Server: ObservatoryNative' <<<"$head"; then
        local code
        code=$(awk 'NR==1{print $2}' <<<"$head")
        [[ -n $HTTP_CODE ]] || HTTP_CODE=$code
        PROBE_RESPONSES=$((PROBE_RESPONSES + 1))
        PROBE_DETAIL="${PROBE_DETAIL:+$PROBE_DETAIL, }$scheme://$address:$port=$code"
    else
        PROBE_DETAIL="${PROBE_DETAIL:+$PROBE_DETAIL, }$scheme://$address:$port=unreachable-or-untrusted"
    fi
    if [[ -n $FWD_PORT ]]; then adbt forward --remove "tcp:$FWD_PORT" >/dev/null 2>&1 || true; FWD_PORT=; fi
}

# Sets HTTP_CODE and checks every configured protocol/address; HTTPS uses the app's public cert.
probe_http() {
    HTTP_CODE=
    PROBE_ENABLED=0
    PROBE_RESPONSES=0
    PROBE_DETAIL=
    command -v curl >/dev/null || die $EX_DEVICE "curl is required for listener probing (pkg install curl)."
    read_network_config
    local address
    if [[ $HTTP_ENABLED == on ]]; then
        if [[ $LISTEN_ADDRESSES == 0.0.0.0 ]]; then
            probe_endpoint http 0.0.0.0 "$HTTP_PORT"
        else
            IFS=, read -r -a addresses <<<"$LISTEN_ADDRESSES"
            for address in "${addresses[@]}"; do probe_endpoint http "$address" "$HTTP_PORT"; done
        fi
    fi
    if [[ $HTTPS_ENABLED == on ]]; then
        if [[ $LISTEN_ADDRESSES == 0.0.0.0 ]]; then
            probe_endpoint https 0.0.0.0 "$HTTPS_PORT"
        else
            IFS=, read -r -a addresses <<<"$LISTEN_ADDRESSES"
            for address in "${addresses[@]}"; do probe_endpoint https "$address" "$HTTPS_PORT"; done
        fi
    fi
    if [[ -n $FWD_PORT ]]; then adbt forward --remove "tcp:$FWD_PORT" >/dev/null 2>&1 || true; FWD_PORT=; fi
}

describe_http() {
    case $1 in
        200) echo "HTTP 200: server running and a snapshot is available" ;;
        404) echo "HTTP 404: server running, no snapshot captured yet" ;;
        503) echo "HTTP 503: server running, snapshot unreadable/oversized/incomplete" ;;
        *) echo "HTTP $1: server running" ;;
    esac
}

wait_http_state() { # up|down -> 0 reached, 1 timed out
    local deadline=$((SECONDS + TIMEOUT))
    while :; do
        probe_http
        if [[ $1 == up && $PROBE_ENABLED -gt 0 && $PROBE_RESPONSES -eq $PROBE_ENABLED ]] \
            || [[ $1 == down && $PROBE_RESPONSES -eq 0 ]]; then return 0; fi
        (( SECONDS >= deadline )) && return 1
        sleep 1
    done
}

explain_reason() {
    case $1 in
        notifications-permission-missing) echo "POST_NOTIFICATIONS is not granted. Run: camera-control.sh -s $TARGET setup-permissions" ;;
        notifications-blocked) echo "Notifications/channel are blocked for the app. The server needs a visible Stop notification; re-enable notifications for $PKG in system settings (or via 'adb shell cmd notification' on builds that support it)." ;;
        *Could\ not\ listen*) echo "Port bind failed (port occupied?): $1" ;;
        *) echo "$1" ;;
    esac
}

cmd_start() {
    [[ $(perm_state android.permission.POST_NOTIFICATIONS) == denied ]] && die $EX_PERM "POST_NOTIFICATIONS is not granted. Run: $0 -s $TARGET setup-permissions"
    local id deadline lines last result
    id=$(new_id); deadline=$((SECONDS + TIMEOUT))
    send_control start "$id"
    while :; do
        lines=$(control_lines "$id" | grep 'cmd=start' || true)
        last=$(tail -n 1 <<<"$lines")
        if grep -q 'result=failed' <<<"$lines"; then
            result=$(grep 'result=failed' <<<"$lines" | tail -n 1 | sed 's/.*reason=//')
            local msg; msg=$(explain_reason "$result")
            [[ $result == notifications-* ]] && die $EX_PERM "Start failed: $msg"
            die $EX_FAIL "Start failure: $msg"
        fi
        grep -q 'result=listening' <<<"$lines" && break
        if (( SECONDS >= deadline )); then
            if grep -q 'result=deferred' <<<"$last"; then
                die $EX_TIMEOUT "Timeout: the control activity never resumed (screen off, locked or dozing?). The service is only started from a visible activity."
            fi
            die $EX_TIMEOUT "Timeout waiting for start confirmation (last: ${last:-no log line})."
        fi
        sleep 1
    done
    # The app logged 'listening'; verify the real listener independently.
    wait_http_state up || die $EX_TIMEOUT "Timeout: app reported listening but requested endpoints did not all respond: $PROBE_DETAIL"
    echo "RUNNING: $(describe_http "$HTTP_CODE"); endpoints: $PROBE_DETAIL"
}

cmd_status() {
    local id deadline line running
    id=$(new_id); deadline=$((SECONDS + TIMEOUT))
    send_control status "$id"
    while :; do
        line=$(control_lines "$id" | grep 'result=status' | tail -n 1)
        [[ -n $line ]] && break
        (( SECONDS >= deadline )) && die $EX_TIMEOUT "Timeout waiting for status from the app."
        sleep 1
    done
    echo "App: ${line#*result=status }"
    running=$(sed -n 's/.* running=\([a-z]*\).*/\1/p' <<<"$line")
    probe_http
    if [[ $PROBE_RESPONSES -gt 0 ]]; then
        [[ $running == true ]] || die $EX_INCONSISTENT "HTTP is answering (or the listener probe found an endpoint) but the app says it is not running: $PROBE_DETAIL"
        [[ $PROBE_RESPONSES -eq $PROBE_ENABLED ]] \
            || die $EX_INCONSISTENT "Only some requested listeners answered ($PROBE_RESPONSES/$PROBE_ENABLED): $PROBE_DETAIL"
        echo "RUNNING: $(describe_http "$HTTP_CODE"); endpoints: $PROBE_DETAIL"; exit 0
    fi
    [[ $running == true ]] && die $EX_INCONSISTENT "App says running but no configured HTTP/HTTPS listener answered: $PROBE_DETAIL"
    if grep -q 'lastError=' <<<"$line"; then die $EX_FAIL "STOPPED after a start failure: ${line#*lastError=}"; fi
    echo "STOPPED"; exit $EX_STOPPED
}

cmd_stop() {
    local id deadline
    id=$(new_id); deadline=$((SECONDS + TIMEOUT))
    send_control stop "$id"
    until control_lines "$id" | grep -q 'result=stop-requested'; do
        (( SECONDS >= deadline )) && die $EX_TIMEOUT "Timeout waiting for stop acknowledgement."
        sleep 1
    done
    wait_http_state down || die $EX_TIMEOUT "Timeout: HTTP listener still answering after stop."
    echo "STOPPED: listener is down (app process left running; nothing force-stopped)."
}

snapshot_stat() { adbt shell run-as "$PKG" stat -c '%i-%Y-%s' files/snapshot.jpg 2>/dev/null | tr -d '\r' | grep -E '^[0-9]+-[0-9]+-[0-9]+$' || echo none; }

fetch_snapshot() { # fetch_snapshot FILE
    local dest=$1 tmp
    [[ ! -e $dest || $FORCE -eq 1 ]] || die $EX_USAGE "$dest exists; use --force to overwrite."
    tmp=$(mktemp "${dest}.XXXXXX") || die $EX_USAGE "Cannot create temporary file next to $dest."
    if ! adbt exec-out run-as "$PKG" cat files/snapshot.jpg > "$tmp" 2>/dev/null || [[ ! -s $tmp ]]; then
        rm -f "$tmp"; die $EX_FAIL "No snapshot.jpg could be read (capture first)."
    fi
    if [[ $(head -c2 "$tmp" | od -An -tx1 | tr -d ' \n') != ffd8 ]]; then
        rm -f "$tmp"; die $EX_FAIL "Fetched file does not start with a JPEG marker."
    fi
    mv "$tmp" "$dest"
    echo "Fetched $(wc -c < "$dest") bytes to $dest"
}

cmd_capture() {
    case $(perm_state android.permission.CAMERA) in
        denied) die $EX_PERM "CAMERA is not granted (no dialog is shown over SSH). Run: $0 -s $TARGET setup-permissions" ;;
    esac
    local id before after deadline line
    id=$(new_id); before=$(snapshot_stat); deadline=$((SECONDS + TIMEOUT))
    wake
    # No 'am start -S': that would kill the process and a running HTTP server.
    am_start -n "$PKG/.MainActivity" --ez capture true --ez no_prompt true --es request_id "$id"
    while :; do
        line=$(control_lines "$id" | grep 'cmd=capture' | tail -n 1)
        [[ -n $line ]] && break
        if (( SECONDS >= deadline )); then
            die $EX_TIMEOUT "Timeout: no capture result. The activity needs window focus (screen on, unlocked, not dozing); retry after waking the device."
        fi
        sleep 1
    done
    grep -q 'reason=camera-busy' <<<"$line" && die $EX_BUSY "Capture rejected: the camera is owned by another mode (${line#*reason=}). Stop continuous capture first."
    grep -q 'result=failed' <<<"$line" && die $EX_FAIL "Capture failed: ${line#*reason=}"
    after=$(snapshot_stat)
    [[ $after != none ]] || die $EX_FAIL "Capture logged success but snapshot.jpg is missing."
    echo "FRESH capture confirmed by log line for request $id: ${line#*result=}"
    if [[ $after == "$before" ]]; then
        echo "WARNING: file identity (inode-mtime-size) did not change from before ($before); verify with 'logs'." >&2
    else
        echo "snapshot.jpg changed: $before -> $after"
    fi
    [[ -z $OUT ]] || fetch_snapshot "$OUT"
}

field() { sed -n "s/.* $1=\\([^ ]*\\).*/\\1/p" <<<"$2"; } # field NAME LINE

# Waits for a fresh CONTROL line of command $2 (request id $1) matching regex $3; prints it.
wait_control() {
    local deadline=$((SECONDS + TIMEOUT)) line
    while :; do
        line=$(control_lines "$1" | grep "cmd=$2" | grep -E "$3" | tail -n 1)
        [[ -n $line ]] && { echo "$line"; return 0; }
        (( SECONDS >= deadline )) && return 1
        sleep 1
    done
}

# Prints the continuous-status line (fresh request id every call).
continuous_line() {
    local id; id=$(new_id)
    send_control continuous-status "$id"
    wait_control "$id" continuous-status 'result=continuous-status' || die $EX_TIMEOUT "Timeout waiting for continuous status from the app."
}

cmd_continuous_start() {
    [[ $(perm_state android.permission.CAMERA) == denied ]] && die $EX_PERM "CAMERA is not granted (no dialog over SSH). Run: $0 -s $TARGET setup-permissions"
    [[ $(perm_state android.permission.POST_NOTIFICATIONS) == denied ]] && die $EX_PERM "POST_NOTIFICATIONS is not granted. Run: $0 -s $TARGET setup-permissions"
    local id line reason
    id=$(new_id)
    send_control continuous-start "$id"
    if ! line=$(wait_control "$id" continuous-start 'result=(started|failed)'); then
        if control_lines "$id" | grep -q 'result=deferred'; then
            die $EX_TIMEOUT "Timeout: the control activity never resumed (screen off, locked or dozing?). The camera service is only started from a visible activity."
        fi
        die $EX_TIMEOUT "Timeout waiting for the camera to start; check 'continuous-status' and 'logs'."
    fi
    if grep -q 'result=failed' <<<"$line"; then
        reason=${line#*reason=}
        case $reason in
            camera-busy*) die $EX_BUSY "Rejected: the camera is already in use ($reason). Only one camera owner is allowed." ;;
            camera-permission-missing*|notifications-*) die $EX_PERM "Start failed: $reason" ;;
            *) die $EX_FAIL "Continuous start failed: $reason" ;;
        esac
    fi
    echo "STARTED: ${line#*result=started }"
    echo "Check requested target, actual rate and fresh frames with: $0 -s $TARGET continuous-status"
}

cmd_continuous_status() {
    local line state age
    line=$(continuous_line)
    echo "Continuous: ${line#*result=continuous-status }"
    state=$(field state "$line"); age=$(field age_ms "$line")
    case $state in
        running)
            if [[ $age == none || ( $age =~ ^[0-9]+$ && $age -gt 10000 ) ]]; then echo "WARNING: running but no recent frame (age_ms=$age); the last stored snapshot may be stale." >&2; fi
            echo "RUNNING (actual_fps is measured; target_fps is requested)"; exit 0 ;;
        starting) echo "STARTING"; exit 0 ;;
        error) die $EX_FAIL "ERROR: ${line#*lastError=}" ;;
        *) echo "STOPPED"; exit $EX_STOPPED ;;
    esac
}

cmd_continuous_stop() {
    local id deadline line
    id=$(new_id); deadline=$((SECONDS + TIMEOUT))
    send_control continuous-stop "$id"
    wait_control "$id" continuous-stop 'result=stop-requested' >/dev/null || die $EX_TIMEOUT "Timeout waiting for stop acknowledgement."
    while :; do
        line=$(continuous_line)
        [[ $(field state "$line") == stopped || $(field state "$line") == error ]] && break
        (( SECONDS >= deadline )) && die $EX_TIMEOUT "Timeout: continuous capture still active after stop."
        sleep 1
    done
    echo "STOPPED: camera released; the app process and any HTTP server were left running."
}

cmd_config() { # config-show | config-set | config-reset
    local id line
    id=$(new_id)
    send_control "$SUB" "$id" "$CONFIG"
    line=$(wait_control "$id" "$SUB" 'result=(config|failed)') || die $EX_TIMEOUT "Timeout waiting for the settings result."
    grep -q 'result=failed' <<<"$line" && die $EX_USAGE "Rejected, nothing changed: ${line#*reason=}"
    grep -q 'network=not-applied' <<<"$line" \
        && die $EX_FAIL "Settings were saved, but listeners were unchanged: ${line#*networkError=}. Fix the requested endpoints, then run network-apply."
    echo "Config: ${line#*result=config }"
}

cmd_config_validate() {
    local id line
    id=$(new_id)
    send_control config-validate "$id"
    line=$(wait_control "$id" config-validate 'result=(valid|failed)') || die $EX_TIMEOUT "Timeout validating camera.properties."
    grep -q 'result=failed' <<<"$line" && die $EX_USAGE "Validation failed; no settings changed: ${line#*reason=}"
    echo "Valid: ${line#*result=valid }"
}

cmd_config_reload() {
    local id line
    id=$(new_id)
    send_control "$SUB" "$id"
    line=$(wait_control "$id" "$SUB" 'result=(reloaded|failed)') || die $EX_TIMEOUT "Timeout reloading camera.properties."
    grep -q 'result=failed' <<<"$line" && die $EX_FAIL "Reload was not applied: ${line#*reason=}"
    echo "Reloaded: ${line#*result=reloaded }"
}

cmd_config_export() {
    local id line tmp
    [[ ! -e $OUT || $FORCE -eq 1 ]] || die $EX_USAGE "$OUT exists; use --force to overwrite."
    id=$(new_id)
    send_control config-export "$id"
    line=$(wait_control "$id" config-export 'result=(config|failed)') || die $EX_TIMEOUT "Timeout waiting for the export."
    grep -q 'result=failed' <<<"$line" && die $EX_FAIL "Export failed: ${line#*reason=}"
    tmp=$(mktemp "${OUT}.XXXXXX") || die $EX_USAGE "Cannot create temporary file next to $OUT."
    if ! adbt exec-out run-as "$PKG" cat files/camera.properties > "$tmp" 2>/dev/null || [[ ! -s $tmp ]]; then
        rm -f "$tmp"; die $EX_FAIL "camera.properties could not be read."
    fi
    mv "$tmp" "$OUT"
    echo "Exported $(wc -c < "$OUT") bytes to $OUT"
}

cmd_config_import() {
    local id line
    # Stage the file in app-private storage (debuggable APK, run-as); the app validates it entirely before use.
    adbt shell "run-as $PKG sh -c 'cat > files/camera.import'" < "$IN" || die $EX_DEVICE "Could not upload $IN to the app-private import slot."
    id=$(new_id)
    send_control config-import "$id"
    line=$(wait_control "$id" config-import 'result=(config|failed)') || die $EX_TIMEOUT "Timeout waiting for the import result."
    grep -q 'result=failed' <<<"$line" && die $EX_USAGE "Import rejected, previous configuration kept: ${line#*reason=}"
    echo "Imported: ${line#*result=config }"
}

cmd_certificate_import() {
    local id line
    adbt shell "run-as $PKG sh -c 'umask 077; cat > files/https-server.import.p12'" < "$IN" \
        || die $EX_DEVICE "Could not stage PKCS#12 in app-private storage."
    if ! adbt shell "run-as $PKG sh -c 'umask 077; cat > files/https-server.import.password'" < "$PASSWORD_FILE"; then
        adbt shell "run-as $PKG rm -f files/https-server.import.p12 files/https-server.import.password" >/dev/null 2>&1 || true
        die $EX_DEVICE "Could not stage certificate password file."
    fi
    id=$(new_id)
    send_control certificate-import "$id"
    line=$(wait_control "$id" certificate-import 'result=(imported|failed)') \
        || die $EX_TIMEOUT "Timeout validating the staged server identity."
    grep -q 'result=failed' <<<"$line" && die $EX_USAGE "Certificate import rejected; prior identity retained: ${line#*reason=}"
    echo "Imported: ${line#*result=imported }"
    echo "Private key and password remain app-private; HTTPS must be enabled and restarted explicitly."
}

validate_san_list() {
    local entry value octet label
    local -a entries parts
    IFS=, read -r -a entries <<<"$SAN"
    for entry in "${entries[@]}"; do
        case $entry in
            IP:*)
                value=${entry#IP:}
                IFS=. read -r -a parts <<<"$value"
                [[ ${#parts[@]} -eq 4 ]] || return 1
                for octet in "${parts[@]}"; do
                    [[ $octet =~ ^(0|[1-9][0-9]{0,2})$ && $octet -le 255 ]] || return 1
                done ;;
            DNS:*)
                value=${entry#DNS:}
                [[ ${#value} -le 253 && $value =~ ^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$ \
                    && $value != *..* ]] || return 1
                IFS=. read -r -a parts <<<"$value"
                for label in "${parts[@]}"; do
                    [[ ${#label} -le 63 && $label =~ ^[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?$ ]] || return 1
                done ;;
            *) return 1 ;;
        esac
    done
}

cmd_certificate_generate() {
    validate_san_list || die $EX_USAGE "Invalid IP/DNS SAN entry."
    command -v openssl >/dev/null || die $EX_DEVICE "OpenSSL is required in the authorized Termux shell (pkg install openssl-tool)."
    GENERATE_TMP=$(mktemp -d) || die $EX_FAIL "Could not create a private temporary directory."
    chmod 700 "$GENERATE_TMP"
    umask 077
    od -An -N32 -tx1 /dev/urandom | tr -d ' \n' > "$GENERATE_TMP/password"
    openssl req -x509 -newkey rsa:3072 -nodes -sha256 -days 365 \
        -keyout "$GENERATE_TMP/server-key.pem" -out "$GENERATE_TMP/server-cert.pem" \
        -subj '/CN=Observatory IP Camera' -addext "subjectAltName=$SAN" >/dev/null 2>&1 \
        || die $EX_FAIL "Self-signed certificate generation failed."
    openssl pkcs12 -export -out "$GENERATE_TMP/server.p12" -inkey "$GENERATE_TMP/server-key.pem" \
        -in "$GENERATE_TMP/server-cert.pem" -name server -passout "file:$GENERATE_TMP/password" \
        >/dev/null 2>&1 || die $EX_FAIL "PKCS#12 packaging failed."
    chmod 600 "$GENERATE_TMP/server.p12" "$GENERATE_TMP/password"
    IN="$GENERATE_TMP/server.p12"
    PASSWORD_FILE="$GENERATE_TMP/password"
    cmd_certificate_import
}

cmd_certificate_status() {
    local id line
    id=$(new_id)
    send_control certificate-status "$id"
    line=$(wait_control "$id" certificate-status 'result=(ready|missing|failed)') \
        || die $EX_TIMEOUT "Timeout reading certificate status."
    grep -q 'result=ready' <<<"$line" || die $EX_FAIL "No valid server certificate is installed."
    echo "Certificate: ${line#*result=ready }"
}

cmd_certificate_export() {
    local id line tmp
    [[ ! -e $OUT || $FORCE -eq 1 ]] || die $EX_USAGE "$OUT exists; use --force to overwrite."
    id=$(new_id)
    send_control certificate-export "$id"
    line=$(wait_control "$id" certificate-export 'result=(exported|failed)') \
        || die $EX_TIMEOUT "Timeout exporting the public certificate."
    grep -q 'result=failed' <<<"$line" && die $EX_FAIL "Public certificate export failed."
    tmp=$(mktemp "${OUT}.XXXXXX") || die $EX_USAGE "Cannot create temporary file next to $OUT."
    if ! adbt exec-out run-as "$PKG" cat files/https-server.pem > "$tmp" 2>/dev/null \
        || [[ ! -s $tmp ]] || ! grep -q '^-----BEGIN CERTIFICATE-----$' "$tmp" \
        || grep -q 'PRIVATE KEY' "$tmp"; then
        rm -f "$tmp"; die $EX_FAIL "Only a public PEM certificate may be exported."
    fi
    chmod 600 "$tmp"
    mv "$tmp" "$OUT"
    echo "Public certificate exported to $OUT; no private key was copied."
}

cmd_admin_token() {
    local id line token command
    if [[ $SUB == admin-token-rotate ]]; then command=admin-token-rotate; else command=admin-token-show; fi
    id=$(new_id)
    send_control "$command" "$id"
    line=$(wait_control "$id" "$command" 'result=(ready|rotated|failed)') || die $EX_TIMEOUT "Timeout waiting for the token control command."
    grep -q 'result=failed' <<<"$line" && die $EX_FAIL "Token operation failed (the token value was not logged)."
    token=$(adbt exec-out run-as "$PKG" cat files/http-admin.token 2>/dev/null | tr -d '\r\n') \
        || die $EX_FAIL "Could not retrieve the app-private token through run-as."
    [[ $token =~ ^[A-Za-z0-9_-]{43}$ ]] || die $EX_FAIL "The app-private token file is missing or invalid."
    printf 'HTTP admin token (plaintext HTTP risk; never enter it over unencrypted HTTP; store securely and enter only in the viewer): %s\n' "$token"
}

cmd_logs() {
    echo "== permissions =="
    echo "CAMERA: $(perm_state android.permission.CAMERA)"
    echo "POST_NOTIFICATIONS: $(perm_state android.permission.POST_NOTIFICATIONS)"
    echo "== service =="
    adbt shell dumpsys activity services "$PKG" 2>/dev/null | grep -E 'ServiceRecord|isForeground|No services' | head -n 5
    echo "== snapshot file (inode-mtime-size) =="
    snapshot_stat
    echo "== recent logs =="
    adbt logcat -d -v time -s ObservatoryControl:I ObservatoryCamera:I ObservatoryContinuous:I SnapshotHttp:I '*:S' 2>/dev/null | tail -n "$LINES"
}

cmd_setup_permissions() {
    local sdk rc=0
    sdk=$(adbt shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')
    adbt shell pm grant "$PKG" android.permission.CAMERA || rc=$EX_PERM
    if [[ $sdk =~ ^[0-9]+$ && $sdk -ge 33 ]]; then
        adbt shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || rc=$EX_PERM
    else
        echo "SDK '$sdk' < 33: POST_NOTIFICATIONS is not a runtime permission; skipped."
    fi
    echo "CAMERA: $(perm_state android.permission.CAMERA)"
    echo "POST_NOTIFICATIONS: $(perm_state android.permission.POST_NOTIFICATIONS)"
    exit $rc
}

case $SUB in
    start) cmd_start ;;
    stop) cmd_stop ;;
    status) cmd_status ;;
    capture) cmd_capture ;;
    fetch) fetch_snapshot "$OUT" ;;
    logs) cmd_logs ;;
    setup-permissions) cmd_setup_permissions ;;
    continuous-start) cmd_continuous_start ;;
    continuous-stop) cmd_continuous_stop ;;
    continuous-status) cmd_continuous_status ;;
    config-show|config-set|config-reset) cmd_config ;;
    config-validate) cmd_config_validate ;;
    config-reload|network-apply) cmd_config_reload ;;
    config-export) cmd_config_export ;;
    config-import) cmd_config_import ;;
    certificate-generate) cmd_certificate_generate ;;
    certificate-import) cmd_certificate_import ;;
    certificate-status) cmd_certificate_status ;;
    certificate-export) cmd_certificate_export ;;
    admin-token-show|admin-token-rotate) cmd_admin_token ;;
esac
