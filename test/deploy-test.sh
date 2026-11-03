#!/usr/bin/env bash
# Tests scripts/deploy.sh with fake adb, fake build/test scripts and fake Android tools in a temporary
# checkout (path with spaces). No device or Android SDK needed. Run: bash test/deploy-test.sh
set -uo pipefail
cd "$(dirname "$0")/.."
ROOT=$PWD
T=$(mktemp -d "${TMPDIR:-/tmp}/deploy test.XXXXXX")
trap 'rm -rf "$T"' EXIT
R="$T/repo dir"
mkdir -p "$R/scripts" "$R/test" "$T/bin" "$T/home/.local/share/observatory-ip-camera/signing"
cp scripts/adb-target.sh scripts/deploy.sh "$R/scripts/"
cat > "$R/build.sh" <<'B'
#!/usr/bin/env bash
cd "$(dirname "$0")"; mkdir -p dist
echo "fake build"
[[ -n ${FAKE_BUILD_FAIL:-} ]] && { echo "build error" >&2; exit 1; }
echo "${FAKE_APK_CONTENT:-0.5.0 5}" > dist/observatory-ip-camera.apk
B
cat > "$R/test/run.sh" <<'B'
#!/usr/bin/env bash
echo "fake tests"; [[ -n ${FAKE_TEST_FAIL:-} ]] && exit 1; exit 0
B
printf '#!/usr/bin/env bash\nexec bash "%s/test/fake-adb.sh" "$@"\n' "$ROOT" > "$T/bin/adb"
cat > "$T/bin/aapt2" <<'B'
#!/usr/bin/env bash
read -r n c < "$3"; echo "package: name='local.termux.observatorycamera' versionCode='$c' versionName='$n' platformBuildVersionName='13'"
B
cat > "$T/bin/apksigner" <<'B'
#!/usr/bin/env bash
grep -q BADSIG "$2" && exit 1; exit 0
B
printf '#!/usr/bin/env bash\nexit 0\n' > "$T/bin/keytool"
chmod +x "$T/bin"/*
printf "dist/\nbuild/\n" > "$R/.gitignore"
git -C "$R" init -q -b main && git -C "$R" add -A && git -C "$R" -c user.name=t -c user.email=t@t commit -qm init
export PATH="$T/bin:$PATH" HOME="$T/home" ADB="$T/bin/adb" DEPLOY_STATE_DIR="$T/state"
export FAKE_LOG="$T/calls" FAKE_DEVICES="$T/devices" FAKE_MDNS="$T/mdns"
unset TARGET
SIGN="$HOME/.local/share/observatory-ip-camera/signing"
D="$R/scripts/deploy.sh"
fails=0
reset() {
    printf 'A1 device\n' > "$FAKE_DEVICES"; : > "$FAKE_LOG"; rm -f "$FAKE_LOG.bad" "$R/dist/"* "$R/dist/".deploy-stamp
    echo k > "$SIGN/identity.p12"; echo p > "$SIGN/password"
    unset FAKE_BUILD_FAIL FAKE_TEST_FAIL FAKE_INSTALL FAKE_INSTALLED FAKE_APK_CONTENT; export FAKE_VERSION="0.5.0 5"
}
check() { # name rc pattern -- args
    local name=$1 want=$2 pat=$3; shift 3
    local out rc
    out=$(cd / && bash "$D" "$@" 2>&1); rc=$?
    if [[ $rc -ne $want ]] || ! grep -qE -- "$pat" <<<"$out" || [[ -e $FAKE_LOG.bad ]]; then
        echo "FAIL: $name (rc=$rc, want $want)"; echo "$out"; fails=$((fails+1))
    else echo "ok: $name"; fi
}
installed() { grep -q ' install ' "$FAKE_LOG"; }
expect_installed() { if installed; then echo "ok: $1 (installed)"; else echo "FAIL: $1 not installed"; fails=$((fails+1)); fi; }
expect_not_installed() { if installed; then echo "FAIL: $1 installed"; fails=$((fails+1)); else echo "ok: $1 (no install)"; fi; }

reset
check "successful deploy" 0 'result: SUCCESS' 
expect_installed "success"
grep -q -- '-s A1 install -r .*observatory-ip-camera.apk' "$FAKE_LOG" && echo "ok: install uses -s and -r" || { echo "FAIL: install args"; fails=$((fails+1)); }
grep -qE 'uninstall|pm clear|pm grant| -d ' "$FAKE_LOG" && { echo "FAIL: forbidden adb calls"; fails=$((fails+1)); }
out=$(cd / && bash "$D" 2>&1); echo "$out" | grep -E 'commit:|target: A1 \(model: FakePhone\)|apk version: 0.5.0 \(5\)|log: ' >/dev/null && echo "ok: summary" || { echo "FAIL: summary"; echo "$out"; fails=$((fails+1)); }
ls "$T/state/logs"/deploy-*.log >/dev/null 2>&1 && [[ $(stat -c %a "$T/state/logs") == 700 ]] && echo "ok: private log" || { echo "FAIL: log"; fails=$((fails+1)); }

reset; FAKE_TEST_FAIL=1 check "tests fail block build+install" 23 'test/run.sh failed'
expect_not_installed "tests fail"; [[ ! -e $R/dist/observatory-ip-camera.apk ]] && echo "ok: no build after test failure"
reset; echo "0.4.0 4" > /dev/null; mkdir -p "$R/dist"; echo "old 1" > "$R/dist/observatory-ip-camera.apk"
FAKE_BUILD_FAIL=1 check "build fail never installs old APK" 24 'build.sh failed'
expect_not_installed "build fail"; [[ ! -e $R/dist/observatory-ip-camera.apk ]] && echo "ok: old APK removed"
reset; FAKE_APK_CONTENT="0.5.0 5 BADSIG" check "bad signature" 25 'signature verification failed'
expect_not_installed "bad signature"
reset; FAKE_INSTALL=fail check "install failure" 27 'install -r failed'
reset; FAKE_VERSION="0.4.0 4" check "version verification failure" 28 'does not match'
reset; printf 'A1 device\nB2 device\n' > "$FAKE_DEVICES"; check "multiple devices refused" 26 'ambiguous'
expect_not_installed "ambiguous"
check "explicit target among several" 0 'target: B2' -s B2
grep -q -- '-s B2 install' "$FAKE_LOG" && ! grep -q -- '-s A1 install' "$FAKE_LOG" && echo "ok: only explicit target installed" || { echo "FAIL: wrong device"; fails=$((fails+1)); }
reset; printf 'A1 unauthorized\n' > "$FAKE_DEVICES"; check "unauthorized refused" 26 'unauthorized'
reset; printf 'A1 offline\n' > "$FAKE_DEVICES"; check "offline refused" 26 'offline'
reset; : > "$FAKE_DEVICES"; check "missing refused" 26 'no device found'
expect_not_installed "missing"
reset; export TARGET=STALE; check "stale TARGET refused" 26 '--refresh'
check "refresh target re-resolves" 0 'TARGET changes from .STALE. to .A1.' --refresh-target
unset TARGET
# signing identity
reset; rm -f "$SIGN/identity.p12" "$SIGN/password"; FAKE_INSTALLED=1 check "missing identity with installed app" 22 'no signing identity'
expect_not_installed "missing identity"; [[ ! -e $R/dist/observatory-ip-camera.apk ]] && echo "ok: no build without identity"
reset; rm -f "$SIGN/password"; check "incomplete identity" 22 'incomplete'
reset; rm -f "$SIGN/identity.p12" "$SIGN/password"; check "first install without identity allowed" 0 'will create one'
# modes
reset; check "test-only" 0 'result: SUCCESS' --test-only; expect_not_installed "test-only"
reset; check "build-only" 0 'apk version: 0.5.0' --build-only; expect_not_installed "build-only"
[[ -s $R/dist/observatory-ip-camera.apk ]] && echo "ok: build-only artifact" || { echo "FAIL: artifact"; fails=$((fails+1)); }
check "install-only fresh artifact" 0 'result: SUCCESS' --install-only; expect_installed "install-only"
: > "$FAKE_LOG"; echo "tamper 9" > "$R/dist/observatory-ip-camera.apk"
check "install-only rejects modified APK" 30 'does not match' --install-only; expect_not_installed "tampered"
reset; echo "0.5.0 5" > "$R/dist/observatory-ip-camera.apk"; check "install-only rejects unstamped APK" 30 'no validated artifact' --install-only
reset; check "build-only" 0 'SUCCESS' --build-only; echo "# change" >> "$R/build.sh"
check "install-only rejects stale checkout" 30 'checkout changed' --install-only; expect_not_installed "stale checkout"
git -C "$R" checkout -q -- build.sh
# lock
reset; mkdir -p "$T/state/deploy.lock"; echo $$ > "$T/state/deploy.lock/pid"; check "concurrent run refused" 29 'in progress'
rm -rf "$T/state/deploy.lock"; reset; check "stale lock recovered" 0 'SUCCESS'
# git update
reset; check "git-update without remote fails" 21 'no origin remote' --git-update
git -C "$R" remote add origin "https://example.com/other/repo.git"
check "git-update wrong remote" 21 'not the expected repository' --git-update
git -C "$R" remote set-url origin "$T/upstream.git" ; git init -q --bare "$T/upstream.git"; 
check "git-update wrong remote (path)" 21 'not the expected repository' --git-update
echo x > "$R/untracked"; export DEPLOY_REMOTE_PATTERN=upstream
check "git-update dirty tree refused" 21 'not clean' --git-update; rm "$R/untracked"
git -C "$R" push -q origin main 2>/dev/null
check "git-update ff-only success" 0 'git merge --ff-only' --git-update
check "dirty status reported" 0 'dirty: no' --test-only
echo x > "$R/untracked"; check "dirty status reported" 0 'dirty: yes' --test-only; rm "$R/untracked"
check "unknown option" 2 'unknown option' --bogus
exit $(( fails > 0 ))
