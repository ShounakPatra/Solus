"""Compare unsigned CI/F-Droid APKs, including ZIP packaging bytes."""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile


def compare(first, second):
    digest = lambda data: hashlib.sha256(data).hexdigest()
    with zipfile.ZipFile(first) as a, zipfile.ZipFile(second) as b:
        left, right = set(a.namelist()), set(b.namelist())
        changed = sorted(name for name in left & right if a.read(name) != b.read(name))
    first_hash = digest(Path(first).read_bytes())
    second_hash = digest(Path(second).read_bytes())
    return dict(identical=first_hash == second_hash, ci_sha256=first_hash,
                fdroid_sha256=second_hash, only_ci=sorted(left - right),
                only_fdroid=sorted(right - left), different_entries=changed)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('ci', type=Path)
    parser.add_argument('fdroid', type=Path)
    parser.add_argument('--report', type=Path, required=True)
    args = parser.parse_args()
    result = compare(args.ci, args.fdroid)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    output = json.dumps(result, indent=2)
    args.report.write_text(output + '\n', encoding='utf-8')
    print(output)
    raise SystemExit(0 if result['identical'] else 1)
