import tempfile
import unittest
from pathlib import Path
import zipfile

from compare_release_apks import compare


class CompareReleaseApksTest(unittest.TestCase):
    def test_detects_native_library_difference(self):
        with tempfile.TemporaryDirectory() as directory:
            a, b = Path(directory) / 'ci.apk', Path(directory) / 'fdroid.apk'
            for path, data in [(a, b'compiler one'), (b, b'compiler two')]:
                with zipfile.ZipFile(path, 'w') as apk:
                    apk.writestr('classes.dex', b'same dex')
                    apk.writestr('lib/arm64-v8a/libsolus_candle.so', data)
            result = compare(a, b)
            self.assertFalse(result['identical'])
            self.assertEqual(['lib/arm64-v8a/libsolus_candle.so'], result['different_entries'])

    def test_equal_entries_with_different_zip_metadata_are_not_reproducible(self):
        with tempfile.TemporaryDirectory() as directory:
            a, b = Path(directory) / 'ci.apk', Path(directory) / 'fdroid.apk'
            for path, year in [(a, 2025), (b, 2026)]:
                with zipfile.ZipFile(path, 'w') as apk:
                    apk.writestr(zipfile.ZipInfo('classes.dex', (year, 1, 1, 0, 0, 0)), b'same dex')
            self.assertFalse(compare(a, b)['identical'])
            self.assertEqual([], compare(a, b)['different_entries'])
            self.assertTrue(compare(a, a)['identical'])
