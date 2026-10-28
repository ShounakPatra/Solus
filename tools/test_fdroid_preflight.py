import shutil
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import io

from fdroid_preflight import check


class FDroidPreflightTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        source = Path(__file__).resolve().parent.parent
        files = ['app/build.gradle.kts', 'fdroid/official/com.shounak.localmeshai.yml',
                 'LICENSE', 'gradle/wrapper/gradle-wrapper.properties',
                 '.github/workflows/android-ci.yml']
        files += [str(p.relative_to(source)) for p in (source / 'fastlane').rglob('*') if p.is_file()]
        for name in files:
            target = self.root / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source / name, target)

    def test_version_mismatch_fails_before_build(self):
        path = self.root / 'fdroid/official/com.shounak.localmeshai.yml'
        code = check(self.root)['versionCode']
        path.write_text(path.read_text().replace(f'versionCode: {code}', f'versionCode: {code + 1}'))
        result = check(self.root)
        self.assertFalse(result['source_checks_passed'])
        self.assertIn('Recipe versionCode does not match app', result['errors'])

    def test_source_success_does_not_claim_publication_ready(self):
        result = check(self.root)
        self.assertTrue(result['source_checks_passed'], result['errors'])
        self.assertIn('F-Droid maintainer approval', result['still_required'])

    def test_missing_gradle_in_catalog_fails(self):
        with patch('fdroid_preflight.urllib.request.urlopen', return_value=io.BytesIO(b'{}')):
            result = check(self.root, verify_gradle=True)
        self.assertFalse(result['source_checks_passed'])
        self.assertIn('Gradle version/checksum absent from F-Droid catalog', result['errors'])

    def test_developer_signing_requirements_are_rejected(self):
        path = self.root / 'fdroid/official/com.shounak.localmeshai.yml'
        original = path.read_text()
        for field in ('AllowedAPKSigningKeys: []', 'Binaries: https://example.invalid/app.apk',
                      'binary: https://example.invalid/app.apk'):
            with self.subTest(field=field):
                path.write_text(original + '\n' + field + '\n')
                self.assertFalse(check(self.root)['source_checks_passed'])
