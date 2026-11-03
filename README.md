# Observatory IP Camera

Native Java Android camera app developed, compiled and signed in Termux. Source, comments, messages and documentation are in English.

## Status: 0.5.0 (continuous capture, not yet compiled to an APK or device-validated)

New in 0.5.0: sustained Camera2 JPEG capture with a burned-in overlay, controlled only over authorized ADB (see "Continuous capture and overlay" below). Everything below about 0.4.0 still applies.

### 0.4.0 baseline

Camera2 single JPEG capture plus a native HTTP snapshot server owned by an explicit foreground service (SnapshotHttpService), bound explicitly to 0.0.0.0:8080 (all IPv4 interfaces). No Python server or manual export is required for native HTTP. GET and HEAD /snapshot.jpg read the latest private app snapshot. Requests do not trigger capture. No directory listing, continuous video or MJPEG stream is implemented.

The owner validated the previous capture implementation at 1280x720, retrieved the 31671-byte JPEG and verified byte-identical HTTP transfer through the separate Python helper. The native HTTP integration has not yet been built or tested on the phone. MainActivity's existing status text still identifies the capture component as 0.2.0; APK package metadata is 0.4.0 and native HTTP logs use SnapshotHttp.

## Security and lifetime

WARNING: While the server is running it exposes the last private snapshot without authentication or TLS to any client that can reach port 8080. Use only a trusted private network. Do not configure router port forwarding or expose it to the Internet. Binding to 0.0.0.0 is not IPv6 support, Internet publication or a guarantee that Android/network policy permits remote access. Use the actual phone address in the browser, not 0.0.0.0.

The server no longer starts with the application process. It runs only in SnapshotHttpService, a foreground service started by pressing Start server in a visible, resumed MainActivity (or by the shell-protected non-touch ControlActivity, see below), or stopped by Stop server or the Stop action of its ongoing notification (which also offers Open app). Start and Stop are idempotent; there is never more than one listener. Stop closes the listening socket and any active client socket and ends the worker thread. The UI shows "running" only after the port was bound successfully; a bind failure (for example port 8080 occupied) or a start failure is shown as an error and the service stops itself.

Notification permission: on Android 13 (API 33) the app asks for POST_NOTIFICATIONS when you press Start server. If it is denied (or notifications/the channel are blocked), the server is not started, because a running server must stay visible and stoppable.

Manifest: FOREGROUND_SERVICE and POST_NOTIFICATIONS permissions; a non-exported service without a foregroundServiceType. targetSdk 33 does not require a type, and this service only serves HTTP: it is not a camera service and has no background camera access (no FOREGROUND_SERVICE_CAMERA). If the target is raised to API 34 or later, a type (likely specialUse with its permission and justification property) must be chosen and declared then.

Restart semantics: START_NOT_STICKY. If Android kills the process the server stays stopped until you press Start server again; there is no boot receiver, no automatic restart and no resurrection after Stop. Swiping the app from recents does not by itself stop the service.

A foreground service is not a guarantee. Android may still kill the process, and Doze, battery policy, Wi-Fi power saving or network suspension can make the server unreachable, especially with the screen off. No wake lock, Wi-Fi lock or battery-optimization bypass is used. It does not bypass lock-screen or camera restrictions: capture still needs a visible, resumed, focused activity and runs only on demand; the service never uses the camera. Force-stopping the app stops the service and server. The debuggable APK is for development only.

Requests are sequential, header size is limited to 8192 bytes with a five-second read deadline, and JPEG size is limited to 16 MiB. Slow readers can still block this basic server; it is not production hardened. Camera writes publish the image using the existing temporary-file rename. The server reads an opened snapshot file and never exposes arbitrary paths. No image bytes are logged. Old snapshots survive restarts and failed capture attempts.

## Non-touch control over SSH (screen broken / no touch)

All existing features can be driven without touching the phone, from Termux over SSH via an already authorized adb connection (this document does not cover initial adb pairing, which needs authorization on the device). Use scripts/camera-control.sh. It always requires an explicit device (`-s TARGET` or the TARGET environment variable; the exact serial or host:port from `adb devices`), never auto-selects among several devices, has no hardcoded adb port, and checks that the device state is `device` and the app is installed.

```sh
bash scripts/camera-control.sh -s "$TARGET" setup-permissions   # only when you want it: pm grant CAMERA and POST_NOTIFICATIONS
bash scripts/camera-control.sh -s "$TARGET" start
bash scripts/camera-control.sh -s "$TARGET" status
bash scripts/camera-control.sh -s "$TARGET" capture --out snapshot.jpg
bash scripts/camera-control.sh -s "$TARGET" fetch --out snapshot2.jpg
bash scripts/camera-control.sh -s "$TARGET" logs
bash scripts/camera-control.sh -s "$TARGET" stop
```

Options: `--timeout SECONDS` (default 20), `--no-wake` (skip the KEYCODE_WAKEUP key event, which does not bypass a secure lock screen), `--force`, `--lines N`. Exit codes: 0 ok, 2 usage, 3 failure, 4 stopped, 5 timeout, 6 inconsistent, 10 device/app problem, 11 missing permission.

How it works:
- start/stop/status launch ControlActivity (explicit component, extras `command` = start|stop|status and `request_id`, validated; invalid values are logged and ignored; handled identically in onCreate and onNewIntent). The foreground service is started only while that activity is resumed; if it is not resumed the start is deferred until onResume and the script reports a timeout (screen off/locked/dozing). Foreground-start restrictions are never bypassed. The activity never shows permission dialogs.
- Results are CONTROL lines under the ObservatoryControl log tag, matched by a fresh unique request id, so old log lines are not mistaken for current results. `start` waits (bounded) for the service to log `listening`, then probes the real listener (through `adb forward tcp:0`, no fixed host port) instead of trusting `am start`. It distinguishes: running with a snapshot (HTTP 200), running without a snapshot yet (HTTP 404), unreadable snapshot (503), stopped, start failure (for example occupied port, blocked notifications, with the reason) and timeout.
- `stop` stops only the service (the app is not force-stopped) and waits until the listener is down.
- `capture` runs `am start -W` on MainActivity with `--ez capture true --ez no_prompt true --es request_id ID`. It never uses `am start -S`, so a running server survives. `no_prompt` makes a missing camera permission an error instead of a dialog. Fresh evidence: the app logs `CONTROL id=ID cmd=capture result=saved ...` after publishing the file, and the script also compares the snapshot file identity (inode-mtime-size) before and after. Capture still needs a resumed activity with window focus (screen on, not dozing); pause cancels it. `--out FILE` fetches afterwards.
- `fetch` copies the latest stored files/snapshot.jpg through `run-as` (debuggable APK), checks the JPEG marker and refuses to overwrite without `--force`. Fetching alone is NOT proof of a fresh capture; use `capture` (or compare `logs` output) for that.
- `setup-permissions` runs `adb shell pm grant` for CAMERA and POST_NOTIFICATIONS (API 33 only). It is never run implicitly. Missing permissions and blocked notifications/channel are reported as actionable errors (exit 11).

Security of the control entry: ControlActivity is exported but declared with `android:permission="android.permission.DUMP"`, a signature/development permission that the adb shell (and root) hold and that the platform enforces when an activity is started; ordinary apps do not hold it and are rejected. This does not make the entry fully private: code in the same UID, root, and any app that was explicitly granted DUMP (for example via `adb shell pm grant`) can still invoke it, and it is the platform permission check, not the explicit intent or request_id extra (a plain log correlation token), that restricts callers. The launcher MainActivity must remain exported, and its pre-existing `capture` extra (plus the new optional `request_id`/`no_prompt`) is therefore callable by any local app; it can only trigger a single capture from a visible activity, but it is not caller-authenticated. The service and (absent) receivers are not exported, there are no network control endpoints, and the HTTP server still has no authentication or TLS. The DUMP enforcement is expected platform behavior but was not verified on a device here.

Not promised: lock-screen bypass, background camera, Doze immunity, automatic recovery after reboot or process death, or initial adb pairing.

### SSH-only manual checklist

Set TARGET to a verified device and run each step; record fresh output.
1. Launch/reuse: `start`, then `start` again (idempotent, one notification), then `status`. Re-run `capture` with the app already in front (onNewIntent path) and with the app in the background.
2. Pause/resume: send `adb -s "$TARGET" shell input keyevent KEYCODE_SLEEP`, then `capture --no-wake --timeout 10`: expect timeout/`activity-paused`, not a stale success. Repeat after wake. With the screen off `start` reports the deferral timeout.
3. Denied permissions: `adb -s "$TARGET" shell pm revoke local.termux.observatorycamera android.permission.CAMERA` (and POST_NOTIFICATIONS); `capture` and `start` must exit 11 without dialogs. Then `setup-permissions` and retry.
4. Start/stop/restart: `start`, `stop` (listener refused, app still alive in `logs`), `start` again immediately.
5. Occupied port: run `python scripts/serve-snapshot.py --snapshot snapshot.jpg` in Termux (or `nc -l 8080`), `start` must exit 3 with the port message; free it and retry.
6. Capture while serving: `start`, `capture --out a.jpg`, `status` must still be RUNNING with HTTP 200, and `curl` of the forwarded port returns the same bytes (`cmp`).
7. Stale snapshot: with an old snapshot present, `fetch` alone prints no freshness claim; `capture` with a deliberately blocked camera (another camera app open, or the screen off) must fail instead of reporting fresh; 404 appears only after `adb shell run-as local.termux.observatorycamera rm files/snapshot.jpg`.
8. No-snapshot server: after removing the file, `status` shows HTTP 404 and still counts as running.
9. Multiple adb devices: with two devices attached run with no `-s`/TARGET (must refuse) and with each serial explicitly; with a wrong serial it must fail without touching the other device.
10. Caller restriction: from another installed app (or `adb shell run-as <other uid>`), start ControlActivity and confirm a permission denial; document the result.
11. Doze/lock screen: record behavior; no guarantee.

Validation status: the shell script is tested against a fake adb (test/camera-control-test.sh) and ControlCommand by a pure Java test (`bash test/run.sh`). ControlActivity, the service/MainActivity changes and the manifest were not compiled against android.jar or run on a device in the authoring environment; run `bash build.sh` in Termux and the checklist above.

## Continuous capture and overlay

Sustained JPEG capture runs in ContinuousCaptureService, started and stopped only over SSH through the same shell-protected ControlActivity entry and scripts/camera-control.sh (explicit TARGET required, same whitelisting, no display or touch needed). Single capture and the independent HTTP server keep working as before.

```sh
bash scripts/camera-control.sh -s "$TARGET" setup-permissions       # CAMERA + POST_NOTIFICATIONS, only when you choose
bash scripts/camera-control.sh -s "$TARGET" config-show
bash scripts/camera-control.sh -s "$TARGET" config-set overlay=on location=NYX position=bottom-left text_size=24 fields=location,datetime,fps,frames target_fps=1
bash scripts/camera-control.sh -s "$TARGET" continuous-start
bash scripts/camera-control.sh -s "$TARGET" continuous-status
bash scripts/camera-control.sh -s "$TARGET" continuous-stop
```
Exit codes add 7 = camera busy (another owner holds the camera).

Camera ownership: exactly one owner at a time (CameraArbiter): the single-capture activity or the continuous service. A conflicting request is rejected (log reason `camera-busy owner=...`, script exit 7), never coordinated by opening a second camera. Stop continuous capture before running `capture`. HTTP is independent: it only reads the published file, and starting/stopping it never touches the camera.

Android rules respected: the service is declared `android:foregroundServiceType="camera"` and entered with `startForeground(..., FOREGROUND_SERVICE_TYPE_CAMERA)`. At targetSdk 33 the manifest needs FOREGROUND_SERVICE (already declared) and the runtime CAMERA permission; `FOREGROUND_SERVICE_CAMERA` only exists from API 34 and is deliberately not declared (add it, and re-check, if the target SDK is raised). Android 11+ only grants while-in-use camera access to a foreground service started while the app is visible, so `continuous-start` launches ControlActivity, requires it to be resumed and keeps it open until the service reports running or an error; if the screen is off/locked the start is deferred and times out. Missing CAMERA/POST_NOTIFICATIONS are reported as errors (exit 11) without dialogs. Nothing bypasses camera, background or lock-screen restrictions; Samsung/Android may still disconnect the camera (screen off, privacy toggle, another app), which ends the session in state `error`. START_NOT_STICKY, no boot receiver, no automatic restart, no wake lock, no battery-optimization bypass.

Pipeline: one camera device, one capture session and one ImageReader (maxImages 2) per run. One capture is in flight at a time; the next is issued only after the previous frame was processed and published (or failed), after waiting for the target interval measured on a monotonic clock. Memory is bounded (one JPEG buffer and at most three bitmaps of one frame). Camera callbacks run on one thread, decode/overlay/file I/O on another; the UI thread does none of it (single capture was moved off the main thread too). A 10 s watchdog and 5 consecutive failures end the session with state `error`. Stop and all error paths cancel pending work and close the reader, session, device, both threads and the orientation listener; start/stop can be repeated.

Rate: default target 1 FPS, optional 2 FPS (`target_fps` 0.1-2.0). The target is a ceiling, not a promise. `continuous-status` reports the measured FPS (sliding window of the last 10 frames, monotonic time; `n/a` until two frames exist), `late` (cycles longer than the target interval) and processing time (last/avg/max ms, bytes, overlay mode). Fields: `state` (stopped|starting|running|error), `frames` (session frame count, successfully published frames), `last_capture` (wall-clock time of the frame in ISO form) with `tz` (device timezone) and `age_ms` (monotonic age computed at query time). The stored snapshot is only "fresh" if `frames` increases and `age_ms` is small; a stopped or errored session reports its state and never a fresh time. The frame time is taken when the image is received from the camera, so it can trail the exposure by the camera latency; it is never the HTTP retrieval time.

Overlay (default on, burned into the JPEG pixels so snapshot clients and any later MJPEG consumer see it): bottom-left, green bold text with black outline and shadow, margins of 3% of the short side (min 8 px). Default text:

```
NYX
2026-11-03 18:00:00 CET
FPS: 0.98 | Frames: 12
```
NYX is a configurable text label, not GPS; no location permission is used. FPS shows `--` until it can be measured. Single capture uses the same overlay with `FPS: --` and `Frames: 1`.

Settings (persistent, SharedPreferences, validated as one atomic command; an invalid value changes nothing): `overlay=on|off`, `location=` 1-24 chars of A-Za-z0-9_.- (no spaces: values pass through the device shell and are whitelisted), `position=bottom-left|bottom-right|top-left|top-right`, `text_size=10-96` (for a 720 px short side, scaled to the image; long lines shrink to fit), `fields=` comma list of location,datetime,fps,frames, `target_fps=0.1-2.0`. Apply semantics: saved immediately and used by the next frame of a running session and by the next single capture; no restart needed. The camera resolution (nearest to 1280x720) is not configurable.

Orientation and EXIF: JPEG_ORIENTATION is requested per capture from the physical device angle as before. With the overlay on, the EXIF orientation tag of the camera JPEG is read and applied once (rotate/mirror), the text is drawn on the upright pixels and the re-encoded JPEG has no EXIF orientation, so viewers cannot rotate it a second time. If a HAL rotates pixels and leaves the tag at normal, nothing more is applied. If the HAL ignores JPEG_ORIENTATION and writes no tag, the image cannot be corrected by the app. If the orientation sensor delivers nothing in the background the fallback angle 0 is used.

Behavior change: previous versions saved the camera JPEG bytes unchanged. With the overlay on, every frame is decoded, drawn on and re-encoded (quality 90), which costs CPU/time and one lossy generation; with `overlay=off` the camera bytes are saved unchanged (no decode, no re-encode, camera EXIF kept) and `overlay_mode=passthrough` is reported. Processing cost is measured and reported (`proc_*_ms`), not promised; sustained 2 FPS may not be reached on this device.

Publication: each frame is written to a temporary file, synced and renamed over `files/snapshot.jpg` (private, atomic; the temp name is never published), only if the session is still current, so HTTP clients always get a complete file and a stopped session never publishes a late frame. Overlay text is rendered once per capture; an old file is never re-stamped. `GET /snapshot.jpg` is unchanged and compatible. Still not included: MJPEG endpoint, continuous web viewer, authentication, web settings endpoint. The HTTP server remains unauthenticated and without TLS: trusted LAN only.

Future Settings button: a later authenticated web viewer may show a Settings button as a viewer-layer control (HTML/UI next to the image, backed by authenticated web configuration). It will not be burned into recorded pixels and no fake clickable JPEG button exists now.

### Continuous capture SSH-only checklist

Set TARGET; run every step with the phone screen possibly off/unconnected to any display. Record fresh output each time.
1. `setup-permissions`, `config-reset`, `config-show` (defaults: overlay=on location=NYX position=bottom-left text_size=24 target_fps=1.0).
2. Fresh frames: `continuous-start` (exit 0), then `continuous-status` three times 5 s apart: `frames` must increase, `age_ms` stay below about 2x the interval, `last_capture` advance, fps near the target. `fetch --out a.jpg`, wait a few seconds, `fetch --out b.jpg`: the files must differ and show different times in the overlay.
3. Measured rates: run with `target_fps=1`, then `config-set target_fps=2` (applies without restart); compare `fps`, `late`, `proc_avg_ms`; record whether 2 FPS is reached.
4. Stop/restart: `continuous-stop` (exit 0, state stopped, camera released: `dumpsys media.camera` shows no client), `continuous-stop` again (idempotent), `continuous-start`; repeat 10 times; `frames` restarts from 0 each session.
5. HTTP coexistence: `start` the HTTP server, then `continuous-start`; `curl` the forwarded port repeatedly: always complete JPEGs with changing time stamps; stop each independently; stopping HTTP leaves capture running and vice versa.
6. Single-capture conflict: while continuous runs, `capture` must exit 7 (camera-busy) and continuous must keep running; after `continuous-stop`, `capture` works and a following `continuous-start` works. `continuous-start` during a running single capture must be rejected (exit 7) or succeed after it.
7. Repeated commands: `continuous-start` twice (second reports already=true, one notification), `config-set` twice, `continuous-status` while starting.
8. Activity/background: press Home (`input keyevent KEYCODE_HOME`) while running: capture should continue (foreground service) - record result; also `am force-stop` stops it and nothing restarts.
9. Screen-off/lock: `input keyevent KEYCODE_SLEEP` during a run: record whether `frames` keeps increasing or the state becomes `error`; `continuous-start` with the screen off must time out (deferred) rather than start. No bypass is attempted.
10. Orientation/overlay: for portrait, landscape left, landscape right and reverse portrait (and with auto-rotation locked), set the phone and run `fetch`; scene and text must both be upright with the text in the bottom-left of the upright image; check `position=` variants and `overlay=off` (scene upright via EXIF in a viewer honoring EXIF, no text, `overlay_mode=passthrough`).
11. Persistence: `config-set overlay=off location=TEST target_fps=2 text_size=40`, `am force-stop`, `config-show` must still show the values; `config-reset`.
12. Denied permissions: `pm revoke ... CAMERA` then `continuous-start` -> exit 11 with no dialog; also POST_NOTIFICATIONS. Re-grant and retry.
13. Errors: `config-set text_size=5`, `target_fps=3`, `fields=gps`, `position=middle`, `location='a b'`, `overlay=on;id` must all be rejected (exit 2) and `config-show` unchanged. Open another camera app during a run: record state/error and that `continuous-stop` still cleans up.
14. Sustained run: 1-2 hours; sample `continuous-status` and `dumpsys meminfo local.termux.observatorycamera`, `dumpsys battery` temperature and `dumpsys thermalservice` every 10 minutes: frames increase, no growing memory, processing time stable, storage not growing (one snapshot file). Record thermal throttling.

Validation status of this section: pure tests (config validation, overlay text/layout, EXIF decomposition, FPS/age math, camera arbitration, atomic publisher) and shell tests against a fake adb pass in `bash test/run.sh`. The Android sources were type-checked with javac against a Robolectric API 33 android-all jar, but build.sh (aapt2/d8/apksigner), the APK, Camera2 behavior, foreground-camera rules, EXIF handling by the Samsung HAL and every checklist step were NOT run or device-validated in the authoring environment.

## Build in Termux

Target: Galaxy S20 FE SM-G780G, Android 13/API 33, One UI 5.1, Termux from Google Play. No root, Gradle, Android Studio, proot or cloud build. Use packages from your existing Termux distribution.

```sh
pkg install git openjdk-21 aapt aapt2 d8 apksigner zip coreutils
```

Provision the official API 33 android.jar at $HOME/.local/share/android-sdk/platforms/android-33/android.jar or set ANDROID_PLATFORM_JAR. Guide: https://github.com/davidglt/wireless-debugging/blob/main/docs/android-platform-33.md

```sh
git pull --ff-only && bash build.sh
```

Keep the signing identity and password under $HOME/.local/share/observatory-ip-camera/signing private, backed up and unchanged for updates. Never commit keys, credentials or camera images.

## Install and test over SSH

First stop the old Python server with Ctrl+C in its session. It uses the same port, even if bound only to loopback. Do not run both servers. A native bind failure is shown in the app and logged; the camera still works.

Use your current authorized ADB target; wireless ADB ports are dynamic. The wireless-debugging companion documents discovery and pairing. Run each command separately, from the repository root:

```sh
adb -s "$TARGET" get-state
```
```sh
adb -s "$TARGET" install -r dist/observatory-ip-camera.apk
```

Proceed only with state device and installation Success. If needed, deliberately grant the declared camera permission through authorized ADB:

```sh
adb -s "$TARGET" shell pm grant local.termux.observatorycamera android.permission.CAMERA
```

Wake before capture; the owner observed Dozing prevented the activity from becoming available. This does not bypass a secure lock screen. Capture waits for resumed window focus and cancels on pause. Camera privacy settings and other camera clients may prevent capture.

```sh
adb -s "$TARGET" shell input keyevent KEYCODE_WAKEUP && adb -s "$TARGET" shell am start -W -n local.termux.observatorycamera/.MainActivity --ez capture true
```
```sh
adb -s "$TARGET" logcat -d -v time -s ObservatoryCamera:I SnapshotHttp:I '*:S' | tail -n 30
```

Start the server from the UI first (see below). Wait for current Listening on 0.0.0.0:8080 and Snapshot saved messages. Compare timestamps; old messages and files are not proof of fresh success.

```sh
curl --fail --silent --show-error -D - http://127.0.0.1:8080/snapshot.jpg -o snapshot-native.jpg
```

Expected server header: ObservatoryNative/0.3, not ObservatorySnapshot/0.1 (the old Python helper). From another reachable LAN device, open http://PHONE_IP:8080/snapshot.jpg using the actual phone IP. Test LAN access separately; loopback success does not prove it. The endpoint returns 404 before the first snapshot and 503 for an unreadable, oversized or incomplete image.

### Foreground service validation

Run each step separately and check logcat (`logcat -d -v time -s SnapshotHttp:I ObservatoryCamera:I '*:S'`) and `adb -s "$TARGET" shell dumpsys activity services local.termux.observatorycamera` for fresh evidence.

1. Fresh install: no listener yet (`curl` to 127.0.0.1:8080 fails) even after launching the app. It does not start on launch.
2. Notification permission: `adb -s "$TARGET" shell pm revoke local.termux.observatorycamera android.permission.POST_NOTIFICATIONS`, open the app, press Start server. Deny: status says not started and curl fails. Repeat and allow: server starts, ongoing notification appears with Stop and Open app, UI says running, curl returns the snapshot (or 404 before the first one).
3. Background: press Home. Notification stays and curl from the LAN works. Tap Open app in the notification: MainActivity opens.
4. Screen off: `adb -s "$TARGET" shell input keyevent KEYCODE_SLEEP`, wait several minutes and curl from another LAN device. Reachability is NOT guaranteed (Doze/network suspension); record the result. Capture is not expected to work while locked or screen-off.
5. Stop from the notification: server stops, notification disappears, curl is refused, reopening the app shows stopped. Stop from the UI button the same way. Repeat start/stop several times; start twice quickly; there must be one notification and one "Listening" log per start.
6. Restart: Start, Stop, Start again; the port must be reusable immediately.
7. Occupied port: start Termux `python scripts/serve-snapshot.py --snapshot snapshot.jpg` (or `nc -l 8080`), press Start server: UI shows a bind error, no notification remains, service not in dumpsys. Free the port and start again.
8. Process death: `adb -s "$TARGET" shell am kill local.termux.observatorycamera` (or kill the process); the server must stay stopped (no restart) and the UI must not show running.
9. Force-stop: `am force-stop` while running: notification and listener disappear; nothing restarts, including after reboot.
10. Orientation regression: with the server running, repeat the JPEG orientation checks below; capture still works from a resumed activity and the HTTP bytes are unchanged.

To stop the native server and camera process without the UI:

```sh
adb -s "$TARGET" shell am force-stop local.termux.observatorycamera
```

## Earlier helper and next work

scripts/serve-snapshot.py and docs/snapshot-http.md remain for optional isolated testing, not as the normal native serving path. They must use another port if run alongside the APK.

Next: verify native build and LAN access, add authentication, then MJPEG for Motion on Manjaro and an authenticated web settings page. Exposure and focus convergence still need validation; no preview or autofocus convergence routine is implemented. Current JPEG saving is on the main handler.

## JPEG orientation

The app sets Camera2 JPEG_ORIENTATION from the physical device angle (OrientationEventListener, rounded to 90 degrees) and the rear camera SENSOR_ORIENTATION: (sensor + device + 360) % 360, with the device angle negated for a front-facing sensor. Display rotation is not used. The last valid angle is kept across pause and activity recreation. With no valid reading yet (cold start) the app logs a fallback (device angle 0, sensor-only behavior); that is not measured orientation. The angle is snapshotted when the capture request is built, and logcat shows sensor, device, JPEG angle and fallback use.

With the overlay disabled, Camera2 JPEG bytes are saved unchanged (no bitmap rotation or re-encoding); the camera HAL may either rotate the pixels or set the EXIF orientation tag, so use a viewer that honors EXIF. With the overlay enabled the JPEG is decoded, the EXIF orientation is applied exactly once and the result is re-encoded without an EXIF block (implicitly upright; see below). Snapshots taken before this change must be recaptured. Orientation is ambiguous when the device lies flat (the sensor reports unknown), so the last valid angle is used.

Pure tests (no Gradle, no Android): `bash test/run.sh` covers orientation math and SnapshotHttpServer start/stop/restart/occupied-port/active-socket shutdown. SnapshotHttpService, the MainActivity controls and the manifest changes were not compiled against the Android API or run on a device in the authoring environment (no android.jar/aapt2/d8); run `bash build.sh` in Termux and the validation above.

Manual validation on the phone: capture and fetch the HTTP snapshot in a viewer honoring EXIF; it must appear upright in each case:
1. Portrait.
2. Landscape (rotated left) and landscape (rotated right).
3. Reverse portrait.
4. With screen auto-rotation locked, repeat the above; results must follow the physical device angle.
5. Cold start: force-stop, launch with --ez capture true while flat (without -S the process is not restarted; prefer the camera-control.sh capture command); logcat must say fallback=true. Tilt the phone upright, capture again; fallback=false.
6. Pause and return: rotate, background the app, resume, capture; the last angle is preserved until a new reading arrives.

Check with: `adb -s "$TARGET" logcat -d -v time -s ObservatoryCamera:I '*:S' | grep "JPEG orientation"`
