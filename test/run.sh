#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
P=local/termux/observatorycamera
javac --release 8 -d "$OUT" src/$P/JpegOrientation.java src/$P/SnapshotHttpServer.java test/$P/JpegOrientationTest.java test/$P/SnapshotHttpServerTest.java
java -cp "$OUT" local.termux.observatorycamera.JpegOrientationTest
java -cp "$OUT" local.termux.observatorycamera.SnapshotHttpServerTest
