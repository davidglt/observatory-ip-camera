#!/usr/bin/env python3
# Copyright (C) 2026 David González López-Tercero
# SPDX-License-Identifier: GPL-3.0-or-later
# See LICENSE in the repository root for licensing details.

import json
import re
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "src/local/termux/observatorycamera/SnapshotHttpServer.java"

def main():
    lines = SOURCE.read_text(encoding="utf-8").splitlines()
    starts = [i for i, line in enumerate(lines) if "function telemetryRow(" in line]
    ends = [
        i for i, line in enumerate(lines)
        if "updateTelemetry();setInterval(updateTelemetry" in line
    ]
    if len(starts) != 1 or len(ends) != 1 or starts[0] >= ends[0]:
        raise RuntimeError("Unexpected telemetry renderer boundaries")
    chunks = []
    for line in lines[starts[0]:ends[0]]:
        match = re.fullmatch(r'\s*\+\s*("(?:[^"\\]|\\.)*")\s*', line)
        if not match:
            raise RuntimeError("Unexpected Java string expression")
        chunks.append(json.loads(match.group(1)))
    with tempfile.TemporaryDirectory(prefix="memory-viewer-test-") as temp:
        script = Path(temp) / "renderer.js"
        script.write_text("".join(chunks), encoding="utf-8")
        subprocess.run(
            ["node", "--check", str(script)], check=True, timeout=30
        )
        subprocess.run(
            ["node", str(ROOT / "test/browser-memory-viewer-test.js"), str(script)],
            check=True, timeout=60, cwd=ROOT,
        )

if __name__ == "__main__":
    main()
