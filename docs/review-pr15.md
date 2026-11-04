# PR15 architecture and validation review

## Scope inspected

Reviewed the post-#14 source tree and the main application paths: `MainActivity` and `ControlActivity`; `SnapshotHttpServer`, `SnapshotHttpService`, and `SnapshotAdminApi`; `ContinuousCaptureService` and `CameraArbiter`; `CaptureConfig`, `ConfigStore`, and camera capability/request mapping; JPEG orientation, image transforms, and overlay rendering; telemetry assembly and formatting; MQTT subscription, credentials, and runtime lifecycle; shell control/deploy scripts; and the Java and shell test runners.

The app has a deliberately small, framework-free Java test surface alongside Android-dependent services. Capture owns the camera, while the HTTP server serves only the atomically published JPEG. The single-capture activity and continuous service share `CameraArbiter`; the continuous path serializes frame requests and cleans up camera, reader, threads, and orientation listener on stop/error. Viewer telemetry remains a public, read-only snapshot and does not request capture. Settings, capability discovery, and mutations use the authenticated admin API.

## Findings and disposition

| Severity | Finding | Disposition |
| --- | --- | --- |
| High (deployment risk) | HTTP binds to all IPv4 interfaces and is unencrypted. Images and public telemetry are readable without authentication; the admin bearer token and settings are exposed to network observers. | Existing design and documentation explicitly limit use to a trusted LAN and prohibit port forwarding. This PR does not introduce TLS or alter the network contract. A TLS/bind-scope design needs a separately reviewed proposal. |
| Medium (validation gap) | The host suite cannot prove Android Camera2 behavior, Samsung lifecycle behavior, real MQTT broker interoperability, or browser pixel/layout behavior. | Documented as unverified. New served-page assertions cover DOM construction and layout rules, not browser rendering; the device/browser checklist remains required. |
| Medium (development build) | `AndroidManifest.xml` marks the APK debuggable. This exposes development-only capabilities and must not be mistaken for a release-hardening setting. | Preserved as existing project behavior; keep APK distribution limited to the intended development/test workflow until a release-build policy is approved. |
| Medium (credential storage) | Optional MQTT credentials are excluded from browser settings and exports and kept in app-private storage, but the file is not encrypted at rest. | No credentials are added to browser-visible settings or exported config. Stronger at-rest protection is a separate threat-model/design proposal. |
| Low (test maintainability) | The viewer tests inspect serialized HTML/CSS/JavaScript strings and can be sensitive to harmless source refactors; they do not execute the browser layout algorithm. | Kept as focused regression protection without claiming browser validation. No existing assertions or test suites were removed. |
| Blocked (licensing) | The repository has no `LICENSE`, and accessible owner repositories do not establish one consistent license. GPL-3.0-or-later appears in `bme280-observatory`, `wireless-debugging`, `retro_selector`, and `sharpcap-sequence-analyzer`; `bmx280_win` uses Apache-2.0. The GPL examples use the actual author name David González López-Tercero; `bme280-observatory` also has a `SPDX-FileCopyrightText` header with an email address. | No license or SPDX identifier was chosen. A maintainer decision is required before adding `LICENSE` and first-party authorship/license headers. Third-party notices must not be changed. |

No changes were made to JPEG overlay rendering, telemetry JSON, camera ownership, the capture schedule, MQTT payload semantics, saved-settings schema, auth flow, credential files, or export behavior. No duplicate/unused implementation was removed during this focused change; the existing suites were retained.

## Changes in this release

- The viewer renders each telemetry row as a label and a complete value node. It keeps both on one line when their intrinsic widths fit; otherwise it moves the full value under its label, indented 4ch. Long values can wrap within the available indented width without clipping.
- Group separators are CSS borders between adjacent visible group sections, so disabled groups create no empty heading or leading/trailing separator. Panel resizing, font-size changes, and telemetry refreshes trigger a fresh width measurement.
- Settings are grouped in the requested order into titled sections and responsive columns. Existing camera-advertised controls and persisted field names remain in use; overlay fields and telemetry groups remain independent. Browser settings continue to omit credentials and report validation failures without partial writes.
- Release metadata is advanced together to versionName `0.8.0` and versionCode `8`. The optional Python snapshot-helper documentation no longer describes the native server as future work.

## Test inventory and evidence

`bash test/run.sh` runs 17 Java test programs and three shell suites: `camera-control-test.sh`, `adb-target-test.sh`, and `deploy-test.sh`. Existing coverage includes camera capability/config validation, Camera2 request mapping, unrestricted requested FPS parsing, transforms/orientation, overlay and telemetry formatting, MQTT payload/subscription behavior, atomic settings persistence, HTTP auth and request limits, MJPEG framing/lifecycle, and fake-ADB deployment workflows.

Current automated evidence for this change:

- `bash test/run.sh` — passed after the viewer changes and the 0.8.0 version update.
- `git diff --check` — passed.
- `bash build.sh` — not completed; the environment exits immediately with `Missing tool: aapt2`. No APK was built, installed, or tested.
- Samsung/browser/MQTT-broker manual validation — not performed.

The existing Java tests for HTTP/config and image transforms pass, but there is no real browser automation or Android instrumentation suite here. A successful host suite does not establish Samsung-specific Camera2 support or visual layout behavior.

## Prioritized improvement proposals

1. **Review a transport-security design before broader deployment.** Decide whether to bind to loopback by default, support a trusted reverse proxy, or add an authenticated TLS endpoint. Include token rotation, certificate lifecycle, and compatibility requirements; do not treat same-origin checks as encryption.
2. **Add browser-level layout testing.** Exercise actual rendered widths/font sizes for left/right and below-image layouts, group combinations, long labels/values, zoom, and resize. Keep the current lightweight served-page checks as fast regressions.
3. **Add Android lifecycle instrumentation on representative devices.** Verify camera permission/foreground restrictions, camera arbitration and cleanup, orientation/mirror/quality behavior, settings reloads, and service stop/restart on the supported Samsung/Android combinations.
4. **Clarify release hardening and MQTT secret storage.** Define whether the distributable build should remain debuggable and whether app-private MQTT credentials need encryption under the project threat model.

## Manual Samsung and browser checklist

These are outstanding tasks, not test results:

1. On a real Samsung device and a desktop/mobile browser, test the panel beside the image on both sides and below it at narrow widths. Resize the viewport and browser zoom, and change the panel font size while polling continues.
2. Enable Environment only, Device only, Capture only, each pair, all groups, and no groups. Confirm a thin separator appears only between visible sections, with no leading, trailing, or orphan line.
3. Use a capture telemetry value such as `4.5 / 5.0`. Confirm it stays a complete value: one line when label and value fit; otherwise the label is above and the full value is below at 4ch. Use unusually long values to confirm internal wrapping stays indented and does not overflow.
4. Load and save every Settings section. Verify responsive one-column layout, camera-advertised focus/exposure/white-balance choices, explicit units, independent overlay and panel selections/colors, and the short save acknowledgment. Reload settings and confirm persistence.
5. Submit an invalid camera/configuration candidate and verify the whole update is rejected and previous values remain. Confirm MQTT username/password are absent from browser requests, API responses, logs, and exported config.
6. On supported hardware, verify actual versus requested FPS, camera control results, JPEG quality, auto/manual rotation, mirror order, and capture behavior with overlay enabled/disabled. Check that the viewer panel never changes JPEG pixels.
7. Against a real broker and BME280 producer, verify TLS, credential provisioning, valid/retained/stale timestamps, reconnect, service stop, and that environmental values reach the panel only through the existing telemetry payload.
8. Re-run the HTTP auth/origin/token checks from the target browser and verify the deployment remains on a trusted network. Plain HTTP does not protect images or credentials from network observers.
