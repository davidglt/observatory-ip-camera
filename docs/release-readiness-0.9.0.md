# 0.9.0 release-readiness report

## Status

The source metadata is `versionName=0.9.0`, `versionCode=9`. This is a development version, not a stable-release or 1.0 claim. Release publication and APK deployment were not performed.

Maintainer-reported visual validation is limited to the Settings organization and telemetry separators/whole-value wrapping with 4ch indentation. It does not establish comprehensive browser, Samsung device, or network validation.

## Network and security behavior

- Defaults remain HTTP enabled on `0.0.0.0:8080`, HTTPS disabled on port 8443.
- At least one protocol must be enabled; enabled ports must differ. Wildcard binding is exclusive; concrete IPv4 addresses can be combined. Unavailable saved addresses remain visible and are never broadened.
- The service reports persisted/requested endpoints separately from effective bound endpoints. Listener replacement stages the new set first; a failed stage leaves the old listeners active and reports the mismatch.
- HTTP is unencrypted and exposes the admin bearer token to network observers. HTTPS uses an explicitly provisioned certificate but does not authenticate public viewer, image, stream, or telemetry access.
- HTTPS certificate generation/import/status/export are SSH/ADB operations. The supported import is PKCS#12. Private key/password data stay in app-private files; browser key import is disabled. No certificate is silently generated at startup.
- Self-signed certificates require client trust setup and matching IP/DNS SANs. Use `certificate-export` only for the public PEM, verify its fingerprint through a trusted channel, and do not disable TLS verification or use `curl -k`.
- Keep authorized SSH/ADB recovery available before changing the active route. A port collision can prevent staging a replacement when old and new listener sets overlap; resolve the conflict, then run `network-apply` or stop/restart the service. At least one transport must remain configured.

## Supported SSH and properties procedure

Set and verify an explicit target before any command:

```sh
TARGET='VERIFIED_ADB_SERIAL_OR_HOST:PORT'
adb -s "$TARGET" get-state
```

Common operations:

```sh
bash scripts/camera-control.sh -s "$TARGET" config-show
bash scripts/camera-control.sh -s "$TARGET" config-validate
bash scripts/camera-control.sh -s "$TARGET" config-reload
bash scripts/camera-control.sh -s "$TARGET" network-apply
bash scripts/camera-control.sh -s "$TARGET" status
```

Direct ordinary settings edits target app-private `files/camera.properties`, use a same-directory temporary file and atomic rename, then validate and explicitly reload:

```sh
adb -s "$TARGET" shell "run-as local.termux.observatorycamera sh -c 'umask 077; cat > files/camera.properties.tmp && mv files/camera.properties.tmp files/camera.properties'" < camera.properties
bash scripts/camera-control.sh -s "$TARGET" config-validate
bash scripts/camera-control.sh -s "$TARGET" config-reload
```

Alternatively use `config-export --out FILE` and `config-import --in FILE`. A whole candidate is validated before replacement. A malformed edit is retained as a reported error and does not replace the last valid effective settings. MQTT secrets are separate in `files/mqtt-credentials.properties`; do not include them in normal exports. Certificate material is separate in `https-server.p12`, `https-server.password` and the public-only `https-server.pem`.

Certificate operations:

```sh
bash scripts/camera-control.sh -s "$TARGET" certificate-generate --san IP:127.0.0.1,DNS:camera.example
bash scripts/camera-control.sh -s "$TARGET" certificate-status
bash scripts/camera-control.sh -s "$TARGET" certificate-export --out https-server.pem
bash scripts/camera-control.sh -s "$TARGET" certificate-import --in server.p12 --password-file server-password.txt
```

Replace sample SANs with the actual client-facing addresses/names. Keep local private files protected, do not put passwords in command arguments or shell history, and do not send private keys through the browser over HTTP. Configure HTTPS and run `network-apply` only after a valid identity is installed.

## Automated validation performed

| Command | Result |
| --- | --- |
| `bash test/run.sh` | Passed: license headers, 21 Java test programs, and the camera-control, adb-target and deploy shell suites. |
| `bash -n scripts/camera-control.sh` | Passed. |
| Focused `bash test/camera-control-test.sh` | Passed, including config validate/reload, network apply, certificate status/import/export, private-material separation and invalid SAN rejection. |
| `bash build.sh` | Not completed: the environment does not provide `aapt2` (`Missing tool: aapt2`). This is an unavailable build prerequisite, not a source compile result. |
| GitHub Actions run `41804127782` | In progress when checked; one job was processing the request and zero failed jobs were reported. No CI conclusion is claimed. |

`NetworkListenerTest` exercises multi-address binding/rollback and a TLS handshake with an explicitly trusted test certificate. `TlsCertificateStoreTest` covers certificate validation and failed-import retention. These are host tests; they are not Samsung hardware or browser tests. The served-page tests inspect markup and source invariants and do not execute a browser layout engine.

## Required pre-stable checks

Record device model, Android/One UI version, browser/version, selected interface/address, and fresh command output for each applicable test. Do not claim a check that was not actually run.

1. **Camera2 and ownership:** grant permissions intentionally; test single capture, sustained capture, stop/restart, and competing camera use. Confirm exactly one camera owner, serialized captures, bounded image buffers, no second camera session from HTTP/HTTPS, and no camera access by the listener service.
2. **Lifecycle:** repeat start/stop and capture through activity pause/resume, screen-off, lock, Doze, process death and notification denial. Record Samsung-specific behavior; no background-camera or Doze guarantee is promised.
3. **Network UI and routing:** verify wildcard exclusivity, simultaneous concrete addresses, loopback-only, unavailable-address retention, interface refresh, DHCP address change, port collision, HTTP-only, HTTPS-only and both enabled. Check requested versus bound status and confirm failures do not claim success or silently bind wildcard.
4. **SSH forwarding and certificates:** use an explicit `TARGET`; verify loopback forwarding with non-default ports, HTTP status, HTTPS IP SAN and DNS SAN, fingerprint/expiry, public-only certificate export, wrong password, mismatched key, invalid/expired chain, failed-import retention and route recovery over SSH/ADB.
5. **HTTP/TLS protections:** test public image/telemetry policy, protected settings APIs, bearer authentication, same-origin mutations, cross-origin rejection, Host authorities, bounded headers/body/connections and no permissive CORS. Confirm HTTP token risk is clearly shown; HTTPS must not imply authentication.
6. **Properties recovery/parity:** exercise every configurable key through `config-set` or direct `camera.properties` editing, full-candidate validation, atomic rejection, reload, malformed/unknown properties recovery, MQTT secret separation and certificate file permissions.
7. **MQTT:** test source changes for broker host/port, TLS and topic; same-source reconnect retains fresh/stale history; old subscriber callbacks, retained samples and out-of-order timestamps cannot overwrite newer source data. Test credential add/replace/remove with unchanged modification time and malformed files without reconnect storms or secret exposure.
8. **Browser rendering:** run actual browser layout tests against the production-served viewer and Settings at phone/desktop sizes, including zoom, resizing, long values, whole-value wrapping, 4ch indentation, telemetry separators, fonts and Network checkbox states. Current automated tests do not provide this evidence.
9. **Live broker:** confirm actual authentication, TLS trust, topic and sensor payload behavior with a controlled broker and BME280 source. Host tests do not establish broker interoperability.

## Known limitations and future build design

- No Samsung device, live broker, or rendered browser validation was performed for this change.
- ADB/Android build tools are incomplete in this environment (`aapt2` is missing), so APK build/install evidence remains outstanding.
- Android interface discovery and binding behavior still need testing across target Samsung devices, Wi-Fi/Ethernet transitions and DHCP changes.
- Changing overlapping listener bindings can require a service stop/restart; preserve SSH/ADB access for recovery.
- A future development-versus-distribution build split should be designed separately. Do not disable `debuggable`, break `run-as`, or remove SSH administration until an equivalent documented secure provisioning/recovery path is implemented and tested.
