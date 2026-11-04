#!/usr/bin/env bash
# Copyright (C) 2026 David González López-Tercero
# SPDX-License-Identifier: GPL-3.0-or-later
# See LICENSE in the repository root for licensing details.

# SSH-only deployment in Termux: tests -> build -> authorized adb install -> version verification.
# Reuses test/run.sh and build.sh (including their signing logic) and scripts/adb-target.sh.
# Works from any working directory. Never installs packages, uninstalls, clears data, downgrades,
# grants permissions, starts services or changes device settings.
set -uo pipefail
umask 077

PKG=local.termux.observatorycamera
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P) || exit 1
APK="$ROOT/dist/observatory-ip-camera.apk"
STAMP="$ROOT/dist/.deploy-stamp"
ADB="${ADB:-adb}"
STATE_DIR="${DEPLOY_STATE_DIR:-${XDG_STATE_HOME:-$HOME/.local/state}/observatory-ip-camera}"
LOG_DIR="$STATE_DIR/logs"
LOCK_DIR="$STATE_DIR/deploy.lock"
IDENTITY="$HOME/.local/share/observatory-ip-camera/signing"
MAX_LOG_BYTES="${DEPLOY_MAX_LOG_BYTES:-5242880}"
KEEP_LOGS="${DEPLOY_KEEP_LOGS:-20}"
REMOTE_PATTERN="${DEPLOY_REMOTE_PATTERN:-davidglt/observatory-ip-camera}"

EX_USAGE=2 EX_PREREQ=20 EX_GIT=21 EX_SIGNID=22 EX_TEST=23 EX_BUILD=24 EX_SIG=25
EX_TARGET=26 EX_INSTALL=27 EX_VERIFY=28 EX_LOCK=29 EX_STALE=30

MODE=deploy
GIT_UPDATE=0
TARGET_ARGS=()
LOG=
COMMIT=unknown
DIRTY=unknown
RESOLVED=
MODEL=unknown
APK_VERSION=unknown
APK_CODE=
APK_NAME=
HAVE_LOCK=0

usage() {
    cat <<'USAGE'
Usage: deploy.sh [mode] [options]
Modes (default: run tests, build, install, verify):
  --test-only      run test/run.sh only
  --build-only     run build.sh and verify the APK signature/metadata; no device needed
  --install-only   install the existing dist/observatory-ip-camera.apk, only if a stamp written by a
                   previous deploy.sh build matches the current commit/diff and APK hash (limitation:
                   untracked files are not part of the fingerprint; otherwise rebuild)
Options:
  -s, --target SERIAL  explicit adb target (else TARGET, else the single authorized device)
  --refresh-target     ignore a stale TARGET and re-resolve (see scripts/adb-target.sh)
  --mdns               opt-in local IPv4 discovery/reconnection via adb mdns services (or external
                       Python zeroconf only when adb explicitly reports mDNS unsupported)
  --wireless-on        opt-in bounded `wireless-debugging on` recovery if no usable device is found
                       when combined with --mdns, discovery is tried first; no services permits
                       local-phone activation, but backend failures and ambiguity do not
  --git-update         first run `git fetch` + `git merge --ff-only` of origin/main; requires a clean
                       tree on branch main whose origin matches the expected repository
  -h, --help           this help
Exit codes: 0 ok, 2 usage, 20 prerequisites, 21 git, 22 signing identity, 23 tests, 24 build,
  25 APK signature/metadata, 26 target, 27 install, 28 verification, 29 another run in progress,
  30 stale/unvalidated artifact.
Logs: private, bounded, timestamped, in $XDG_STATE_HOME/observatory-ip-camera/logs (default
  ~/.local/state/observatory-ip-camera/logs). Environment: ADB, TARGET, DEPLOY_STATE_DIR,
  DEPLOY_REMOTE_PATTERN, ADB_DISCOVERY_PYTHON, ADB_MDNS_TIMEOUT.
USAGE
}

say() { printf '%s\n' "$*"; [[ -n $LOG ]] && printf '%s\n' "$*" >> "$LOG"; return 0; }
err() { printf 'deploy: %s\n' "$*" >&2; [[ -n $LOG ]] && printf 'deploy: %s\n' "$*" >> "$LOG"; return 0; }

summary() {
    say "---- summary ----"
    say "commit: $COMMIT (dirty: $DIRTY)"
    say "target: ${RESOLVED:-none} (model: $MODEL)"
    say "apk version: $APK_VERSION"
    say "result: $1"
    say "log: ${LOG:-none}"
}

die() { # code message
    err "$2"
    summary "FAILED (exit $1)"
    exit "$1"
}

cleanup() {
    if (( HAVE_LOCK )); then rm -rf "$LOCK_DIR"; fi
}
trap cleanup EXIT

# Copy stdin to stdout and to the log, bounded by MAX_LOG_BYTES.
logtee() {
    local line n=0 cut=0
    while IFS= read -r line || [[ -n $line ]]; do
        printf '%s\n' "$line"
        if (( n < MAX_LOG_BYTES )); then
            printf '%s\n' "$line" >> "$LOG"
            n=$(( n + ${#line} + 1 ))
        elif (( ! cut )); then
            printf '[log truncated at %s bytes]\n' "$MAX_LOG_BYTES" >> "$LOG"; cut=1
        fi
    done
}

# run_phase CODE NAME CMD...: run with output logged, keeping the command's own exit status.
run_phase() {
    local code=$1 name=$2 rc; shift 2
    say "== $name =="
    "$@" 2>&1 | logtee
    rc=${PIPESTATUS[0]}
    (( rc == 0 )) || die "$code" "$name failed (exit $rc)"
}

acquire_lock() {
    mkdir -p "$STATE_DIR" || die $EX_PREREQ "cannot create $STATE_DIR"
    chmod 700 "$STATE_DIR"
    local pid
    for _ in 1 2; do
        if mkdir "$LOCK_DIR" 2>/dev/null; then
            printf '%s\n' "$$" > "$LOCK_DIR/pid"; HAVE_LOCK=1; return 0
        fi
        pid=$(cat "$LOCK_DIR/pid" 2>/dev/null || true)
        if [[ -n $pid ]] && kill -0 "$pid" 2>/dev/null; then
            err "another deploy run is in progress (pid $pid)"; exit $EX_LOCK
        fi
        rm -rf "$LOCK_DIR"
    done
    err "cannot take the deploy lock $LOCK_DIR"; exit $EX_LOCK
}

start_log() {
    mkdir -p "$LOG_DIR" || die $EX_PREREQ "cannot create $LOG_DIR"
    chmod 700 "$LOG_DIR"
    LOG="$LOG_DIR/deploy-$(date +%Y%m%d-%H%M%S)-$$.log"
    : > "$LOG"
    local old
    old=$(ls -1t "$LOG_DIR"/deploy-*.log 2>/dev/null | tail -n +$(( KEEP_LOGS + 1 )))
    [[ -z $old ]] || while IFS= read -r f; do rm -f -- "$f"; done <<<"$old"
}

need_tools() {
    local t
    for t in "$@"; do command -v "$t" >/dev/null 2>&1 || die $EX_PREREQ "missing tool: $t (install it yourself; nothing is installed automatically)"; done
}

git_state() {
    if git -C "$ROOT" rev-parse --git-dir >/dev/null 2>&1; then
        COMMIT=$(git -C "$ROOT" rev-parse --short=12 HEAD 2>/dev/null || echo unknown)
        if [[ -n $(git -C "$ROOT" status --porcelain 2>/dev/null) ]]; then DIRTY=yes; else DIRTY=no; fi
    fi
}

fingerprint() { # commit + hash of tracked diff
    local h d
    h=$(git -C "$ROOT" rev-parse HEAD 2>/dev/null || echo nogit)
    d=$(git -C "$ROOT" diff HEAD 2>/dev/null | sha256sum | cut -d' ' -f1)
    printf '%s:%s' "$h" "$d"
}

git_update() {
    need_tools git
    git -C "$ROOT" rev-parse --git-dir >/dev/null 2>&1 || die $EX_GIT "not a git checkout"
    local url branch
    url=$(git -C "$ROOT" remote get-url origin 2>/dev/null) || die $EX_GIT "no origin remote"
    [[ $url == *"$REMOTE_PATTERN"* ]] || die $EX_GIT "origin ($url) is not the expected repository ($REMOTE_PATTERN)"
    branch=$(git -C "$ROOT" symbolic-ref --short -q HEAD || true)
    [[ $branch == main ]] || die $EX_GIT "current branch is '${branch:-detached}', not main; switch manually"
    [[ -z $(git -C "$ROOT" status --porcelain) ]] || die $EX_GIT "working tree is not clean; commit or stash manually (nothing is discarded)"
    run_phase $EX_GIT "git fetch" git -C "$ROOT" fetch origin main
    run_phase $EX_GIT "git merge --ff-only" git -C "$ROOT" merge --ff-only FETCH_HEAD
}

# Parse the single "package:" line of aapt2 badging output into APK_PKG_NAME/APK_PKG_CODE/APK_PKG_VERSION.
# Fields are matched by exact name (whitespace-delimited name='value' pairs); nothing is eval'd or executed.
# Fails (returns 1) on aapt2 failure, no/multiple package lines, malformed, duplicate, missing or empty fields.
parse_badging() { # $1 = aapt2 badging output
    local line rest key val n=0 l
    APK_PKG_NAME= APK_PKG_CODE= APK_PKG_VERSION=
    local seen_name=0 seen_code=0 seen_ver=0
    while IFS= read -r l; do
        if [[ $l == "package:"* ]]; then n=$((n+1)); line=$l; fi
    done <<<"$1"
    (( n == 1 )) || return 1
    rest=${line#package:}
    while [[ $rest =~ ^[[:space:]]+([^[:space:]=\']+)=\'([^\']*)\'(.*)$ ]]; do
        key=${BASH_REMATCH[1]} val=${BASH_REMATCH[2]} rest=${BASH_REMATCH[3]}
        case $key in
            name) (( seen_name )) && return 1; seen_name=1; APK_PKG_NAME=$val ;;
            versionCode) (( seen_code )) && return 1; seen_code=1; APK_PKG_CODE=$val ;;
            versionName) (( seen_ver )) && return 1; seen_ver=1; APK_PKG_VERSION=$val ;;
        esac
    done
    [[ -z ${rest//[[:space:]]/} ]] || return 1
    [[ -n $APK_PKG_NAME && -n $APK_PKG_CODE && -n $APK_PKG_VERSION ]]
}

verify_apk() {
    need_tools aapt2 apksigner
    [[ -s $APK ]] || die $EX_SIG "APK missing: $APK"
    apksigner verify "$APK" >/dev/null 2>&1 || die $EX_SIG "APK signature verification failed"
    local badging pkg code name
    badging=$(aapt2 dump badging "$APK" 2>/dev/null) || die $EX_SIG "aapt2 failed to read APK metadata"
    parse_badging "$badging" || die $EX_SIG "unreadable, missing, empty or ambiguous APK package metadata"
    pkg=$APK_PKG_NAME code=$APK_PKG_CODE name=$APK_PKG_VERSION
    [[ $pkg == "$PKG" ]] || die $EX_SIG "unexpected APK metadata (package='$pkg' versionCode='$code' versionName='$name')"
    APK_VERSION="$name ($code)"; APK_CODE=$code; APK_NAME=$name
    say "APK: $PKG $APK_VERSION, signature OK"
}

resolve_target() {
    local out tmp rc
    tmp=$(mktemp "${TMPDIR:-/tmp}/deploy-target.XXXXXX") || die $EX_PREREQ "mktemp failed"
    out=$(bash "$ROOT/scripts/adb-target.sh" "$@" 2>"$tmp"); rc=$?
    [[ -n $LOG ]] && cat "$tmp" >> "$LOG"
    cat "$tmp" >&2
    MODEL=$(sed -n 's/.* model=\(.*\) device_serial=.*/\1/p' "$tmp" | tail -n 1)
    MODEL=${MODEL:-unknown}
    rm -f "$tmp"
    (( rc == 0 )) && [[ -n $out ]] || die $EX_TARGET "no usable target (adb-target exit $rc)"
    RESOLVED=$out
}

check_signing_identity() {
    local key="$IDENTITY/identity.p12" pass="$IDENTITY/password" installed
    if [[ -s $key && -s $pass ]]; then
        need_tools keytool
        keytool -list -keystore "$key" -storepass:file "$pass" -alias app >/dev/null 2>&1 || die $EX_SIGNID "signing identity in $IDENTITY is unreadable; restore it from backup before building"
        return 0
    fi
    if [[ -e $key || -e $pass ]]; then
        die $EX_SIGNID "signing identity in $IDENTITY is incomplete (need identity.p12 and password); restore it from backup"
    fi
    if [[ -n $RESOLVED ]]; then
        installed=$("$ADB" -s "$RESOLVED" shell pm path "$PKG" 2>/dev/null | tr -d '\r')
        if [[ $installed == package:* ]]; then
            die $EX_SIGNID "$PKG is installed on $RESOLVED but no signing identity exists in $IDENTITY; a new key could not update it. Restore the original identity.p12 and password (never uninstall: app data would be lost)"
        fi
    fi
    say "note: no signing identity yet; build.sh will create one (first install only)"
}

install_and_verify() {
    # Re-validate the explicit target right before the install.
    resolve_target -s "$RESOLVED"
    local out rc dump code name
    say "== adb install -r =="
    out=$("$ADB" -s "$RESOLVED" install -r "$APK" 2>&1); rc=$?
    printf '%s\n' "$out" | logtee
    (( rc == 0 )) && grep -q 'Success' <<<"$out" || die $EX_INSTALL "adb install -r failed (exit $rc); nothing was uninstalled or cleared"
    say "== verify installed version =="
    dump=$("$ADB" -s "$RESOLVED" shell dumpsys package "$PKG" 2>&1 | tr -d '\r') || true
    code=$(sed -n 's/.*versionCode=\([0-9][0-9]*\).*/\1/p' <<<"$dump" | head -n 1)
    name=$(sed -n 's/.*versionName=\([^ ]*\).*/\1/p' <<<"$dump" | head -n 1)
    [[ $code == "$APK_CODE" && $name == "$APK_NAME" ]] || die $EX_VERIFY "installed version ($name/$code) does not match built APK ($APK_NAME/$APK_CODE)"
    say "installed version matches: $name ($code)"
}

write_stamp() {
    printf 'fingerprint=%s\napk_sha256=%s\n' "$(fingerprint)" "$(sha256sum "$APK" | cut -d' ' -f1)" > "$STAMP"
}

check_stamp() {
    [[ -f $STAMP && -s $APK ]] || die $EX_STALE "no validated artifact: run deploy.sh --build-only (or the default mode) first"
    local fp sha
    fp=$(sed -n 's/^fingerprint=//p' "$STAMP"); sha=$(sed -n 's/^apk_sha256=//p' "$STAMP")
    [[ $fp == "$(fingerprint)" ]] || die $EX_STALE "checkout changed since the APK was built; rebuild"
    [[ $sha == "$(sha256sum "$APK" | cut -d' ' -f1)" ]] || die $EX_STALE "APK does not match its build stamp; rebuild"
}

do_build() {
    rm -f "$APK" "$STAMP"
    run_phase $EX_BUILD "build.sh" bash "$ROOT/build.sh"
    [[ -s $APK ]] || die $EX_BUILD "build.sh reported success but produced no APK"
    verify_apk
    write_stamp
}

main() {
    while (( $# )); do
        case $1 in
            --test-only) MODE="test" ;;
            --build-only) MODE=build ;;
            --install-only) MODE=install ;;
            --git-update) GIT_UPDATE=1 ;;
            --refresh-target) TARGET_ARGS+=(--refresh) ;;
            --mdns) TARGET_ARGS+=(--mdns) ;;
            --wireless-on) TARGET_ARGS+=(--wireless-on) ;;
            -s|--target) [[ $# -ge 2 && -n $2 ]] || { echo "deploy: $1 needs a value" >&2; exit $EX_USAGE; }
                TARGET_ARGS+=(-s "$2"); shift ;;
            -h|--help) usage; exit 0 ;;
            *) echo "deploy: unknown option: $1" >&2; usage >&2; exit $EX_USAGE ;;
        esac
        shift
    done
    acquire_lock
    start_log
    git_state
    say "deploy mode=$MODE root=$ROOT"
    if (( GIT_UPDATE )); then git_update; git_state; fi
    say "commit: $COMMIT (dirty: $DIRTY)"
    case $MODE in
        test)
            run_phase $EX_TEST "test/run.sh" bash "$ROOT/test/run.sh" ;;
        build)
            check_signing_identity
            do_build ;;
        install)
            need_tools "$ADB"
            resolve_target "${TARGET_ARGS[@]}"
            check_stamp
            verify_apk
            check_signing_identity
            install_and_verify ;;
        deploy)
            need_tools "$ADB"
            resolve_target "${TARGET_ARGS[@]}"
            check_signing_identity
            run_phase $EX_TEST "test/run.sh" bash "$ROOT/test/run.sh"
            do_build
            install_and_verify ;;
    esac
    summary SUCCESS
}

main "$@"
exit $?
