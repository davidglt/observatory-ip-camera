#!/usr/bin/env bash
# Copyright (C) 2026 David González López-Tercero
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
P=local/termux/observatorycamera
javac --release 8 -d "$OUT" "src/$P/CpuNormalization.java" "test/$P/CpuNormalizationTest.java"
java -cp "$OUT" local.termux.observatorycamera.CpuNormalizationTest
