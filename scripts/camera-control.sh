#!/usr/bin/env bash
# Non-touch control of Observatory IP Camera over authorized adb (for SSH-only use in Termux).
# Always requires an explicit adb target; never auto-selects a device and has no default port.
set -uo pipefail

PKG=local.termux.observatorycamera
APP_PORT="${APP_PORT:-8080}"
ADB="${ADB:-adb}"
TIMEOUT=20
WAKE=1
FORCE=0
OUT=
LINES=60
TARGET="${TARGET:-}"

EX_USAGE=2 EX_FAIL=3 EX_STOPPED=4 EX_TIMEOUT=5 EX_INCONSISTENT=6 EX_DEVICE=10 EX_PERM=11

usage() {
    cat <<USAGE
Usage: camera-control.sh (-s|--target TARGET | TARGET env) [options] SUBCOMMAND

Subcommands:
  start              start the HTTP server (needs POST_NOTIFICATIONS already granted)
  stop               stop the HTTP server (service only; the app is not force-stopped)
  status             report app-side state and probe the real listener
  capture [--out F]  capture one JPEG now; optionally fetch it after fresh-capture evidence
  fetch --out FILE   copy the stored snapshot.jpg (the latest stored file, NOT proof of a fresh capture)
  logs               show recent diagnostics (control/camera/HTTP logs, permissions, service state)
  setup-permissions  grant CAMERA and POST_NOTIFICATIONS via 'pm grant' (only when you run it)

Options: --timeout SECONDS (default 20), --no-wake, --force (fetch overwrite), --out FILE, --lines N
TARGET is the exact adb serial or host:port of a device you have verified (see 'adb devices').
Exit codes: 0 ok, 2 usage, 3 failure, 4 stopped, 5 timeout, 6 inconsistent, 10 device/app, 11 permission.
USAGE
}

die() { local code=$1; shift; echo "ERROR: $*" >&2; exit "$code"; }
adbt() { "$ADB" -s "$TARGET" "$@"; }

SUB=
while [[ $# -gt 0 ]]; do
    case "$1" in
        -s|--target) [[ $# -ge 2 ]] || die $EX_USAGE "$1 needs a value"; TARGET=$2; shift 2 ;;
        --timeout) [[ $# -ge 2 ]] || die $EX_USAGE "--timeout needs a value"; TIMEOUT=$2; shift 2 ;;
        --out) [[ $# -ge 2 ]] || die $EX_USAGE "--out needs a value"; OUT=$2; shift 2 ;;
        --lines) [[ $# -ge 2 ]] || die $EX_USAGE "--lines needs a value"; LINES=$2; shift 2 ;;
        --no-wake) WAKE=0; shift ;;
        --force) FORCE=1; shift ;;
        -h|--help|help) usage; exit 0 ;;
        start|stop|status|capture|fetch|logs|setup-permissions)
            [[ -z $SUB ]] || die $EX_USAGE "Only one subcommand allowed."; SUB=$1; shift ;;
        *) usage >&2; die $EX_USAGE "Unknown argument: $1" ;;
    esac
done
[[ -n $SUB ]] || { usage >&2; die $EX_USAGE "Missing subcommand."; }
[[ -n $TARGET ]] || die $EX_USAGE "No target. Pass -s TARGET or set TARGET to the verified adb device; this script never auto-selects a device."
[[ $TARGET =~ ^[A-Za-z0-9][A-Za-z0-9._:-]*$ ]] || die $EX_USAGE "Invalid target '$TARGET'."
[[ $TIMEOUT =~ ^[0-9]+$ && $TIMEOUT -ge 1 && $TIMEOUT -le 300 ]] || die $EX_USAGE "--timeout must be 1-300."
[[ $LINES =~ ^[0-9]+$ ]] || die $EX_USAGE "--lines must be a number."
[[ $APP_PORT =~ ^[0-9]+$ ]] || die $EX_USAGE "APP_PORT must be a number."
case $SUB in fetch) [[ -n $OUT ]] || die $EX_USAGE "fetch needs --out FILE." ;; esac

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

send_control() { wake; am_start -n "$PKG/.ControlActivity" --es command "$1" --es request_id "$2"; }

FWD_PORT=
cleanup() { [[ -n $FWD_PORT ]] && adbt forward --remove "tcp:$FWD_PORT" >/dev/null 2>&1; FWD_PORT=; }
trap cleanup EXIT

# Sets HTTP_CODE to the status of an ObservatoryNative response, or "" when nothing is listening.
probe_http() {
    HTTP_CODE=
    command -v curl >/dev/null || die $EX_DEVICE "curl is required for HTTP probing (pkg install curl)."
    if [[ -z $FWD_PORT ]]; then
        FWD_PORT=$(adbt forward tcp:0 "tcp:$APP_PORT" 2>/dev/null | tr -dc '0-9')
        [[ -n $FWD_PORT ]] || die $EX_DEVICE "adb forward failed."
    fi
    local head
    head=$(curl -s -m 3 -I "http://127.0.0.1:$FWD_PORT/snapshot.jpg" 2>/dev/null | tr -d '\r') || true
    if grep -qi '^Server: ObservatoryNative' <<<"$head"; then HTTP_CODE=$(awk 'NR==1{print $2}' <<<"$head"); fi
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
        if [[ $1 == up && -n $HTTP_CODE ]] || [[ $1 == down && -z $HTTP_CODE ]]; then return 0; fi
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
    wait_http_state up || die $EX_TIMEOUT "Timeout: app reported listening but no ObservatoryNative HTTP response on device port $APP_PORT."
    echo "RUNNING: $(describe_http "$HTTP_CODE")"
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
    if [[ -n $HTTP_CODE ]]; then
        [[ $running == true ]] || die $EX_INCONSISTENT "HTTP is answering but the app says it is not running (stale/other server on port $APP_PORT?)."
        echo "RUNNING: $(describe_http "$HTTP_CODE")"; exit 0
    fi
    [[ $running == true ]] && die $EX_INCONSISTENT "App says running but nothing answers on port $APP_PORT."
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

cmd_logs() {
    echo "== permissions =="
    echo "CAMERA: $(perm_state android.permission.CAMERA)"
    echo "POST_NOTIFICATIONS: $(perm_state android.permission.POST_NOTIFICATIONS)"
    echo "== service =="
    adbt shell dumpsys activity services "$PKG" 2>/dev/null | grep -E 'ServiceRecord|isForeground|No services' | head -n 5
    echo "== snapshot file (inode-mtime-size) =="
    snapshot_stat
    echo "== recent logs =="
    adbt logcat -d -v time -s ObservatoryControl:I ObservatoryCamera:I SnapshotHttp:I '*:S' 2>/dev/null | tail -n "$LINES"
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
esac
