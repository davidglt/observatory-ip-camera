#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
P=local/termux/observatorycamera
javac --release 8 -d "$OUT" src/$P/JpegOrientation.java src/$P/SnapshotHttpServer.java src/$P/ControlCommand.java src/$P/CaptureConfig.java src/$P/OverlayFormat.java src/$P/OverlayLayout.java src/$P/ExifOrientation.java src/$P/FrameStats.java src/$P/ContinuousStatus.java src/$P/CameraArbiter.java src/$P/SnapshotPublisher.java test/$P/JpegOrientationTest.java test/$P/ControlCommandTest.java test/$P/SnapshotHttpServerTest.java test/$P/CaptureConfigTest.java test/$P/OverlayTest.java test/$P/ContinuousStatsTest.java
java -cp "$OUT" local.termux.observatorycamera.JpegOrientationTest
java -cp "$OUT" local.termux.observatorycamera.SnapshotHttpServerTest
java -cp "$OUT" local.termux.observatorycamera.ControlCommandTest
for t in CaptureConfigTest OverlayTest ContinuousStatsTest; do java -cp "$OUT" local.termux.observatorycamera.$t; done
bash test/camera-control-test.sh
