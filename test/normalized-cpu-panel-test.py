#!/usr/bin/env python3
# Copyright (C) 2026 David González López-Tercero
# SPDX-License-Identifier: GPL-3.0-or-later
# See LICENSE in the repository root for licensing details.

import ast
import hashlib
import json
import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
P = Path("local/termux/observatorycamera")
PACKAGE = "local.termux.observatorycamera"

def replace_once(text, old, new):
    if text.count(old) != 1:
        raise RuntimeError("Unexpected harness structure")
    return text.replace(old, new, 1)

class TextPanelAcceptance(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        source = ROOT / "test/telemetry-rest-test.py"
        tree = ast.parse(source.read_text(encoding="utf-8"))
        constants = {}
        for node in tree.body:
            if isinstance(node, ast.Assign):
                for target in node.targets:
                    if isinstance(target, ast.Name) and target.id in ("STUBS", "HARNESS"):
                        constants[target.id] = ast.literal_eval(node.value)

        harness = constants["HARNESS"]
        harness = replace_once(
            harness,
            'null, new CaptureConfig());',
            'null, panelConfig());',
        )
        harness = replace_once(
            harness,
            'System.out.println(name + "\\t" + snapshot.toJson());',
            'System.out.println(name + "\\t" + String.join("\\u001f", snapshot.panelLines()));',
        )
        harness = replace_once(
            harness,
            '    public static void main(String[] args) throws Exception {',
            '''    private static CaptureConfig panelConfig() {
        CaptureConfig config = new CaptureConfig();
        config.panelDevice = true;
        return config;
    }
    public static void main(String[] args) throws Exception {''',
        )
        harness = replace_once(
            harness,
            '        emit("partial", null, 160.0, 8, 3, 0, false);',
            '''        emit("partial", null, 160.0, 8, 3, 0, false);
        emit("partial_current", 200.0, null, 8, 3, 0, false);
        emit("forced_stale", 200.0, 160.0, 8, 3, 0, true);''',
        )

        with tempfile.TemporaryDirectory(prefix="cpu-text-acceptance-") as temp:
            out = Path(temp)
            package = out / P
            package.mkdir(parents=True)
            context = out / "android/content/Context.java"
            context.parent.mkdir(parents=True)
            context.write_text(
                'package android.content; public class Context { '
                'public java.io.File getFilesDir() { return new java.io.File("."); } }',
                encoding="utf-8",
            )
            stubs = package / "TelemetryTestDependencies.java"
            stubs.write_text(constants["STUBS"], encoding="utf-8")
            fixture = package / "TelemetryRestFixture.java"
            fixture.write_text(harness, encoding="utf-8")
            sources = [
                ROOT / "src" / P / name for name in (
                    "TelemetrySnapshot.java", "CpuNormalization.java",
                    "ProcessMetrics.java",
                )
            ]
            subprocess.run(
                ["javac", "--release", "8", "-sourcepath", str(out),
                 "-d", str(out)]
                + [str(path) for path in sources + [context, stubs, fixture]],
                check=True, timeout=60,
            )
            run = subprocess.run(
                ["java", "-cp", str(out), PACKAGE + ".TelemetryRestFixture"],
                check=True, capture_output=True, text=True, timeout=30,
            )
            cls.lines = {}
            for line in run.stdout.splitlines():
                name, payload = line.split("\t", 1)
                cls.lines[name] = payload.split("\x1f")

    def value(self, case, prefix):
        rows = [line for line in self.lines[case] if line.startswith(prefix)]
        self.assertEqual(len(rows), 1, (case, prefix, self.lines[case]))
        return rows[0].split(": ", 1)[1]

    def cpu(self, case, average=False):
        prefix = "CPU recent average (" if average else "CPU current ("
        return self.value(case, prefix)

    def assertPercent(self, text, number, stale=False):
        suffix = r"\s*\(stale\)" if stale else ""
        self.assertRegex(
            text, r"^" + re.escape(number) + r"\s*%" + suffix + r"$"
        )

    def test_available_uses_dot_under_german_locale(self):
        self.assertPercent(self.cpu("available"), "25.00")
        self.assertPercent(self.cpu("available", True), "20.00")
        self.assertEqual(self.value("available", "Logical CPU count used:"), "8")

    def test_zero_and_values_above_100(self):
        for case, current, average in (
            ("zero", "0.00", "0.00"),
            ("unclamped", "125.00", "112.50"),
        ):
            with self.subTest(case=case):
                self.assertPercent(self.cpu(case), current)
                self.assertPercent(self.cpu(case, True), average)

    def test_collecting_and_unavailable(self):
        for case in ("empty", "first", "missing_cpu"):
            expected = "Unavailable" if case == "missing_cpu" else "Collecting"
            for average in (False, True):
                with self.subTest(case=case, average=average):
                    self.assertEqual(self.cpu(case, average), expected)

    def test_partial_readings_are_independent(self):
        self.assertEqual(self.cpu("partial"), "Unavailable")
        self.assertPercent(self.cpu("partial", True), "20.00")
        self.assertPercent(self.cpu("partial_current"), "25.00")
        self.assertEqual(self.cpu("partial_current", True), "Unavailable")

    def test_stale_values_including_partial_and_forced_flag(self):
        for case in ("stale", "forced_stale", "stale_partial"):
            with self.subTest(case=case):
                self.assertPercent(self.cpu(case, True), "20.00", stale=True)
                if case == "stale_partial":
                    self.assertEqual(self.cpu(case), "Unavailable")
                else:
                    self.assertPercent(self.cpu(case), "25.00", stale=True)

    def test_invalid_counts(self):
        for case in ("missing_count", "zero_count", "negative_count"):
            with self.subTest(case=case):
                self.assertEqual(self.cpu(case), "Unavailable")
                self.assertEqual(self.cpu(case, True), "Unavailable")
                self.assertEqual(
                    self.value(case, "Logical CPU count used:"), "Unavailable"
                )

if __name__ == "__main__":
    watched = [
        ROOT / "src" / P / "TelemetrySnapshot.java",
        ROOT / "test/telemetry-rest-test.py",
    ]
    before = {
        str(path): hashlib.sha256(path.read_bytes()).hexdigest()
        for path in watched
    }
    result = unittest.main(verbosity=2, exit=False).result
    unchanged = all(
        hashlib.sha256(Path(path).read_bytes()).hexdigest() == digest
        for path, digest in before.items()
    )
    print("Candidate and reused harness unchanged:", unchanged)
    raise SystemExit(0 if result.wasSuccessful() and unchanged else 1)
