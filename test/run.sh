#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
javac --release 8 -d "$OUT" src/local/termux/observatorycamera/JpegOrientation.java test/local/termux/observatorycamera/JpegOrientationTest.java
java -cp "$OUT" local.termux.observatorycamera.JpegOrientationTest
