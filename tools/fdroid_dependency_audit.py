"""Record resolved runtime licenses and flag missing evidence; not maintainer approval."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import re
import urllib.request
import xml.etree.ElementTree as ET
import zipfile


REPOSITORIES = ('https://dl.google.com/dl/android/maven2/',
                'https://repo.maven.apache.org/maven2/')
PROHIBITED = re.compile(r'^(com\.google\.firebase|com\.google\.android\.gms|com\.google\.android\.datatransport)(:|\.)')
FOSS_NAMES = re.compile(r'apache|mit\b|bsd|isc\b|eclipse public|mozilla public|gnu (lesser |affero )?general public|public domain|unlicense|zlib|unicode|cc0', re.I)
NS = {'m': 'http://maven.apache.org/POM/4.0.0'}


def pom_evidence(coordinate, cache, depth=0):
    group, name, version = coordinate.split(':')
    local = cache / group / name / version
    files = list(local.glob('**/*.pom'))
    origin = 'Gradle cache'
    if files:
        content = files[0].read_bytes()
    else:
        content = None
        relative = f'{group.replace(".", "/")}/{name}/{version}/{name}-{version}.pom'
        for repo in REPOSITORIES:
            try:
                with urllib.request.urlopen(repo + relative, timeout=30) as response:
                    content = response.read()
                origin = repo + relative
                break
            except Exception:
                continue
        if content is None:
            return {'licenses': [], 'source': '', 'pom': None, 'error': 'POM unavailable'}
    root = ET.fromstring(content)
    licenses = [dict(name=node.findtext('m:name', '', NS), url=node.findtext('m:url', '', NS))
                for node in root.findall('m:licenses/m:license', NS)]
    source = root.findtext('m:scm/m:url', '', NS) or root.findtext('m:url', '', NS)
    result = dict(licenses=licenses, source=source, pom=origin,
                  pom_sha256=hashlib.sha256(content).hexdigest())
    parent = root.find('m:parent', NS)
    if not licenses and parent is not None and depth < 5:
        parts = [parent.findtext('m:' + field, '', NS) for field in ('groupId', 'artifactId', 'version')]
        if all(parts) and not any('${' in part for part in parts):
            inherited = pom_evidence(':'.join(parts), cache, depth + 1)
            result.update(licenses=inherited['licenses'], license_parent=':'.join(parts))
            if not source:
                result['source'] = inherited['source']
    return result


def audit_artifact(artifact, cache):
    coordinate = artifact['coordinate']
    result = {'coordinate': coordinate, **pom_evidence(coordinate, cache)}
    artifact_path = Path(artifact['file'])
    with artifact_path.open('rb') as binary:
        result['artifact_sha256'] = hashlib.file_digest(binary, 'sha256').hexdigest()
    result['native_libraries'] = []
    if zipfile.is_zipfile(artifact_path):
        with zipfile.ZipFile(artifact_path) as archive:
            result['native_libraries'] = [name for name in archive.namelist() if name.endswith('.so')]
    result['prohibited_group'] = bool(PROHIBITED.search(coordinate))
    # The 1.72 POMs omit licenses. Preserve the exact upstream release's MIT
    # notice in the APK and record its source and content hash as evidence.
    if coordinate in {f'org.bouncycastle:{name}:1.72' for name in
                      ('bcpkix-jdk15to18', 'bcprov-jdk15to18', 'bcutil-jdk15to18')}:
        notice = Path(__file__).resolve().parent.parent / 'app/src/main/assets/licenses/bouncycastle-1.72.html'
        digest = hashlib.sha256(notice.read_bytes()).hexdigest()
        if digest == '537e0e2946266c2b5049c12b1934543c301e6b45f283dce17ad64aad4ae2ccfc':
            result['licenses'] = [dict(name='MIT', url='https://github.com/bcgit/bc-java/blob/r1rv72/LICENSE.html')]
            result['source'] = 'https://github.com/bcgit/bc-java/tree/r1rv72'
            result['license_evidence_sha256'] = digest
    result['license_review_required'] = not any(
        FOSS_NAMES.search(license['name']) for license in result['licenses'])
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--inventory', type=Path, default=Path('build/reports/fdroid/runtime-dependencies.json'))
    parser.add_argument('--cargo-metadata', type=Path)
    parser.add_argument('--output', type=Path, default=Path('build/reports/fdroid/dependency-audit.json'))
    args = parser.parse_args()
    cache = Path.home() / '.gradle/caches/modules-2/files-2.1'
    artifacts = json.loads(args.inventory.read_text(encoding='utf-8'))
    with ThreadPoolExecutor(max_workers=6) as pool:
        maven = list(pool.map(lambda artifact: audit_artifact(artifact, cache), artifacts))
    cargo = []
    if args.cargo_metadata:
        metadata = json.loads(args.cargo_metadata.read_text(encoding='utf-8'))
        reachable = {node['id'] for node in metadata['resolve']['nodes']}
        cargo = [dict(name=p['name'], version=p['version'], license=p['license'],
                      source=p['repository'], license_file=p['license_file'])
                 for p in metadata['packages'] if p['id'] in reachable and p['source']]
    findings = [p['coordinate'] for p in maven if p['prohibited_group'] or p['license_review_required']]
    findings += [f"{p['name']}:{p['version']} missing SPDX license" for p in cargo if not p['license']]
    report = dict(maven=maven, cargo=cargo, findings=findings,
                  limitations=['Declared licenses do not prove all binary content matches its published source.',
                               'Bundled native code, assets, model downloads and license compatibility need manual review.',
                               'F-Droid maintainers decide acceptance.'])
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print(f'Recorded {len(maven)} runtime artifacts and {len(cargo)} Rust packages; {len(findings)} findings.')
    for finding in findings:
        print('REVIEW:', finding)
    raise SystemExit(bool(findings))


if __name__ == '__main__':
    main()
