"""Check real llama.cpp metadata generation with shallow/full Git histories."""
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest


CMAKE = os.environ.get('SOLUS_TEST_CMAKE') or shutil.which('cmake')


@unittest.skipUnless(CMAKE and shutil.which('git'), 'CMake and Git required')
class NativeBuildMetadataTest(unittest.TestCase):
    def test_pinned_identity_is_independent_of_clone_depth(self):
        root = Path(__file__).resolve().parent.parent
        cpp = root / 'app/src/main/cpp'
        parent = (cpp / 'CMakeLists.txt').read_text()
        upstream = (cpp / 'llama.cpp/CMakeLists.txt').read_text()
        defaults = upstream[upstream.index('if (NOT DEFINED LLAMA_BUILD_NUMBER)'):upstream.index('# override ggml options')]
        pins = '\n'.join(re.findall(r'^set\((?:SOLUS_LLAMA_UPSTREAM_COMMIT|LLAMA_BUILD_NUMBER|LLAMA_BUILD_COMMIT) [^\n]+', parent, re.M))
        self.assertEqual(3, len(pins.splitlines()))
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            full, shallow = base / 'full', base / 'shallow'
            def git(*args, cwd=None):
                return subprocess.run(['git', *args], cwd=cwd, check=True, capture_output=True, text=True).stdout.strip()
            git('init', str(full))
            for number in (1, 2):
                (full / 'file').write_text(str(number))
                git('add', 'file', cwd=full)
                git('-c', 'user.name=Test', '-c', 'user.email=test@example.invalid', 'commit', '-m', 'fixture', cwd=full)
            git('clone', '--depth', '1', full.as_uri(), str(shallow))
            self.assertEqual('2', git('rev-list', '--count', 'HEAD', cwd=full))
            self.assertEqual('1', git('rev-list', '--count', 'HEAD', cwd=shallow))
            def generate(repo, pinned):
                output = base / 'build-info.cpp'
                script = base / 'generate.cmake'
                script.write_text('\n'.join([
                    f'set(CMAKE_CURRENT_SOURCE_DIR "{repo.as_posix()}")',
                    pins if pinned else '',
                    f'include("{(cpp / "llama.cpp/cmake/build-info.cmake").as_posix()}")',
                    defaults,
                    f'configure_file("{(cpp / "llama.cpp/common/build-info.cpp.in").as_posix()}" "{output.as_posix()}" @ONLY)',
                ]))
                subprocess.run([CMAKE, '-P', str(script)], check=True, capture_output=True, text=True)
                return output.read_text()
            self.assertNotEqual(generate(full, False), generate(shallow, False))
            self.assertEqual(generate(full, True), generate(shallow, True))
