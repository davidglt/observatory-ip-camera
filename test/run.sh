#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
P=local/termux/observatorycamera
javac --release 8 -d "$OUT" src/$P/JpegOrientation.java src/$P/SnapshotHttpServer.java src/$P/ControlCommand.java test/$P/JpegOrientationTest.java test/$P/ControlCommandTest.java test/$P/SnapshotHttpServerTest.java
java -cp "$OUT" local.termux.observatorycamera.JpegOrientationTest
java -cp "$OUT" local.termux.observatorycamera.SnapshotHttpServerTest
java -cp "$OUT" local.termux.observatorycamera.ControlCommandTest
bash test/camera-control-test.sh
