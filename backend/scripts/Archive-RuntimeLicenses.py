"""Archive LICENSE/NOTICE texts embedded in the built backend's runtime JARs.

Run from backend/. This inventories evidence, not legal approval or a CVE audit.
"""
import hashlib
import io
import json
from pathlib import Path
import re
import zipfile


def main():
    root = Path(__file__).resolve().parents[1]
    jar = root / 'target/huimeng-api-1.0.0-SNAPSHOT.jar'
    target = root / 'licenses'
    target.mkdir(exist_ok=True)
    items = []
    with zipfile.ZipFile(jar) as outer:
        for name in sorted(outer.namelist()):
            if not name.startswith('BOOT-INF/lib/') or not name.endswith('.jar'):
                continue
            raw = outer.read(name)
            artifact = Path(name).name
            texts = []
            with zipfile.ZipFile(io.BytesIO(raw)) as inner:
                for entry in sorted(inner.namelist()):
                    if not re.match(r'^(LICENSE|NOTICE|COPYING)([._-].*)?$', Path(entry).name, re.I):
                        continue
                    if entry.endswith('/'):
                        continue
                    content = inner.read(entry)
                    local = Path('texts') / artifact / entry.replace('/', '__')
                    (target / local).parent.mkdir(parents=True, exist_ok=True)
                    (target / local).write_bytes(content)
                    texts.append({'sourceEntry': entry, 'path': local.as_posix(),
                                  'sha256': hashlib.sha256(content).hexdigest()})
            items.append({'runtimeJar': artifact, 'sha256': hashlib.sha256(raw).hexdigest(),
                          'embeddedLegalTexts': texts, 'textReviewPending': True})
    (target / 'runtime-inventory.json').write_text(json.dumps({'artifacts': items,
        'releaseApproval': False, 'securityReview': 'M4 pending'}, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'runtimeJars': len(items), 'legalTexts': sum(len(x['embeddedLegalTexts']) for x in items),
                      'missingEmbeddedTexts': [x['runtimeJar'] for x in items if not x['embeddedLegalTexts']]}))


if __name__ == '__main__':
    main()
