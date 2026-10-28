"""Exercise Android ELF build IDs after debug information is stripped."""
import os
from pathlib import Path
import platform
import subprocess
import tempfile
import unittest


NDK = os.environ.get('SOLUS_TEST_NDK') or str(Path(os.environ.get('ANDROID_HOME', '/missing')) / 'ndk/28.2.13676358')
HOST = 'windows-x86_64' if platform.system() == 'Windows' else 'linux-x86_64'
BIN = Path(NDK) / 'toolchains/llvm/prebuilt' / HOST / 'bin'
SUFFIX = '.exe' if platform.system() == 'Windows' else ''
CLANG, STRIP = BIN / ('clang' + SUFFIX), BIN / ('llvm-strip' + SUFFIX)


@unittest.skipUnless(CLANG.is_file() and STRIP.is_file(), 'Pinned Android NDK required')
class NativeDebugPathsTest(unittest.TestCase):
    def test_strip_does_not_leave_checkout_dependent_build_id(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            def build(name, normalize_build, omit_id=False):
                root = base / name
                build = root / 'app/.cxx' / name / 'arm64-v8a'
                build.mkdir(parents=True)
                source = root / 'source.c'
                source.write_text('#include "generated.h"\nstruct Record record;\nconst char * source_path(void) { return __FILE__; }\n')
                (build / 'generated.h').write_text('struct Record { int value; };\n')
                output = build / 'libfixture.so'
                flags = [f'-ffile-prefix-map={root.as_posix()}=/solus']
                if normalize_build:
                    flags += [f'-ffile-prefix-map={build.as_posix()}=/solus/native-build', '-fdebug-compilation-dir=/solus']
                if omit_id:
                    flags += ['-Wl,--build-id=none']
                subprocess.run([str(CLANG), '--target=aarch64-linux-android30', '-shared', '-nostdlib',
                    '-g', '-O2', '-flto=thin', '-Wl,--build-id=sha1', '-I' + build.as_posix(),
                    *flags, source.as_posix(), '-o', output.as_posix()], cwd=build, check=True, capture_output=True)
                subprocess.run([str(STRIP), '--strip-unneeded', str(output)], check=True, capture_output=True)
                return output.read_bytes()
            self.assertNotEqual(build('old-ci', False), build('old-fdroid', False))
            self.assertEqual(build('new-ci', True), build('new-fdroid', True))
            # Even a linked dependency's unnormalized debug paths must not leave
            # a differing ID in the APK after the debug information is stripped.
            ci = build('release-ci', False, omit_id=True)
            fdroid = build('release-fdroid', False, omit_id=True)
            self.assertEqual(ci, fdroid)
            self.assertNotIn(b'.note.gnu.build-id', ci)
