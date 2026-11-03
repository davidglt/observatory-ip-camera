# Observatory IP Camera

Native Java Android app developed, compiled and signed in Termux. All source, messages and documentation are in English.

## Status: 0.2.0

Camera2 single JPEG capture implementation. Rear camera selection, supported JPEG size nearest to 1280x720 by pixel count, runtime camera permission, 15-second timeout, resource cleanup on pause and private snapshot storage. No preview, autofocus convergence routine, continuous capture, foreground service, HTTP server or streaming yet. Orientation uses the sensor orientation; DeX rotation and framing require device validation. Image saving currently runs on the main handler and will move off it for streaming.

The 0.1.0 build, signature verification, installation and remote activity launch were confirmed by the owner on the Galaxy S20 FE. Version 0.2.0 has not been compiled or tested on the device yet.

## Target and tools

Samsung Galaxy S20 FE SM-G780G, Android 13 / API 33, One UI 5.1, Termux from Google Play. No root, Gradle, Android Studio, proot or cloud build.

```sh
pkg install git openjdk-21 aapt aapt2 d8 apksigner zip coreutils
```

Use your existing Termux repositories. Provision the official SDK API 33 android.jar separately at `$HOME/.local/share/android-sdk/platforms/android-33/android.jar`, or set ANDROID_PLATFORM_JAR. Reference: https://github.com/davidglt/wireless-debugging/blob/main/docs/android-platform-33.md

```sh
git pull --ff-only
bash build.sh
```

Signing identity is stored outside the repo at `$HOME/.local/share/observatory-ip-camera/signing`. Back up identity.p12 and password securely; never commit them or replace the identity for updates.

## Install and test over SSH

Use an authorized local ADB connection. Run `wireless-debugging on` if using the companion project, then enter the actual reported ADB target. Ports are dynamic.

```sh
read -r -p 'ADB target: ' TARGET
adb -s "$TARGET" get-state
adb -s "$TARGET" install -r dist/observatory-ip-camera.apk
```

Proceed only if get-state reports device and installation reports Success. CAMERA is now a declared runtime permission. Either grant it in the application, or deliberately grant it through your authorized ADB connection for this SSH test:

```sh
adb -s "$TARGET" shell pm grant local.termux.observatorycamera android.permission.CAMERA
adb -s "$TARGET" shell am start -S -W -n local.termux.observatorycamera/.MainActivity --ez capture true
adb -s "$TARGET" logcat -d -s ObservatoryCamera:I '*:S'
```

The activity must be visible and focused; a locked phone, camera privacy switch or another camera user may prevent capture. SSH/ADB does not guarantee unattended background camera access. If the focus check rejects the initial launch, retry the launch command after the display is available. The -S flag stops the app before this test, ensuring the capture extra reaches a fresh activity.

Look for Snapshot saved in logs. Logcat may contain earlier attempts: compare timestamps, and do not treat Status: ok or a PID as capture success. A previous snapshot can survive a failed capture.

This development APK is explicitly debuggable so authorized ADB can retrieve its private file with run-as:

```sh
adb -s "$TARGET" shell run-as local.termux.observatorycamera ls -l files/snapshot.jpg
adb -s "$TARGET" exec-out run-as local.termux.observatorycamera cat files/snapshot.jpg > snapshot.jpg
```

Run retrieval only after a successful fresh capture. Check the file is nonempty; failed redirection can leave an empty local file. Snapshot retrieval writes into the current Termux directory; protect and remove private images as needed. The debuggable flag is for development and must be removed for a production release. No camera image is logged or sent to the network by this version.

## Next milestones

Validate capture and quality on device, then add continuous capture, a properly permissioned camera foreground service, authenticated local-network streaming and authenticated Termux control. Motion on Manjaro will consume the future stream. No unattended restart or locked-screen guarantee is made. Do not expose a future camera server directly to the Internet.
