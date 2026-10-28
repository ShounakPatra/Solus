"""Keep the known ZIP security fixes in the shipping Rust dependency graph."""
from pathlib import Path
import tomllib
import unittest


class RustDependencySecurityTest(unittest.TestCase):
    def test_no_vulnerable_zip_version_is_locked(self):
        root = Path(__file__).resolve().parent.parent
        lock = tomllib.loads((root / 'app/src/main/rust/Cargo.lock').read_text())
        versions = [p['version'] for p in lock['package'] if p['name'] == 'zip']
        self.assertTrue(versions, 'ZIP must stay visible to the dependency guard')
        for version in versions:
            with self.subTest(version=version):
                self.assertNotIn('-', version, 'Review prerelease ZIP versions explicitly')
                self.assertGreaterEqual(tuple(map(int, version.split('.'))), (9, 0, 5),
                                        'GHSA-m665-v7pm-7grj requires ZIP 9.0.5 or newer')
