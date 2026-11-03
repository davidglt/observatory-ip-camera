#!/usr/bin/env bash
# Tests scripts/camera-control.sh against a fake adb. No device needed. Run: bash test/camera-control-test.sh
set -uo pipefail
cd "$(dirname "$0")/.."
T=$(mktemp -d)
trap 'kill ${SRV:-} 2>/dev/null; rm -rf "$T"' EXIT
export FAKE_LOG="$T/calls" FAKE_STATE="$T/state"
: > "$FAKE_LOG"
cat > "$T/adb" <<'FAKE'
#!/usr/bin/env bash
echo "$*" >> "$FAKE_LOG"
[[ $1 == -s ]] || { echo "adb called without -s: $*" >> "$FAKE_LOG.bad"; exit 1; }
shift 2
S=$(cat "$FAKE_STATE" 2>/dev/null || echo ok)
case "$1" in
    get-state) [[ $S == offline ]] && echo offline || echo device ;;
    forward) [[ $2 == --remove ]] || echo "$FAKE_PORT" ;;
    exec-out) cat "$FAKE_EXPORT" 2>/dev/null ;;
    logcat) cat "$FAKE_LOGCAT" 2>/dev/null ;;
    shell)
        case "$2 $3" in
            "pm path") [[ $S == noapp ]] || echo "package:/data/app/x.apk" ;;
            "dumpsys package") echo "      android.permission.POST_NOTIFICATIONS: granted=${FAKE_NOTIF:-true}"; echo "      android.permission.CAMERA: granted=${FAKE_CAM:-true}" ;;
            "run-as"*) cat > "$FAKE_UPLOAD" ;;
            "getprop ro.build.version.sdk") echo 33 ;;
            "am start") shift 3; ID=$(sed -n 's/.*--es request_id \([^ ]*\).*/\1/p' <<<"$*"); [[ -n ${FAKE_LOGLINE:-} ]] && echo "01-01 I/ObservatoryControl( 1): CONTROL id=$ID $FAKE_LOGLINE" >> "$FAKE_LOGCAT"; echo "Status: ok" ;;
        esac ;;
esac
exit 0
FAKE
chmod +x "$T/adb"
export ADB="$T/adb" FAKE_LOGCAT="$T/logcat" FAKE_EXPORT="$T/export" FAKE_UPLOAD="$T/upload"
unset TARGET
C=scripts/camera-control.sh
fails=0
expect() { # name expected_exit pattern -- args
    local name=$1 code=$2 pat=$3; shift 3
    local out rc
    out=$(bash "$C" "$@" 2>&1); rc=$?
    if [[ $rc -ne $code ]] || ! grep -qE -- "$pat" <<<"$out"; then
        echo "FAIL $name: rc=$rc (want $code), output: $out"; fails=$((fails+1))
    else echo "ok $name"; fi
}

expect no-target 2 "No target" status
[[ ! -s $FAKE_LOG ]] && echo "ok no adb call without target" || { echo "FAIL adb called without target"; fails=$((fails+1)); }
expect bad-target 2 "Invalid target" -s '-x' status
expect unknown-arg 2 "Unknown argument" -s T bogus
expect fetch-needs-out 2 "needs --out" -s T fetch
echo offline > "$FAKE_STATE"
expect offline 10 "expected 'device'" -s T status
echo noapp > "$FAKE_STATE"
expect not-installed 10 "not installed" -s T status
echo ok > "$FAKE_STATE"
expect setup 0 "CAMERA: granted" -s T setup-permissions
grep -q "^-s T shell pm grant local.termux.observatorycamera android.permission.POST_NOTIFICATIONS" "$FAKE_LOG" && echo "ok grant calls" || { echo "FAIL grant"; fails=$((fails+1)); }
FAKE_NOTIF=false expect start-notif-denied 11 "POST_NOTIFICATIONS" -s T start
FAKE_CAM=false expect capture-cam-denied 11 "CAMERA is not granted" -s T capture

# Fake HTTP server answering 404 with the native Server header.
python3 - "$T/port" <<'PY' &
import http.server, sys
class H(http.server.BaseHTTPRequestHandler):
    def do_HEAD(self):
        self.send_response(404); self.send_header("Server", "ObservatoryNative/0.3"); self.send_header("Content-Length", "0"); self.end_headers()
    def log_message(self, *a): pass
s = http.server.HTTPServer(("127.0.0.1", 0), H)
open(sys.argv[1], "w").write(str(s.server_port))
s.serve_forever()
PY
SRV=$!
for _ in 1 2 3 4 5 6 7 8 9 10; do [[ -s $T/port ]] && break; sleep 0.3; done
export FAKE_PORT=$(cat "$T/port")
: > "$FAKE_LOGCAT"
FAKE_LOGLINE="cmd=start result=listening port=8080" expect start-404 0 "HTTP 404" -s T --no-wake --timeout 5 start
FAKE_LOGLINE="cmd=start result=failed reason=Could not listen on port 8080: Address already in use" expect start-bind-fail 3 "Port bind failed" -s T --no-wake --timeout 5 start
FAKE_LOGLINE="cmd=start result=failed reason=notifications-blocked" expect start-blocked 11 "blocked" -s T --no-wake --timeout 5 start
FAKE_LOGLINE="cmd=start result=deferred reason=activity-not-resumed" expect start-deferred-timeout 5 "never resumed" -s T --no-wake --timeout 2 start
FAKE_LOGLINE="" expect start-timeout 5 "Timeout" -s T --no-wake --timeout 2 start
FAKE_LOGLINE="cmd=status result=status running=true port=8080 camera=granted notifications=ok" expect status-running 0 "RUNNING" -s T --no-wake --timeout 5 status
FAKE_LOGLINE="cmd=status result=status running=false port=8080 camera=granted notifications=ok" expect status-inconsistent 6 "HTTP is answering" -s T --no-wake --timeout 5 status
FAKE_LOGLINE="cmd=capture result=failed reason=activity-paused" expect capture-fail 3 "activity-paused" -s T --no-wake --timeout 5 capture
kill "$SRV"; wait "$SRV" 2>/dev/null; SRV=
export FAKE_PORT=1
FAKE_LOGLINE="cmd=status result=status running=false port=8080 camera=granted notifications=ok lastError=Could not listen" expect status-start-failure 3 "start failure" -s T --no-wake --timeout 5 status
FAKE_LOGLINE="cmd=status result=status running=false port=8080 camera=granted notifications=ok" expect status-stopped 4 "STOPPED" -s T --no-wake --timeout 5 status
FAKE_LOGLINE="cmd=stop result=stop-requested running=false" expect stop-ok 0 "STOPPED" -s T --no-wake --timeout 5 stop
# Continuous capture and settings.
FAKE_LOGLINE="cmd=continuous-start result=started state=running" expect cstart-ok 0 "STARTED" -s T --no-wake --timeout 5 continuous-start
FAKE_LOGLINE="cmd=continuous-start result=started already=true state=running" expect cstart-idempotent 0 "STARTED" -s T --no-wake --timeout 5 continuous-start
FAKE_LOGLINE="cmd=continuous-start result=failed reason=camera-busy owner=single-capture" expect cstart-busy 7 "already in use" -s T --no-wake --timeout 5 continuous-start
FAKE_LOGLINE="cmd=continuous-start result=failed reason=camera-permission-missing" expect cstart-perm 11 "camera-permission-missing" -s T --no-wake --timeout 5 continuous-start
FAKE_LOGLINE="cmd=continuous-start result=failed reason=Camera error: 2" expect cstart-error 3 "Camera error" -s T --no-wake --timeout 5 continuous-start
FAKE_LOGLINE="cmd=continuous-start result=deferred reason=activity-not-resumed" expect cstart-deferred 5 "never resumed" -s T --no-wake --timeout 2 continuous-start
FAKE_CAM=false expect cstart-cam-denied 11 "CAMERA is not granted" -s T continuous-start
FAKE_LOGLINE="cmd=continuous-status result=continuous-status state=running frames=12 fps=0.98 target_fps=1.0 last_capture=2026-01-01T00:00:00+0000 tz=UTC age_ms=412" expect cstatus-running 0 "RUNNING.*measured" -s T --no-wake --timeout 5 continuous-status
FAKE_LOGLINE="cmd=continuous-status result=continuous-status state=running frames=0 fps=n/a target_fps=1.0 last_capture=none age_ms=none" expect cstatus-no-frame 0 "no recent frame" -s T --no-wake --timeout 5 continuous-status
FAKE_LOGLINE="cmd=continuous-status result=continuous-status state=running frames=5 fps=1.00 target_fps=1.0 last_capture=x tz=UTC age_ms=60000" expect cstatus-stale 0 "no recent frame" -s T --no-wake --timeout 5 continuous-status
FAKE_LOGLINE="cmd=continuous-status result=continuous-status state=stopped frames=3 fps=1.00 target_fps=1.0 last_capture=none age_ms=none" expect cstatus-stopped 4 "STOPPED" -s T --no-wake --timeout 5 continuous-status
FAKE_LOGLINE="cmd=continuous-status result=continuous-status state=error frames=0 fps=n/a target_fps=1.0 last_capture=none age_ms=none lastError=Camera disconnected." expect cstatus-error 3 "Camera disconnected" -s T --no-wake --timeout 5 continuous-status
FAKE_LOGLINE="cmd=continuous-stop result=stop-requested state=running" expect cstop-timeout 5 "still active|Timeout" -s T --no-wake --timeout 2 continuous-stop
FAKE_LOGLINE="cmd=capture result=failed reason=camera-busy owner=continuous" expect capture-busy 7 "owned by another mode" -s T --no-wake --timeout 5 capture
FAKE_LOGLINE="cmd=config-show result=config overlay=on location=NYX position=bottom-left text_size=24 fields=location,datetime,fps,frames target_fps=1.0" expect config-show 0 "location=NYX" -s T --no-wake --timeout 5 config-show
FAKE_LOGLINE="cmd=config-set result=config overlay=off applied=next-frame" expect config-set 0 "overlay=off" -s T --no-wake --timeout 5 config-set overlay=off
grep -q -- "--es config 'overlay=off'" "$FAKE_LOG" && echo "ok config passed single-quoted" || { echo "FAIL config quoting"; fails=$((fails+1)); }
FAKE_LOGLINE="cmd=config-set result=failed reason=invalid-config:invalid-text_size:expected-10-96" expect config-set-rejected 2 "nothing changed" -s T --no-wake --timeout 5 config-set text_size=5
FAKE_LOGLINE="cmd=config-reset result=config overlay=on applied=next-frame" expect config-reset 0 "overlay=on" -s T --no-wake --timeout 5 config-reset
printf 'overlay=on\nlocation=NYX\n' > "$T/export"
FAKE_LOGLINE="cmd=config-export result=config overlay=on" expect config-export 0 "Exported" -s T --no-wake --timeout 5 config-export --out "$T/out.properties"
cmp -s "$T/export" "$T/out.properties" && echo "ok exported bytes" || { echo "FAIL export bytes"; fails=$((fails+1)); }
expect config-export-exists 2 "exists" -s T --no-wake config-export --out "$T/out.properties"
expect config-export-needs-out 2 "needs --out" -s T config-export
printf 'location=NYX\n' > "$T/in.properties"
FAKE_LOGLINE="cmd=config-import result=config overlay=on applied=next-frame" expect config-import 0 "Imported" -s T --no-wake --timeout 5 config-import --in "$T/in.properties"
cmp -s "$T/in.properties" "$T/upload" && echo "ok uploaded bytes" || { echo "FAIL upload bytes"; fails=$((fails+1)); }
FAKE_LOGLINE="cmd=config-import result=failed reason=invalid-import:unknown-key:x" expect config-import-rejected 2 "previous configuration kept" -s T --no-wake --timeout 5 config-import --in "$T/in.properties"
expect config-import-missing 2 "Cannot read" -s T config-import --in "$T/none"
expect config-import-needs-in 2 "needs --in" -s T config-import
head -c 9000 /dev/zero > "$T/big"
expect config-import-big 2 "larger than" -s T config-import --in "$T/big"
n=$(wc -l < "$FAKE_LOG")
expect config-injection 2 "Invalid assignment" -s T config-set 'overlay=on;reboot'
expect config-injection-sub 2 "Invalid assignment" -s T config-set 'location=$(id)'
expect config-space 2 "Invalid assignment|Unknown" -s T config-set "location=a b"
expect config-empty 2 "needs at least one" -s T config-set
expect assignment-elsewhere 2 "only valid after config-set" -s T status overlay=on
expect config-too-long 2 "too long" -s T config-set "location=$(printf 'a%.0s' $(seq 1 120))" "fields=$(printf 'b%.0s' $(seq 1 120))"
[[ $(wc -l < "$FAKE_LOG") -eq $n ]] && echo "ok rejected input made no adb call" || { echo "FAIL adb called for rejected input"; fails=$((fails+1)); }
grep -q -- ' -S' "$FAKE_LOG" && { echo "FAIL am start -S used"; fails=$((fails+1)); } || echo "ok no am start -S"
grep -q force-stop "$FAKE_LOG" && { echo "FAIL force-stop used"; fails=$((fails+1)); } || echo "ok no force-stop"
[[ ! -e $FAKE_LOG.bad ]] && echo "ok every adb call had -s" || { echo "FAIL adb without -s"; fails=$((fails+1)); }
[[ $fails -eq 0 ]] || { echo "$fails failures"; exit 1; }
echo "camera-control tests passed"
