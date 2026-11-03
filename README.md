# Observatory IP Camera

Native Java Android camera app developed, compiled and signed in Termux. Source, comments, messages and documentation are in English.

## Status: 0.3.0

Camera2 single JPEG capture plus a native HTTP snapshot server, bound explicitly to 0.0.0.0:8080 (all IPv4 interfaces). No Python server or manual export is required for native HTTP. GET and HEAD /snapshot.jpg read the latest private app snapshot. Requests do not trigger capture. No directory listing, continuous video or MJPEG stream is implemented.

The owner validated the previous capture implementation at 1280x720, retrieved the 31671-byte JPEG and verified byte-identical HTTP transfer through the separate Python helper. The native HTTP integration has not yet been built or tested on the phone. MainActivity's existing status text still identifies the capture component as 0.2.0; APK package metadata is 0.3.0 and native HTTP logs use SnapshotHttp.

## Security and lifetime

WARNING: Native HTTP starts automatically when the application process starts and exposes the last private snapshot without authentication or TLS to any client that can reach port 8080. Use only a trusted private network. Do not configure router port forwarding or expose it to the Internet. Binding to 0.0.0.0 is not IPv6 support, Internet publication or a guarantee that Android/network policy permits remote access. Use the actual phone address in the browser, not 0.0.0.0.

The server runs on a worker thread owned by the application process, not a foreground service. It can outlive the activity but Android may kill or suspend the process. It does not wake the device, restart after reboot or keep the camera active. Stop it by force-stopping the app. Any later process launch starts it again. The debuggable APK is for development only.

Requests are sequential, header size is limited to 8192 bytes with a five-second read deadline, and JPEG size is limited to 16 MiB. Slow readers can still block this basic server; it is not production hardened. Camera writes publish the image using the existing temporary-file rename. The server reads an opened snapshot file and never exposes arbitrary paths. No image bytes are logged. Old snapshots survive restarts and failed capture attempts.

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

First stop the old Python server with Ctrl+C in its session. It uses the same port, even if bound only to loopback. Do not run both servers. Native bind failures are logged; the camera can still work even when HTTP failed to bind.

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
adb -s "$TARGET" shell input keyevent KEYCODE_WAKEUP && adb -s "$TARGET" shell am start -S -W -n local.termux.observatorycamera/.MainActivity --ez capture true
```
```sh
adb -s "$TARGET" logcat -d -v time -s ObservatoryCamera:I SnapshotHttp:I '*:S' | tail -n 30
```

Wait for current Listening on 0.0.0.0:8080 and Snapshot saved messages. Compare timestamps; old messages and files are not proof of fresh success.

```sh
curl --fail --silent --show-error -D - http://127.0.0.1:8080/snapshot.jpg -o snapshot-native.jpg
```

Expected server header: ObservatoryNative/0.3, not ObservatorySnapshot/0.1 (the old Python helper). From another reachable LAN device, open http://PHONE_IP:8080/snapshot.jpg using the actual phone IP. Test LAN access separately; loopback success does not prove it. The endpoint returns 404 before the first snapshot and 503 for an unreadable, oversized or incomplete image.

To stop the native server and camera process:

```sh
adb -s "$TARGET" shell am force-stop local.termux.observatorycamera
```

## Earlier helper and next work

scripts/serve-snapshot.py and docs/snapshot-http.md remain for optional isolated testing, not as the normal native serving path. They must use another port if run alongside the APK.

Next: verify native build and LAN access, add authentication and explicit server controls, then continuous capture, a properly permissioned foreground service and MJPEG for Motion on Manjaro. Exposure and focus convergence still need validation; no preview or autofocus convergence routine is implemented. Current JPEG saving is on the main handler.

## JPEG orientation

The app sets Camera2 JPEG_ORIENTATION from the physical device angle (OrientationEventListener, rounded to 90 degrees) and the rear camera SENSOR_ORIENTATION: (sensor + device + 360) % 360, with the device angle negated for a front-facing sensor. Display rotation is not used. The last valid angle is kept across pause and activity recreation. With no valid reading yet (cold start) the app logs a fallback (device angle 0, sensor-only behavior); that is not measured orientation. The angle is snapshotted when the capture request is built, and logcat shows sensor, device, JPEG angle and fallback use.

Camera2 JPEG bytes are saved unchanged (no bitmap rotation or re-encoding). The camera HAL may either rotate the pixels or set the EXIF orientation tag; use a viewer that honors EXIF. Snapshots taken before this change must be recaptured. Orientation is ambiguous when the device lies flat (the sensor reports unknown), so the last valid angle is used.

Calculation tests (no Gradle): `bash test/run.sh`. This project was not compiled against the Android API in the authoring environment; run `bash build.sh` in Termux.

Manual validation on the phone: capture and fetch the HTTP snapshot in a viewer honoring EXIF; it must appear upright in each case:
1. Portrait.
2. Landscape (rotated left) and landscape (rotated right).
3. Reverse portrait.
4. With screen auto-rotation locked, repeat the above; results must follow the physical device angle.
5. Cold start: force-stop, launch with --ez capture true while flat; logcat must say fallback=true. Tilt the phone upright, capture again; fallback=false.
6. Pause and return: rotate, background the app, resume, capture; the last angle is preserved until a new reading arrives.

Check with: `adb -s "$TARGET" logcat -d -v time -s ObservatoryCamera:I '*:S' | grep "JPEG orientation"`
