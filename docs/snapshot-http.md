# Termux snapshot HTTP server

This development helper runs in Python inside Termux, not inside the Android APK. It serves the previously exported snapshot.jpg. It does not activate the camera, retrieve private app files, refresh captures or stream video. Device execution is not yet verified. No third-party Python packages are required.

## Start locally

Run each command separately in Termux from the repository root:

```sh
pkg install python
```
```sh
python scripts/serve-snapshot.py --snapshot snapshot.jpg
```

Only http://127.0.0.1:8080/snapshot.jpg is served. There is no directory listing or repository file access through URL paths. Requests read the selected file again, so replacing it updates subsequent responses. GET and HEAD are supported. Missing files return 404; unreadable, oversized or incomplete JPEG files return 503. The JPEG marker check is not a full image decoder. This basic server handles requests sequentially and is not a production server.

Use another SSH session for a local test:

```sh
curl -I http://127.0.0.1:8080/snapshot.jpg
```

Stop with Ctrl+C. The server runs in the foreground; persistence after SSH disconnection or Android process suspension is not guaranteed.

## View from another machine

Preferred: keep the default loopback binding and use SSH local forwarding. On the viewing computer, replace PHONE_IP and SSH_PORT with your actual SSH connection details, including your normal SSH user if needed:

```sh
ssh -p SSH_PORT -L 18080:127.0.0.1:8080 PHONE_IP
```

Open http://127.0.0.1:18080/snapshot.jpg on that computer. Do not reuse an ADB port as the SSH port.

Alternatively, explicitly enable private-LAN access:

```sh
python scripts/serve-snapshot.py --snapshot snapshot.jpg --bind 0.0.0.0
```

Open http://PHONE_IP:8080/snapshot.jpg using the phone's actual LAN address. This exposes the image without authentication or encryption to reachable clients. Use only a trusted private network, do not configure router port forwarding and do not expose the server to the Internet. Stop it when not needed.

## Refresh safely

After a new successful camera capture, export to a temporary file, then replace the served file only if export succeeded and the file is nonempty. TARGET must be your current verified ADB target:

```sh
adb -s "$TARGET" exec-out run-as local.termux.observatorycamera cat files/snapshot.jpg > snapshot.new.jpg && test -s snapshot.new.jpg && mv snapshot.new.jpg snapshot.jpg
```

This copies an existing private snapshot; it does not prove a new capture succeeded. Check capture logs and timestamps first. Keep images private and do not commit them. A future native HTTP server will remove this manual export step.
