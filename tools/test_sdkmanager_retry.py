"""Exercise SDK retries with a fake installer; never downloads Android tools."""
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import tempfile
import unittest


class SdkManagerRetryTest(unittest.TestCase):
    def run_installer(self, failures):
        bash = shutil.which("bash") if os.name != "nt" else r"C:\Program Files\Git\bin\bash.exe"
        if not bash or not Path(bash).is_file():
            self.skipTest("Bash not available")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fake = root / "sdkmanager"
            fake.write_text('''#!/usr/bin/env bash
test "$1" = "--install" || exit 2
count=0
if [ -f "$SDK_TEST_COUNT" ]; then count=$(cat "$SDK_TEST_COUNT"); fi
count=$((count+1))
echo "$count" > "$SDK_TEST_COUNT"
if [ "$count" -le "$SDK_TEST_FAILURES" ]; then exit 1; fi
exit 0
''', newline="\n")
            fake.chmod(0o755)
            (root / "sleep").write_text("#!/bin/sh\nexit 0\n", newline="\n")
            (root / "sleep").chmod(0o755)
            env = dict(os.environ, SDK_TEST_COUNT=(root / "count").as_posix(), SDK_TEST_FAILURES=str(failures))
            installer = Path(__file__).with_name("install-android-sdk.sh").resolve().as_posix()
            bin_path = root.as_posix()
            if os.name == "nt":
                bin_path = "/" + bin_path[0].lower() + bin_path[2:]
            command = f'export PATH={shlex.quote(bin_path)}:"$PATH"; bash {shlex.quote(installer)} {shlex.quote(fake.as_posix())} "ndk;28.2.13676358" "cmake;3.22.1"'
            result = subprocess.run([bash, "-c", command], env=env, capture_output=True, timeout=20)
            return result.returncode, int((root / "count").read_text())

    def test_success_does_not_retry(self):
        self.assertEqual((0, 1), self.run_installer(0))

    def test_transient_failures_retry_then_succeed(self):
        self.assertEqual((0, 3), self.run_installer(2))

    def test_persistent_failure_stops_after_three_attempts(self):
        self.assertEqual((1, 3), self.run_installer(10))


if __name__ == "__main__":
    unittest.main()
