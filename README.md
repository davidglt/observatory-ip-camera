# Observatory IP Camera

Native Java Android camera app developed, compiled and signed in Termux. Source, comments, messages and documentation are in English.

## Status: 0.6.0 (telemetry; device and browser behavior not yet verified)

New in 0.6.0: device and optional BME280 environmental telemetry in a responsive viewer panel and independently configurable JPEG overlays. See "Telemetry" below.

Release versioning: increment `versionCode` for every new release and update `versionName` according to the scope of the change. Do not increment either value for every local rebuild.

New in 0.5.0: sustained Camera2 JPEG capture with a burned-in overlay, controlled only over authorized ADB (see "Continuous capture and overlay" below). Everything below about 0.4.0 still applies.

### 0.4.0 baseline

Camera2 single JPEG capture plus a native HTTP snapshot server owned by an explicit foreground service (SnapshotHttpService), bound explicitly to 0.0.0.0:8080 (all IPv4 interfaces). No Python server or manual export is required for native HTTP. GET and HEAD /snapshot.jpg read the latest private app snapshot. Requests do not trigger capture. No directory listing is implemented; see "MJPEG stream and viewer" for the stream added afterwards.

The owner validated the previous capture implementation at 1280x720, retrieved the 31671-byte JPEG and verified byte-identical HTTP transfer through the separate Python helper. The native HTTP integration has not yet been built or tested on the phone. MainActivity's existing status text still identifies the capture component as 0.2.0; APK package metadata is 0.4.0 and native HTTP logs use SnapshotHttp.

## Security and lifetime

WARNING: While the server is running, the read-only viewer, snapshot and MJPEG routes are unauthenticated and HTTP is unencrypted. The Settings/configuration/capability APIs require the app-private admin bearer token, but HTTP sends that token in plaintext to network observers. Use only a trusted private network; this is trusted-LAN-only, not Internet-safe or TLS. Do not configure router port forwarding or expose it to the Internet. Binding to 0.0.0.0 is not IPv6 support, Internet publication or a guarantee that Android/network policy permits remote access. Use the actual phone address in the browser, not 0.0.0.0.

The server no longer starts with the application process. It runs only in SnapshotHttpService, a foreground service started by pressing Start server in a visible, resumed MainActivity (or by the shell-protected non-touch ControlActivity, see below), or stopped by Stop server or the Stop action of its ongoing notification (which also offers Open app). Start and Stop are idempotent; there is never more than one listener. Stop closes the listening socket and any active client socket and ends the worker thread. The UI shows "running" only after the port was bound successfully; a bind failure (for example port 8080 occupied) or a start failure is shown as an error and the service stops itself.

Notification permission: on Android 13 (API 33) the app asks for POST_NOTIFICATIONS when you press Start server. If it is denied (or notifications/the channel are blocked), the server is not started, because a running server must stay visible and stoppable.

Manifest: FOREGROUND_SERVICE and POST_NOTIFICATIONS permissions; a non-exported service without a foregroundServiceType. targetSdk 33 does not require a type, and this service only serves HTTP: it is not a camera service and has no background camera access (no FOREGROUND_SERVICE_CAMERA). If the target is raised to API 34 or later, a type (likely specialUse with its permission and justification property) must be chosen and declared then.

Restart semantics: START_NOT_STICKY. If Android kills the process the server stays stopped until you press Start server again; there is no boot receiver, no automatic restart and no resurrection after Stop. Swiping the app from recents does not by itself stop the service.

A foreground service is not a guarantee. Android may still kill the process, and Doze, battery policy, Wi-Fi power saving or network suspension can make the server unreachable, especially with the screen off. No wake lock, Wi-Fi lock or battery-optimization bypass is used. It does not bypass lock-screen or camera restrictions: capture still needs a visible, resumed, focused activity and runs only on demand; the service never uses the camera. Force-stopping the app stops the service and server. The debuggable APK is for development only.

Connections are handled concurrently with explicit limits (see "MJPEG stream and viewer"). Headers are limited to 8192 bytes and each complete request has a five-second deadline; API request bodies are limited to 4096 bytes. Host/origin checks accept numeric IPv4 authorities only (including `127.0.0.1` for SSH forwarding; DNS names are rejected); API mutations require `PUT`, matching same-origin `Origin`, and bearer auth. No permissive CORS, cookies, or GET mutations are used. Snapshot JPEG size is limited to 16 MiB. Camera writes publish the image using the existing temporary-file rename. The server reads an opened snapshot file and never exposes arbitrary paths. No image bytes or admin tokens are logged. Old snapshots survive restarts and failed capture attempts.

## Non-touch control over SSH (screen broken / no touch)

All existing features can be driven without touching the phone, from Termux over SSH via an already authorized adb connection (this document does not cover initial adb pairing, which needs authorization on the device). Use scripts/camera-control.sh. It always requires an explicit device (`-s TARGET` or the TARGET environment variable; the exact serial or host:port from `adb devices`), never auto-selects among several devices, has no hardcoded adb port, and checks that the device state is `device` and the app is installed.

Prepare `TARGET` before running camera commands. For explicit wireless-debugging recovery, source the target helper and request both refresh and activation once; camera-control commands themselves never enable wireless debugging:

```sh
source scripts/adb-target.sh
adbt_export --refresh --wireless-on
```

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

Security of the control entry: ControlActivity is exported but declared with `android:permission="android.permission.DUMP"`, a signature/development permission that the adb shell (and root) hold and that the platform enforces when an activity is started; ordinary apps do not hold it and are rejected. This does not make the entry fully private: code in the same UID, root, and any app that was explicitly granted DUMP (for example via `adb shell pm grant`) can still invoke it, and it is the platform permission check, not the explicit intent or request_id extra (a plain log correlation token), that restricts callers. The token show/rotate commands are explicit operations through this protected control entry; the script reads the app-private file with `run-as` only after a successful control response. The token value is never returned through an intent or log. The launcher MainActivity must remain exported, and its pre-existing `capture` extra is therefore callable by any local app; it can only trigger a single capture from a visible activity, but it is not caller-authenticated. The service and receivers are not exported. The DUMP enforcement is expected platform behavior but was not verified on a device here.

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

Validation status: the automated Java and fake-ADB suites passed, and `bash build.sh` compiled and signed the APK against Android API 34 with the pinned MQTT dependencies. The APK was not installed. Phone-specific battery/thermal behavior, camera capture, actual MQTT broker interoperability and browser rendering have not been verified on a device.

## Continuous capture and overlay

Sustained JPEG capture runs in ContinuousCaptureService, started and stopped only over SSH through the same shell-protected ControlActivity entry and scripts/camera-control.sh (explicit TARGET required, same whitelisting, no display or touch needed). Single capture and the independent HTTP server keep working as before.

```sh
bash scripts/camera-control.sh -s "$TARGET" setup-permissions       # CAMERA + POST_NOTIFICATIONS, only when you choose
bash scripts/camera-control.sh -s "$TARGET" config-show
bash scripts/camera-control.sh -s "$TARGET" config-set overlay=on location=NYX position=bottom-left text_size=24 fields=location,datetime,fps jpeg_size=auto target_fps=1 'text_color=#00FF00' telemetry_position=right
bash scripts/camera-control.sh -s "$TARGET" admin-token-show
bash scripts/camera-control.sh -s "$TARGET" admin-token-show       # explicitly provision/retrieve token for the browser
bash scripts/camera-control.sh -s "$TARGET" admin-token-rotate    # invalidate old token and print a new one
bash scripts/camera-control.sh -s "$TARGET" continuous-start
bash scripts/camera-control.sh -s "$TARGET" continuous-status
bash scripts/camera-control.sh -s "$TARGET" continuous-stop
```
Exit codes add 7 = camera busy (another owner holds the camera).

Camera ownership: exactly one owner at a time (CameraArbiter): the single-capture activity or the continuous service. A conflicting request is rejected (log reason `camera-busy owner=...`, script exit 7), never coordinated by opening a second camera. Stop continuous capture before running `capture`. HTTP is independent: it only reads the published file, and starting/stopping it never touches the camera.

Android rules respected: the service is declared `android:foregroundServiceType="camera"` and entered with `startForeground(..., FOREGROUND_SERVICE_TYPE_CAMERA)`. At targetSdk 33 the manifest needs FOREGROUND_SERVICE (already declared) and the runtime CAMERA permission; `FOREGROUND_SERVICE_CAMERA` only exists from API 34 and is deliberately not declared (add it, and re-check, if the target SDK is raised). Android 11+ only grants while-in-use camera access to a foreground service started while the app is visible, so `continuous-start` launches ControlActivity, requires it to be resumed and keeps it open until the service reports running or an error; if the screen is off/locked the start is deferred and times out. Missing CAMERA/POST_NOTIFICATIONS are reported as errors (exit 11) without dialogs. Nothing bypasses camera, background or lock-screen restrictions; Samsung/Android may still disconnect the camera (screen off, privacy toggle, another app), which ends the session in state `error`. START_NOT_STICKY, no boot receiver, no automatic restart, no wake lock, no battery-optimization bypass.

Pipeline: one camera device, one capture session and one ImageReader (maxImages 2) per run. One capture is in flight at a time; the next is issued only after the previous frame was processed and published (or failed), after waiting for the target interval measured on a monotonic clock. Memory is bounded (one JPEG buffer and at most three bitmaps of one frame). Camera callbacks run on one thread, decode/overlay/file I/O on another; the UI thread does none of it (single capture was moved off the main thread too). A 10 s watchdog and 5 consecutive failures end the session with state `error`. Stop and all error paths cancel pending work and close the reader, session, device, both threads and the orientation listener; start/stop can be repeated.

Rate: the default requested target is 1 FPS. `target_fps` accepts positive rates with at most one decimal digit, from 0.1 through 922337203685477580.7; the upper bound is the largest value representable as signed 64-bit tenths. Camera2 minimum-frame and JPEG-stall timing metadata is reported as an informational estimate only and never rejects a requested target. A zero/unknown minimum frame duration remains `unknown`; a reported zero JPEG stall duration means no additional stall in that metadata. The estimate excludes processing/publication costs and is not guaranteed sustainable throughput. Captures are serialized: the next capture starts only after the previous frame is processed and published (or fails), so slow hardware or processing produces fewer actual FPS without overlapping captures or catch-up queues/bursts. `continuous-status` and the web Settings panel distinguish the requested target, effective target, metadata estimate and actual FPS. Check actual FPS and `late` cycles on the device. Nanosecond timing arithmetic bottoms out at a 1 ns interval, and Android handler delays have millisecond resolution; these are representation/scheduler bounds, not operational FPS caps. `continuous-status` also reports processing time (last/avg/max ms, bytes, overlay mode), state, frames (successfully published), last_capture/tz and monotonic age. The frame time is when the JPEG is received, so it can trail exposure by camera latency; it is never HTTP retrieval time.

Overlay (default on, burned into the JPEG pixels so snapshot clients and any later MJPEG consumer see it): bottom-left, bold text with configurable `#RRGGBB` color (or legacy `green`) and a black outline and shadow, margins of 3% of the short side (min 8 px). Missing or legacy `text_color=green` keeps the original green appearance. RGB values accept either case and are persisted in uppercase.

```
NYX
2026-11-03 18:00:00 CET
FPS: 0.9 / 1.0
```
NYX is a configurable text label, not GPS; no location permission is used. FPS shows measured actual capture/publication rate followed by the configured target; actual FPS shows `--` until it can be measured. Single capture uses the same overlay with `FPS: -- / 1.0`. The visible-field selection controls Location, Date/time, and FPS; frame counts remain internal diagnostics and are not drawn.

The web viewer has an independent framed telemetry panel outside the image: `telemetry_position=left|right` selects its side on wide screens (default right), and on narrow screens it is always below the image. Settings provide editable RGB hex text and synchronized color pickers for the overlay text, panel text, and panel background independently. The panel defaults to a black background/red text and has independently selectable Environment, Device and Capture groups. Its status polling is read-only and does not request captures or reload the image. `/api/telemetry` is public and bounded like the existing public viewer/snapshot routes; it returns display telemetry and panel appearance only, never broker settings or credentials. The existing config/capability APIs remain bearer-token authenticated.

The camera.properties keys `text_color`, `panel_text_color`, `panel_background_color`, and `telemetry_position` use the same validated atomic save/import path as the other settings. For shell commands, quote RGB assignments to keep `#` in the value, for example: `config-set 'text_color=#12ABCD' 'panel_text_color=#FF0000' 'panel_background_color=#000000' telemetry_position=left`. Web Settings, SSH, import/export and reload all share this persisted configuration.

Each overlay field has its own checkbox. New overlay telemetry fields (environmental Temperature, Humidity and Pressure, Battery level/temperature, Charging status and Thermal status) default off, leaving the previously visible Location/Date-time/FPS unchanged for existing files. Panel group selections do not change overlay selections. Environmental values are burned into JPEG pixels only when the source timestamp is fresh; unavailable or stale values are labeled `--`. Thermal status is an Android severity (`none` through `shutdown`), not a CPU temperature. Processing times and server diagnostics are panel-only. All overlay fields may be unchecked and persist as `fields=none`.

The optional MQTT subscriber is disabled while `mqtt_host` is empty. Configure broker host, port, topic, TLS and stale threshold in `camera.properties` or Settings; the topic defaults to `homeassistant/nyx/reading`, and the default stale threshold is 120 seconds. Messages are bounded JSON from the existing BME280 producer and must contain finite numeric `timestamp_epoch`, `temperature_c`, `humidity_pct` and `pressure_hpa` within -40..85 C, 0..100 percent and 300..1100 hPa. Altitude is ignored. Age is based on the source timestamp, including retained messages; future timestamps more than 60 seconds ahead are rejected. MQTT reports disabled/connecting/disconnected/waiting/fresh/stale/error states. One client with a stable installation-specific ID distinct from `bme280-nyx` is started only with the explicitly started HTTP service and is stopped with it; it does not start the camera or an Android background service. TLS uses the system trust store and hostname validation.

Settings are stored in the human-readable UTF-8 file `files/camera.properties` (java.util.Properties format, read and written with explicit UTF-8 Reader/Writer) in app-private storage. It is the single authoritative store: `config-show`, `config-set` and `config-reset` read and update this same file. A commented example with the full schema and defaults is committed as `config/camera.properties.example`; the live device file is never committed (`camera.properties` is git-ignored).

Schema (every key optional; missing keys use the default; unknown keys or invalid values are rejected): existing capture and overlay keys remain as documented above. `panel_enabled`, `panel_environment`, `panel_device` and `panel_capture` are independent `on|off` choices; panel colors are six-digit hex and panel text size is 10-48 px. `mqtt_host` defaults empty (disabled), `mqtt_port` defaults to 1883, `mqtt_topic` defaults to `homeassistant/nyx/reading`, `mqtt_tls` defaults off and `mqtt_stale_seconds` is 10-3600 (default 120). A blank host disables MQTT. The file remains at most 8192 bytes. Shell, import and web writes validate the full candidate atomically.

If authentication is required, credentials are read only from the separate app-private `files/mqtt-credentials.properties` file (with `username` and `password` keys; max 2 KiB). Do not put credentials in `camera.properties`. This file is deliberately excluded from `config-export`; `config-import` neither reads nor overwrites it. Back it up separately only in an encrypted, access-controlled location, and never paste credentials into browser settings, URLs, terminal history, logs or support reports. No API, HTML or settings response includes broker credentials.

To provision credentials, create the properties file in a protected host location and stream it to the app-private file over the explicitly authorized ADB connection (the contents are sent on stdin, not included in the command line):

```sh
chmod 600 /secure/path/mqtt-credentials.properties
adb -s "$TARGET" shell run-as local.termux.observatorycamera sh -c 'cat > files/mqtt-credentials.properties' \
  < /secure/path/mqtt-credentials.properties
```

Export/import over authorized ADB, no touch UI:
```sh
bash scripts/camera-control.sh -s "$TARGET" config-export --out camera.properties   # writes the normalized live file first, then copies it
# edit camera.properties on the host (UTF-8), then:
bash scripts/camera-control.sh -s "$TARGET" config-import --in camera.properties
```
Import stages the file in the app-private `files/camera.import`, and the app validates the entire file before replacing anything. On any problem (unknown key, bad value, invalid UTF-8, over 8192 bytes) the previous valid configuration is kept, the staging file is deleted, and the script exits 2 with the reason. MQTT credentials are separate and unchanged by import/export.

Apply/reload semantics: the file is re-read on each capture cycle and overlay. Overlay/FPS changes apply from the next cycle. A JPEG size change is applied between completed sequential frames by closing the current session/reader and configuring a new session/reader on the same camera device and existing camera owner; it never opens a second camera or restarts the service. Buffers remain bounded (`ImageReader` maxImages 2); if new-session configuration fails, the service safely closes resources and reports `state=error`, desired/effective size and the failure. It does not silently claim the requested size is active. Hand edits are not a supported write path: syntactically corrupt files use the last valid settings and are reported by `config-show`; a syntactically valid but unsupported size/FPS is rejected at runtime while the last active configuration continues, with a reload error. A missing file means defaults (`file=missing`; reading does not create it).

Durability: all updates are serialized in-process and written as a complete temporary file, synced and atomically renamed over the live file, so concurrent SSH/web updates cannot truncate one another; a failed or unsupported update keeps the previous file. The file lives in the app's files directory, so it survives service restarts and app updates (`adb install -r`); uninstalling or `pm clear` removes it. `jpeg_size=auto` preserves the old nearest-to-1280x720-area selection; explicit values are exact advertised JPEG sizes only.

Orientation and EXIF: JPEG_ORIENTATION is requested per capture from the physical device angle as before. With the overlay on, the EXIF orientation tag of the camera JPEG is read and applied once (rotate/mirror), the text is drawn on the upright pixels and the re-encoded JPEG has no EXIF orientation, so viewers cannot rotate it a second time. If a HAL rotates pixels and leaves the tag at normal, nothing more is applied. If the HAL ignores JPEG_ORIENTATION and writes no tag, the image cannot be corrected by the app. If the orientation sensor delivers nothing in the background the fallback angle 0 is used.

Behavior change: previous versions saved the camera JPEG bytes unchanged. With the overlay on, every frame is decoded, drawn on and re-encoded (quality 90), which costs CPU/time and one lossy generation; with `overlay=off` the camera bytes are saved unchanged (no decode, no re-encode, camera EXIF kept) and `overlay_mode=passthrough` is reported. Processing cost is measured and reported (`proc_*_ms`), not promised; sustained 2 FPS may not be reached on this device.

Publication: each frame is written to a temporary file, synced and renamed over `files/snapshot.jpg` (private, atomic; the temp name is never published), only if the session is still current, so HTTP clients always get a complete file and a stopped session never publishes a late frame. Overlay text is rendered once per capture; an old file is never re-stamped. The Settings button is HTML alongside the viewer, never part of the JPEG pixels.

### Continuous capture SSH-only checklist

Set TARGET; run every step with the phone screen possibly off/unconnected to any display. Record fresh output each time.
1. `setup-permissions`, `config-reset`, `config-show` (defaults: overlay=on location=NYX position=bottom-left text_size=24 jpeg_size=auto target_fps=1.0).
2. Fresh frames: `continuous-start` (exit 0), then `continuous-status` three times 5 s apart: `frames` must increase, `age_ms` stay below about 2x the interval, `last_capture` advance, actual FPS approach the target when the device can keep up. `fetch --out a.jpg`, wait a few seconds, `fetch --out b.jpg`: the files must differ and show different times in the overlay.
3. Measured rates: run with `target_fps=1`, then `config-set target_fps=10` (applies without restart); compare `actual_fps`, `late`, `proc_avg_ms`; record actual throughput.
4. Stop/restart: `continuous-stop` (exit 0, state stopped, camera released: `dumpsys media.camera` shows no client), `continuous-stop` again (idempotent), `continuous-start`; repeat 10 times; `frames` restarts from 0 each session.
5. HTTP coexistence: `start` the HTTP server, then `continuous-start`; `curl` the forwarded port repeatedly: always complete JPEGs with changing time stamps; stop each independently; stopping HTTP leaves capture running and vice versa.
6. Single-capture conflict: while continuous runs, `capture` must exit 7 (camera-busy) and continuous must keep running; after `continuous-stop`, `capture` works and a following `continuous-start` works. `continuous-start` during a running single capture must be rejected (exit 7) or succeed after it.
7. Repeated commands: `continuous-start` twice (second reports already=true, one notification), `config-set` twice, `continuous-status` while starting.
8. Activity/background: press Home (`input keyevent KEYCODE_HOME`) while running: capture should continue (foreground service) - record result; also `am force-stop` stops it and nothing restarts.
9. Screen-off/lock: `input keyevent KEYCODE_SLEEP` during a run: record whether `frames` keeps increasing or the state becomes `error`; `continuous-start` with the screen off must time out (deferred) rather than start. No bypass is attempted.
10. Orientation/overlay: for portrait, landscape left, landscape right and reverse portrait (and with auto-rotation locked), set the phone and run `fetch`; scene and text must both be upright with the text in the bottom-left of the upright image; check `position=` variants and `overlay=off` (scene upright via EXIF in a viewer honoring EXIF, no text, `overlay_mode=passthrough`).
11. Persistence/import: `config-set overlay=off location=TEST target_fps=2 text_size=40`, `am force-stop`, `config-show` must still show the values; `config-export --out c.properties`, edit it (include a UTF-8 location such as an accented name), `config-import --in c.properties` and confirm the next frame uses it; import a file with an unknown key or `text_size=500` and confirm exit 2 and an unchanged `config-show`; `adb install -r` the APK and confirm settings persist; corrupt the file with `run-as` and confirm `file=corrupt` in `config-show` and that capture continues; `config-reset`.
12. Denied permissions: `pm revoke ... CAMERA` then `continuous-start` -> exit 11 with no dialog; also POST_NOTIFICATIONS. Re-grant and retry.
13. Errors: `config-set text_size=5`, `target_fps=0`, `target_fps=1.25`, `fields=gps`, `position=middle`, `location='a b'`, `overlay=on;id` must be rejected (exit 2); verify `target_fps=15.5` and rates above 9 are accepted regardless of lower metadata estimates. Confirm failed updates leave `config-show` unchanged. Open another camera app during a run: record state/error and that `continuous-stop` still cleans up.
14. Sustained run: 1-2 hours; sample `continuous-status` and `dumpsys meminfo local.termux.observatorycamera`, `dumpsys battery` temperature and `dumpsys thermalservice` every 10 minutes: frames increase, no growing memory, processing time stable, storage not growing (one snapshot file). Record thermal throttling.

Validation status of this section: `bash test/run.sh` passed, including telemetry parsing, conversion, settings persistence, overlay selection, HTTP authorization and shell tests against fake adb. The APK build and signature verification passed against Android API 34; no device installation or deployment was performed. Camera2 behavior, target API 33 build, Android foreground rules, JPEG session reconfiguration, real MQTT broker behavior, browser layout, sustained FPS and the manual checklist remain unverified. No real-device security validation is claimed.

## Build in Termux

Target: Galaxy S20 FE SM-G780G, Android 13/API 33, One UI 5.1, Termux from Google Play. No root, Gradle, Android Studio, proot or cloud build. Use packages from your existing Termux distribution.

```sh
pkg install git openjdk-21 aapt aapt2 d8 apksigner zip coreutils curl
```

Provision the official API 33 android.jar at $HOME/.local/share/android-sdk/platforms/android-33/android.jar or set ANDROID_PLATFORM_JAR. Guide: https://github.com/davidglt/wireless-debugging/blob/main/docs/android-platform-33.md. The build downloads HiveMQ MQTT Client 1.4.2 and its pinned runtime dependencies from Maven Central into the user cache, verifies every SHA-256 digest, and packages them into the APK. No broker configuration or credentials are bundled.

```sh
git pull --ff-only && bash build.sh
```

Keep the signing identity and password under $HOME/.local/share/observatory-ip-camera/signing private, backed up and unchanged for updates. Never commit keys, credentials or camera images.

## Automated deploy over SSH (Termux)

Two helper scripts automate target resolution and deployment. They work from any directory and never install packages, pair adb, uninstall, clear data, downgrade, grant permissions or start app services. They do not change device settings by default; wireless-debugging activation is an explicit opt-in and is never performed by camera-control commands.

Target helper, `scripts/adb-target.sh`: precedence is `-s/--target SERIAL`, then the existing `TARGET`, then the single authorized device in `adb devices -l`. Each candidate must report `device` from `adb -s X get-state`; identity and model go to stderr, the serial alone to stdout. Ambiguous, unauthorized, offline or missing targets are rejected; an explicit or existing target that fails is never silently replaced. `--refresh` ignores a stale `TARGET` and re-resolves (with a notice if it changes). `--mdns` is opt-in discovery/reconnection only: it first uses `adb mdns services`; only if that command explicitly reports mDNS unsupported does it use the external Python helper `scripts/adb-mdns-discover.py` with the `zeroconf` package. Discovery is limited to local IPv4 `_adb-tls-connect._tcp.local.` services and validated TCP ports. mDNS advertisements are untrusted discovery, not proof of pairing or device identity; the helper connects only to one unambiguous endpoint, then requires `adb get-state=device` and reports the connected device identity. It never pairs, scans arbitrary ports, selects a service by order, or connects to a non-local advertised address. A failing native mDNS backend, missing Python dependency, timeout or ambiguous result is reported rather than silently treated as no services. Configure the interpreter with `ADB_DISCOVERY_PYTHON`; the default is `$HOME/.venvs/adb-discovery/bin/python`. Install `zeroconf` into that environment yourself; dependencies are never installed automatically. The total zeroconf discovery deadline defaults to 15 seconds and can be set with `ADB_MDNS_TIMEOUT` (1-60 seconds).

`--wireless-on` remains a separate explicit opt-in: if no usable device is found, it runs the installed `wireless-debugging on` wrapper with a bounded timeout, then re-reads `adb devices` and validates the selected device. That wrapper changes wireless-debugging only on the local Android phone where it is installed; it cannot activate debugging on an mDNS-discovered remote target. Its human-readable output is not parsed. With both `--mdns` and `--wireless-on`, discovery/reconnection is tried first; only a no-services result permits local activation. A backend failure, timeout, invalid service or ambiguous discovery stops without enabling wireless debugging. Explicit `-s` always takes precedence, and an existing `TARGET` still requires `--refresh` before re-resolution. The wireless activation timeout defaults to 45 seconds and can be set with `ADBT_WIRELESS_ON_TIMEOUT` (1-300 seconds). Neither option pairs, reads/logs tokens, deletes keys, restarts the adb server, reboots, or installs packages.

```sh
# A child process cannot export into its parent. Either source the helper (it only defines functions,
# never exits or changes options/traps/cwd; on failure it unsets TARGET so nothing stale remains):
source scripts/adb-target.sh; adbt_export            # or: adbt_export --refresh / -s SERIAL / --mdns
# Explicit recovery when no device is usable; --refresh is needed to accept a changed wireless serial:
source scripts/adb-target.sh; adbt_export --refresh --wireless-on
# or use the serial mode with failure-safe assignment:
if T=$(bash scripts/adb-target.sh); then export TARGET="$T"; else unset TARGET; fi
```

Deploy, `scripts/deploy.sh`: default runs `test/run.sh`, then `build.sh` (existing build and signing logic), then installs only the newly built `dist/observatory-ip-camera.apk` with `adb -s TARGET install -r` and verifies the installed package version against the APK metadata (`aapt2 dump badging`). The `package:` line is parsed by exact field name (`name`, `versionCode`, `versionName`, in any order, ignoring look-alike fields such as `compileSdkVersionCodename`); aapt2 failure or missing, empty, duplicate or ambiguous metadata fails with exit 25. Exit codes are phase specific: 20 prerequisites, 21 git, 22 signing identity, 23 tests, 24 build, 25 APK signature/metadata, 26 target, 27 install, 28 verification, 29 another run in progress, 30 stale artifact. The old APK is deleted before building, so a failed build can never lead to an install of an old artifact.

```sh
bash scripts/deploy.sh                       # tests, build, install, verify (target: -s, TARGET or the single device)
bash scripts/deploy.sh -s "$TARGET"          # explicit target
bash scripts/deploy.sh --test-only
bash scripts/deploy.sh --build-only          # no device needed
bash scripts/deploy.sh --install-only        # only an artifact built by deploy.sh for the current commit/diff
bash scripts/deploy.sh --git-update          # opt-in: fetch origin main + merge --ff-only
bash scripts/deploy.sh --git-update --refresh-target --wireless-on  # explicitly recover before resolving/installing
bash scripts/deploy.sh --refresh-target --mdns  # discover/reconnect an existing local mDNS target
```

Signing: if the app is already installed on the target but no signing identity exists, the deploy stops with an actionable error instead of letting build.sh create a replacement key; an incomplete identity is also refused. Private signing data is never logged. `--install-only` limitation: the stamp fingerprints the commit, the tracked diff and the APK hash; untracked files are not covered. `--git-update` requires a clean tree on `main` and an `origin` matching `davidglt/observatory-ip-camera` (override with `DEPLOY_REMOTE_PATTERN`); it never resets, stashes or checks out. Without it the local checkout is built and the commit plus dirty status are reported. Logs are private, size-bounded, timestamped files in `~/.local/state/observatory-ip-camera/logs` (outside the source tree, last 20 kept); a run summary lists commit, target, APK version, result and log path. Concurrent runs are refused with a lock.

`scripts/camera-control.sh` continues to require an explicit `-s TARGET` or a previously exported `TARGET`; it never activates wireless debugging or resolves a target itself. Prepare and recover the target beforehand with the sourced helper above, then run camera commands normally.

Validation status: both scripts are tested against a fake adb and fake build/test scripts (`bash test/adb-target-test.sh`, `bash test/deploy-test.sh`, also run by `bash test/run.sh`). A real phone, the real Termux build tools and a real install were NOT exercised.

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

Next: verify the native build, token-protected Settings page, safe resolution reload and actual camera throughput on a device. Exposure and focus convergence still need validation; no preview or autofocus convergence routine is implemented.

## MJPEG stream and viewer

Read-only endpoints remain unauthenticated, and all HTTP traffic (including the admin bearer token) is unencrypted. This is trusted-LAN-only, not Internet-safe or TLS; never expose it to the Internet.

- `/snapshot.jpg`: unchanged latest complete snapshot.
- `/stream.mjpg`: `multipart/x-mixed-replace; boundary=observatoryframe`. Each part is `--observatoryframe`, CRLF, `Content-Type: image/jpeg`, CRLF, `Content-Length: N`, CRLF, CRLF, N JPEG bytes, CRLF. Responses carry `Cache-Control: no-store, no-cache, must-revalidate, max-age=0` and `Pragma: no-cache`.
- `/`: self-contained viewer with a Settings button outside the image. Settings uses an in-memory token and sends it only in an `Authorization: Bearer` header; the page never puts it in a URL or persistent browser storage. Reloading/closing the page forgets the token.
- `GET /api/telemetry`: public, read-only, bounded device/environment/capture display status and panel appearance. No broker host or credentials are returned.
- `GET /api/config`: authenticated current settings and desired/effective resolution plus requested/effective target FPS, actual FPS and metadata estimate.
- `GET /api/capabilities`: authenticated list of every JPEG output size from the selected rear Camera2 camera, with per-size minimum-frame duration, JPEG stall duration, informational metadata estimate and whether it is within the app's 8-megapixel resource bound.
- `PUT /api/config`: authenticated `application/x-www-form-urlencoded` atomic update. Unsupported sizes, invalid FPS syntax/value, invalid fields or bad encoding reject the whole update; existing settings remain unchanged.

The server only reads the atomically published private snapshot (the burned-in overlay is already in it). HTTP never opens the camera, starts capture or creates per-client camera sessions. Read-only viewer/snapshot/stream and telemetry access remains unauthenticated; configuration/capability APIs require the admin bearer token. No cookie auth, permissive CORS or GET mutation is used. Requests require a numeric IPv4 Host authority; mutations additionally require a matching same-origin `Origin`, rejecting DNS hostnames and cross-origin requests. Headers are limited to 8192 bytes and the complete request to 5 s; API bodies to 4096 bytes. No token or broker credential is logged or embedded in HTML.

The app generates a cryptographically random 256-bit token in app-private storage only when explicitly asked through the DUMP-protected SSH/ADB control command. The first call provisions it if absent; subsequent calls retrieve the same value. Rotation replaces it so the old token immediately fails:

```sh
bash scripts/camera-control.sh -s "$TARGET" admin-token-show
bash scripts/camera-control.sh -s "$TARGET" admin-token-rotate
```

Treat terminal output as a password: enter it in the viewer's Settings panel, never paste it into a URL, shell history, issue, screenshot or log. The browser keeps it only in page memory; use “Forget token” or reload/close the page to clear it. If the file is absent/corrupt, HTTP admin APIs fail closed until explicit local provisioning/rotation. There is no token reset endpoint. Plain HTTP exposes the token and all camera images/configuration to network observers. The bearer token prevents unauthenticated settings changes, not eavesdropping or image access. This remains trusted-LAN-only, not Internet-safe and not TLS. Do not port-forward it; same-origin checks are not encryption.

Stream semantics:

- Before the first frame: headers are sent immediately; the client receives nothing until a publication newer than its connection time appears. The snapshot that already existed at connect time is not replayed as a live frame.
- A frame is sent only when the snapshot file was republished (new file identity) since the previous frame. Unchanged snapshots are never repeated, so a viewer cannot mistake a stale image for live video.
- Capture stopped, failed or slower than 60 s: after 60 s without a new publication the server sends the closing boundary `--observatoryframe--` and closes the connection. Reload the page or reconnect to resume. A stopped capture therefore shows no further frames, never repeated ones.
- Client disconnect: detected within about 200 ms (or on write); its thread, socket and stream slot are released.
- HTTP service stop (or restart): the listener and every client socket are closed, all server threads are joined for at most about 2 s and a later Start begins with fresh state. Nothing restarts automatically.

Resource limits (constants in SnapshotHttpServer and CameraCapabilities): at most 8 simultaneous connections (one thread each, extra connections get 503 and are closed); at most 2 of them may be streams (further streams get 503), so streams cannot occupy all slots and snapshot requests keep being served. Request headers are limited to 8192 bytes; header plus API body has a 5 s total deadline. API bodies are at most 4096 bytes; `camera.properties` is at most 8192 bytes; accepted MQTT JSON payloads are at most 4096 bytes. A write that does not complete within 10 s (slow or stalled reader) closes that connection. Memory is bounded: each client holds at most one frame (stream frames up to 8 MiB, snapshots up to 16 MiB, larger images are not streamed or served with 503), no queues, frame history or extra files are kept. Camera capture itself is sequential with one in-flight request, JPEG output is limited to 8 megapixels and 16 MiB, `ImageReader` maxImages 2 and at most three frame bitmaps; metadata FPS estimates are informational, not capture limits. No sustainable device FPS is promised. Larger advertised JPEG sizes remain visible in capability metadata but cannot be selected by this app.

Automated tests (`bash test/run.sh`, pure Java, no Android/camera/browser): multipart framing and JPEG decode, new-frame-only behavior, idle end, concurrent snapshot and stream clients, stream and connection limits, disconnects, a non-reading slow client, stop with open streams, repeated restart and thread cleanup. These tests do not validate phones, browsers, Motion or sustained performance.

SSH-only real-device/browser validation checklist (not yet performed; record results yourself; TARGET is your verified ADB target, PHONE_IP the phone LAN address):

1. Start the server with the existing control script; confirm `Listening on 0.0.0.0:8080; endpoints: /, /snapshot.jpg, /stream.mjpg` in logcat.
2. Start continuous capture; `curl -sS -D - -m 5 http://127.0.0.1:8080/stream.mjpg -o /tmp/s.bin` from an SSH session must show the multipart content type and no-cache headers.
3. `curl -sS -m 10 http://PHONE_IP:8080/stream.mjpg | head -c 200000 | strings | grep -c Content-Length` from another LAN machine: the count must grow with captured frames.
4. Open `http://PHONE_IP:8080/` in a desktop browser and check that the read-only stream works without a token. For SSH tunneling, open `http://127.0.0.1:18080/` after `ssh -p SSH_PORT -L 18080:127.0.0.1:8080 PHONE_IP`.
5. While streaming, repeat `curl -I http://PHONE_IP:8080/snapshot.jpg`; it must stay responsive. Open 3 streams: the third must get 503.
6. Stop capture: the stream must stop updating and end after about 60 s. Close a client: the next stream must connect.
7. Stop the HTTP service with the existing script: open streams must close at once and the port must be refused; start again and repeat step 2.
8. Check `adb -s "$TARGET" logcat -d -s SnapshotHttp:I` and memory (`dumpsys meminfo local.termux.observatorycamera`) after several minutes of streaming; sustained performance and Motion compatibility remain unvalidated until you test them.
9. Run `admin-token-show`; open Settings, enter the token and load settings/capabilities. Confirm the responsive form includes independent overlay and panel selections, only actual advertised JPEG outputs, metadata timings/estimate and distinct requested target/actual FPS. Save with all overlay fields unchecked and confirm none remain selected after save. Verify browser storage contains no token; Forget token and page reload must clear the entry.
10. Save a valid overlay/FPS update, including a rate above 9 and one above the metadata estimate. Try an unadvertised or over-8-megapixel `jpeg_size`; the update must fail and `config-show` must remain unchanged.
11. While continuous capture is active, change JPEG size. Confirm `desired_size` changes first, then `effective_size` only after a frame-boundary session reconfiguration; camera ownership remains continuous, no second camera is opened, and service is not restarted. Force an unsupported/session failure and confirm a clear error plus safe cleanup.
12. Run `admin-token-rotate`; the old token must fail and the newly printed token must work. Confirm the token never appears in URLs, logcat or HTML. Test mismatched Origin/Host, DNS-name Host, missing auth, oversized body and slow header/body rejection. These checks are not complete until performed on a device/browser.

## JPEG orientation

The app sets Camera2 JPEG_ORIENTATION from the physical device angle (OrientationEventListener, rounded to 90 degrees) and the rear camera SENSOR_ORIENTATION: (sensor + device + 360) % 360, with the device angle negated for a front-facing sensor. Display rotation is not used. The last valid angle is kept across pause and activity recreation. With no valid reading yet (cold start) the app logs a fallback (device angle 0, sensor-only behavior); that is not measured orientation. The angle is snapshotted when the capture request is built, and logcat shows sensor, device, JPEG angle and fallback use.

With the overlay disabled, Camera2 JPEG bytes are saved unchanged (no bitmap rotation or re-encoding); the camera HAL may either rotate the pixels or set the EXIF orientation tag, so use a viewer that honors EXIF. With the overlay enabled the JPEG is decoded, the EXIF orientation is applied exactly once and the result is re-encoded without an EXIF block (implicitly upright; see below). Snapshots taken before this change must be recaptured. Orientation is ambiguous when the device lies flat (the sensor reports unknown), so the last valid angle is used.

Pure tests (no Gradle, no Android): `bash test/run.sh` covers orientation math, telemetry validation/conversions, settings round trips, overlays and SnapshotHttpServer lifecycle/authentication. The full APK compiled and signed with the installed Android API 34 toolchain; it was not installed or exercised on a phone. Complete the device/browser checklist above in Termux.

Manual validation on the phone: capture and fetch the HTTP snapshot in a viewer honoring EXIF; it must appear upright in each case:
1. Portrait.
2. Landscape (rotated left) and landscape (rotated right).
3. Reverse portrait.
4. With screen auto-rotation locked, repeat the above; results must follow the physical device angle.
5. Cold start: force-stop, launch with --ez capture true while flat (without -S the process is not restarted; prefer the camera-control.sh capture command); logcat must say fallback=true. Tilt the phone upright, capture again; fallback=false.
6. Pause and return: rotate, background the app, resume, capture; the last angle is preserved until a new reading arrives.

Check with: `adb -s "$TARGET" logcat -d -v time -s ObservatoryCamera:I '*:S' | grep "JPEG orientation"`
