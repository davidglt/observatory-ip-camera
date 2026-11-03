# Observatory IP Camera

Native Android observatory camera project, developed and built on the phone in Termux.

## Status

Version 0.1.0 is a build bootstrap only. The APK displays a status screen. Camera capture, HTTP streaming, authentication and command-line control are not implemented yet. No device build or runtime testing has been performed for this initial version.

## Initial target

Samsung Galaxy S20 FE SM-G780G, Android 13 / API 33, One UI 5.1, Termux from Google Play and Samsung DeX. No root, Gradle, Android Studio, desktop computer or cloud build is required by the proposed pipeline.

## Build in Termux

Use the packages from your existing Termux distribution; do not mix package repositories.

```sh
pkg install git openjdk-21 aapt aapt2 d8 apksigner zip coreutils
```

An official Android SDK API 33 platform JAR must already be installed at `$HOME/.local/share/android-sdk/platforms/android-33/android.jar`. It is not installed by the commands above. Alternatively set `ANDROID_PLATFORM_JAR` to its path. Follow the platform provisioning documentation in the reference project: https://github.com/davidglt/wireless-debugging/blob/main/docs/android-platform-33.md

```sh
git clone https://github.com/davidglt/observatory-ip-camera.git
cd observatory-ip-camera
bash build.sh
```

The script compiles Java, produces DEX, packages, aligns, signs and verifies the APK. Tool availability does not guarantee a successful build on every Termux distribution. Build errors should be reported before proceeding.

## Signing

The application uses its own local signing identity under `$HOME/.local/share/observatory-ip-camera/signing`. Keep both identity.p12 and password private and back them up securely. Never commit them. Reuse the identity for updates. An incomplete identity causes the build to stop.

## Install and run

Use an existing authorized ADB connection. Replace TARGET with the actual verified device identifier; do not type TARGET literally. Local wireless ADB ports are dynamic. The reference wireless-debugging project documents discovery and authorization.

```sh
adb -s TARGET get-state
adb -s TARGET install -r dist/observatory-ip-camera.apk
```

Open Observatory IP Camera from the launcher. Installation does not grant camera permissions or prove streaming works. This bootstrap requests neither camera nor network access.

## Planned milestones

- Validate the on-device build and installation.
- Add Camera2 capture and runtime permissions.
- Add a camera foreground service with explicit user start/stop.
- Add authenticated JPEG snapshot and MJPEG streaming for local-network clients.
- Validate the stream with Motion on Manjaro.
- Add authenticated Termux control, bounded resource use and recovery tests.

Background startup, locked-screen operation and unattended recovery are not guaranteed. Do not expose the future server directly to the Internet.

Source, comments, logs and documentation are written in English.
