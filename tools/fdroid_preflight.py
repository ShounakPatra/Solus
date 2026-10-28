"""Fast source checks, not a certificate of official F-Droid eligibility."""
import argparse
import json
from pathlib import Path
import re
import urllib.request


def check(root, verify_gradle=False):
    root = Path(root)
    read = lambda name: (root / name).read_text(encoding='utf-8')
    errors = []

    def require(condition, message):
        if not condition:
            errors.append(message)

    app = read('app/build.gradle.kts')
    version = re.search(r'versionName\s*=\s*"([^"]+)"', app).group(1)
    code = int(re.search(r'versionCode\s*=\s*(\d+)', app).group(1))
    recipe = read('fdroid/official/com.shounak.localmeshai.yml')
    require(re.search(r'versionName:\s*[\'\"]?' + re.escape(version) + r'[\'\"]?\s*$', recipe, re.M), 'Recipe versionName does not match app')
    require(re.search(r'versionCode:\s*' + str(code) + r'\s*$', recipe, re.M), 'Recipe versionCode does not match app')
    require('Apache License' in read('LICENSE'), 'Missing expected Apache license')
    require('License: Apache-2.0' in recipe, 'Recipe license does not match project')
    require(not re.search(r'^\s*(AllowedAPKSigningKeys|Binaries|binary):', recipe, re.M),
            'Official recipe must use F-Droid signing, without developer APK references or signing-key restrictions')
    listing = 'fastlane/metadata/android/en-US/'
    summary = read(listing + 'short_description.txt').strip()
    require(0 < len(summary) < 80 and not summary.endswith('.'), 'Listing summary must be under 80 characters, without a trailing dot')
    require(bool(read(listing + 'full_description.txt').strip()), 'Empty full description')
    notes = read(listing + f'changelogs/{code}.txt').strip()
    require(0 < len(notes) <= 500, 'Changelog must contain 1-500 characters')
    require((root / listing / 'images/icon.png').is_file(), 'Missing listing icon')
    require(len(list((root / listing / 'images/phoneScreenshots').glob('*'))) >= 2, 'Need at least two listing screenshots')
    props = dict(line.split('=', 1) for line in read('gradle/wrapper/gradle-wrapper.properties').splitlines() if '=' in line)
    url = props['distributionUrl'].replace('\\:', ':')
    require(bool(re.fullmatch(r'[a-f0-9]{64}', props.get('distributionSha256Sum', ''))), 'Gradle distribution checksum missing')
    workflows = [read('.github/workflows/' + name) for name in ('android-ci.yml', 'fdroid-official-check.yml')]
    for workflow in workflows:
        require('runs-on: ubuntu-24.04' in workflow, 'Both builders must use the same pinned Ubuntu release')
        for tool in ('1.90.0', '28.2.13676358', '3.22.1'):
            require(tool in workflow, f'Builder is missing toolchain pin {tool}')
    require('ndkVersion = "28.2.13676358"' in app, 'App NDK does not match CI')
    if verify_gradle:
        with urllib.request.urlopen('https://gitlab.com/fdroid/gradle-transparency-log/-/raw/master/checksums.json', timeout=30) as response:
            catalog = json.load(response)
        require(any(entry.get('sha256') == props['distributionSha256Sum'] for entry in catalog.get(url, [])), 'Gradle version/checksum absent from F-Droid catalog')
    return dict(version=version, versionCode=code, source_checks_passed=not errors, errors=errors,
                still_required=['FOSS dependency and license review', 'Production F-Droid environment/recipe validation',
                                'Tagged release source', 'F-Droid signing and publication',
                                'Final APK phone testing', 'F-Droid maintainer approval'])


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parent.parent)
    parser.add_argument('--verify-gradle', action='store_true')
    args = parser.parse_args()
    result = check(args.root, args.verify_gradle)
    print(json.dumps(result, indent=2))
    raise SystemExit(0 if result['source_checks_passed'] else 1)
