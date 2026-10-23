import base64
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from prepare_release_apk import prepare


class PrepareReleaseApkTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.artifact = self.root / "artifact"
        self.artifact.mkdir()
        self.output = self.root / "prepared"
        self.tools = self.root / "build-tools"
        self.tools.mkdir()
        for name in ("zipalign", "apksigner"):
            (self.tools / name).touch()
        self.secrets = {
            "ANDROID_KEYSTORE_BASE64": base64.b64encode(b"fixture key").decode(),
            "ANDROID_KEYSTORE_PASSWORD": "fixture-store-password",
            "ANDROID_KEY_ALIAS": "release-alias",
            "ANDROID_KEY_PASSWORD": "fixture-key-password",
            "ANDROID_SIGNING_CERT_SHA256": "ab" * 32,
            "RUNNER_TEMP": str(self.root),
        }

    def run_prepare(self, kind="debug", tag="v6.0.2"):
        return prepare(self.artifact, self.output, kind, tag, "39999274715", self.tools)

    def fake_signer(self, args, **kwargs):
        if args[1] == "-P":
            shutil.copyfile(args[-2], args[-1])
        elif args[1] == "sign":
            self.assertTrue(Path(args[args.index("--ks") + 1]).is_file())
            shutil.copyfile(args[-1], args[args.index("--out") + 1])
            self.assertNotIn(self.secrets["ANDROID_KEYSTORE_PASSWORD"], args)
            self.assertNotIn(self.secrets["ANDROID_KEY_PASSWORD"], args)
            self.assertIn("env:ANDROID_KEYSTORE_PASSWORD", args)
            self.assertIn("env:ANDROID_KEY_PASSWORD", args)
        return subprocess.CompletedProcess(args, 0, stdout=f"Signer #1 certificate SHA-256 digest: {'ab' * 32}\n")

    def test_debug_copy_preserves_signature_bytes_and_writes_checksum(self):
        source = self.artifact / "app-debug.apk"
        source.write_bytes(b"signed debug fixture")
        output = self.run_prepare()
        self.assertEqual(source.read_bytes(), output.read_bytes())
        self.assertIn(output.name, output.with_suffix(".apk.sha256").read_text())
        with self.assertRaisesRegex(ValueError, "already exists"):
            self.run_prepare()
        self.assertEqual(source.read_bytes(), output.read_bytes())

    def test_invalid_tag_and_multiple_apks_are_rejected(self):
        with self.assertRaisesRegex(ValueError, "Invalid release"):
            self.run_prepare(tag="../release")
        for name in ("a.apk", "b.apk"):
            (self.artifact / name).touch()
        with self.assertRaisesRegex(ValueError, "exactly one"):
            self.run_prepare()

    def test_debug_apk_cannot_be_relabelled_as_release(self):
        (self.artifact / "app-debug.apk").touch()
        with self.assertRaisesRegex(ValueError, "unsigned release"):
            self.run_prepare("signed-release")

    def test_missing_signing_configuration_fails_closed(self):
        (self.artifact / "app-release-unsigned.apk").touch()
        with patch.dict(os.environ, {}, clear=True), self.assertRaisesRegex(ValueError, "Missing signing configuration"):
            self.run_prepare("signed-release")
        self.assertFalse(list(self.output.glob("*.apk")))

    def test_release_aligns_signs_verifies_and_removes_temporary_key(self):
        (self.artifact / "app-release-unsigned.apk").write_bytes(b"release fixture")
        with patch.dict(os.environ, self.secrets), patch("prepare_release_apk.subprocess.run", side_effect=self.fake_signer) as run:
            output = self.run_prepare("signed-release")
        self.assertTrue(output.is_file())
        self.assertEqual(3, run.call_count)
        self.assertEqual("-P", run.call_args_list[0].args[0][1])
        self.assertEqual("verify", run.call_args_list[2].args[0][1])
        self.assertFalse(list(self.root.glob("solus-sign-*")))

    def test_wrong_certificate_removes_output_and_key(self):
        (self.artifact / "app-release-unsigned.apk").touch()
        def wrong_signer(args, **kwargs):
            result = self.fake_signer(args, **kwargs)
            result.stdout = f"Signer #1 certificate SHA-256 digest: {'cd' * 32}\n"
            return result
        with patch.dict(os.environ, self.secrets), patch("prepare_release_apk.subprocess.run", side_effect=wrong_signer):
            with self.assertRaisesRegex(ValueError, "does not match"):
                self.run_prepare("signed-release")
        self.assertFalse(list(self.output.glob("*")))
        self.assertFalse(list(self.root.glob("solus-sign-*")))

    def test_signing_tool_failure_does_not_leave_output_or_keystore(self):
        (self.artifact / "app-release-unsigned.apk").touch()
        def failing_signer(args, **kwargs):
            self.fake_signer(args, **kwargs)
            if args[1] == "sign":
                raise subprocess.CalledProcessError(1, args)
            return subprocess.CompletedProcess(args, 0)
        with patch.dict(os.environ, self.secrets), patch("prepare_release_apk.subprocess.run", side_effect=failing_signer):
            with self.assertRaises(subprocess.CalledProcessError):
                self.run_prepare("signed-release")
        self.assertFalse(list(self.output.glob("*")))
        self.assertFalse(list(self.root.glob("solus-sign-*")))


if __name__ == "__main__":
    unittest.main()
