"""Fast packaging-gate regressions; no Android SDK, model or build required."""

import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile

from apk_size_report import analyze_apk, verify_optimized


class ApkSizeReportTest(unittest.TestCase):
    LIBRARIES = (
        "libandroidx.graphics.path.so",
        "liblitertlm_jni.so",
        "libllm_inference_engine_jni.so",
        "libsolus_llama.so",
    )

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.apk = Path(self.directory.name) / "release.apk"

    def write_apk(self, missing=None, extra=None, dex_compression=zipfile.ZIP_DEFLATED):
        with zipfile.ZipFile(self.apk, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for name in self.LIBRARIES:
                if name != missing:
                    archive.writestr(f"lib/arm64-v8a/{name}", b"native fixture")
            archive.writestr("classes.dex", b"dex fixture", compress_type=dex_compression)
            if extra:
                archive.writestr(extra, b"unexpected fixture")
        return analyze_apk(self.apk)

    def test_current_runtime_bundle_passes_without_obsolete_litert_libraries(self):
        report = self.write_apk()
        self.assertEqual([], verify_optimized(report, 42_000_000))

    def test_each_current_native_library_is_required(self):
        for name in self.LIBRARIES:
            with self.subTest(library=name):
                errors = verify_optimized(self.write_apk(missing=name))
                self.assertTrue(any(name in error for error in errors), errors)

    def test_extra_abi_is_rejected(self):
        errors = verify_optimized(self.write_apk(extra="lib/x86_64/libsolus_llama.so"))
        self.assertTrue(any("Disallowed native ABI" in error for error in errors), errors)

    def test_bundled_emoji_font_is_rejected(self):
        errors = verify_optimized(self.write_apk(extra="assets/NotoColorEmojiCompat.ttf"))
        self.assertTrue(any("Bundled emoji font" in error for error in errors), errors)

    def test_uncompressed_dex_is_rejected(self):
        errors = verify_optimized(self.write_apk(dex_compression=zipfile.ZIP_STORED))
        self.assertTrue(any("DEX stored without compression" in error for error in errors), errors)

    def test_size_limit_is_inclusive_and_oversize_is_rejected(self):
        report = self.write_apk()
        report["apk_total_bytes"] = 42_000_000
        self.assertEqual([], verify_optimized(report, 42_000_000))
        report["apk_total_bytes"] += 1
        self.assertTrue(any("exceeds maximum" in error for error in verify_optimized(report, 42_000_000)))

    def test_failure_still_writes_diagnostic_report(self):
        self.write_apk(missing="liblitertlm_jni.so")
        output = Path(self.directory.name) / "report.json"
        result = subprocess.run(
            [sys.executable, str(Path(__file__).with_name("apk_size_report.py")),
             "--apk", str(self.apk), "--output", str(output), "--verify-optimized"],
            capture_output=True, text=True,
        )
        self.assertEqual(1, result.returncode)
        self.assertIn("liblitertlm_jni.so", result.stderr)
        self.assertEqual("release.apk", json.loads(output.read_text())["apk_filename"])


if __name__ == "__main__":
    unittest.main()
