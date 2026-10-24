"""One-time, non-overwriting intake from a clean git-archive of the fixed commit.

Usage: python docs/scripts/intake_frontend.py PATH_TO_EXTRACTED_ARCHIVE
Existing historical frontend/local-nodes, tests and packages/api are never touched.
"""
import hashlib
import json
import re
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
COMMIT = 'd2e94aca7b59659ff6b4ca15f03cf134ec7f0e1d'


def main():
    source = Path(sys.argv[1]).resolve()
    frontend = ROOT / 'frontend'
    packages = ['assets', 'extScaffold', 'i18n', 'modelIcons', 'nodeScaffold', 'toolScaffold',
                'providers', 'ffmpeg', 'file', 'tools/canvas']
    moves = [(source / 'apps/web/src', frontend / 'src'), (source / 'apps/web/public', frontend / 'public')]
    moves += [(source / 'packages' / name, frontend / 'packages' / name) for name in packages]
    moves += [(source / 'patches', frontend / 'patches')]
    files = [(source / 'apps/web/index.html', frontend / 'index.html'),
             (source / 'LICENSE', frontend / 'LICENSE.Toonflow'),
             (source / 'postcss.config.ts', frontend / 'postcss.config.ts')]
    new_files = [frontend / 'package.json', frontend / 'packages/contracts',
                 ROOT / 'docs/upstream/frontend-intake.json']
    # Inspect all destinations before the first mutation. Refuse any existing target.
    for _, destination in moves + files:
        if destination.exists():
            raise SystemExit(f'Refusing to overwrite {destination}')
        if not destination.resolve().is_relative_to(frontend.resolve()):
            raise SystemExit('Destination escaped frontend')
    for path in new_files:
        if path.exists():
            raise SystemExit(f'Refusing to overwrite {path}')
    for origin, _ in files + moves:
        if not origin.exists() and origin.name != 'public':
            raise SystemExit(f'Missing archive source: {origin}')
    records = []
    for origin, destination in moves:
        if not origin.exists():
            continue
        shutil.copytree(origin, destination)
        for path in origin.rglob('*'):
            if path.is_file():
                records.append({'source': path.relative_to(source).as_posix(),
                    'target': (destination / path.relative_to(origin)).relative_to(ROOT).as_posix(),
                    'sha256': hashlib.sha256(path.read_bytes()).hexdigest()})
    for origin, destination in files:
        with destination.open('xb') as output:
            output.write(origin.read_bytes())
        records.append({'source': origin.relative_to(source).as_posix(), 'target': destination.relative_to(ROOT).as_posix(),
                        'sha256': hashlib.sha256(origin.read_bytes()).hexdigest()})
    contracts = frontend / 'packages/contracts'
    contracts.mkdir()
    helpers = {'nodeMenu.ts': 'lib/nodeMenu.ts', 'documentFormats.ts': 'lib/documentFormats.ts',
        'agent-types.ts': 'agent/runtime/types.ts', 'mentionSources.ts': 'agent/mentionSources.ts',
        'singleAgent-types.ts': 'utils/singleAgent/types.ts'}
    for name, original in helpers.items():
        origin = source / 'apps/server/src' / original
        text = origin.read_text(encoding='utf-8').replace('./runtime/types', './agent-types')
        text = text.replace('../lib/documentFormats', './documentFormats').replace('../../agent/runtime/types', './agent-types')
        (contracts / name).write_text(text, encoding='utf-8')
        records.append({'source': origin.relative_to(source).as_posix(), 'target': (contracts / name).relative_to(ROOT).as_posix(),
                        'sha256': hashlib.sha256(origin.read_bytes()).hexdigest(), 'kind': 'browser-pure-helper-or-type'})
    (contracts / 'package.json').write_text(json.dumps({'name': '@huimeng/contracts', 'private': True, 'type': 'module',
        'exports': {'./' + name.removesuffix('.ts'): './' + name for name in helpers}}, indent=2) + '\n', encoding='utf-8')
    for path in (frontend / 'src').rglob('*'):
        if path.suffix not in ['.ts', '.vue']:
            continue
        text = path.read_text(encoding='utf-8').replace('@toonflow/server/agent/types', '@huimeng/contracts/agent-types')
        text = text.replace('@toonflow/server/singleAgent/types', '@huimeng/contracts/singleAgent-types')
        for name in ['nodeMenu', 'documentFormats', 'mentionSources']:
            text = text.replace('@toonflow/server/' + ('agent/' if name == 'mentionSources' else '') + name, '@huimeng/contracts/' + name)
        path.write_text(text, encoding='utf-8')
    manifest = json.loads((source / 'apps/web/package.json').read_text(encoding='utf-8'))
    manifest.update({'name': '@huimeng/web', 'version': '1.0.0-m1', 'packageManager': 'bun@1.4.2',
        'workspaces': ['packages/*', 'packages/tools/*'],
        'scripts': {'dev': 'vite --host 127.0.0.1', 'build': 'vite build', 'preview': 'vite preview --host 127.0.0.1',
            'typecheck': 'vue-tsc --noEmit', 'test': 'bun test tests', 'check': 'bun run typecheck && bun test tests'},
        'patchedDependencies': {'@vue-flow/core@1.48.2': 'patches/vueFlowCore.patch'}})
    manifest['dependencies'].pop('@toonflow/server')
    manifest['dependencies'].pop('@toonflow/tool-browser')
    manifest['dependencies'].update({'@huimeng/contracts': 'workspace:*', '@huimeng/api': 'workspace:*'})
    manifest['devDependencies'].update({'@types/bun': '^1.4.2', 'postcss': '^8.5.28', 'postcss-pxtorem': '^6.1.0',
        '@babel/parser': '^7.29.8', '@babel/types': '^7.29.8', '@vue/compiler-dom': '^3.5.42',
        '@vue/compiler-sfc': '^3.5.42', 'magic-string': '^0.30.21'})
    lock = json.loads(re.sub(r',\s*([}\]])', r'\1', (source / 'bun.lock').read_text(encoding='utf-8')))
    locked = {name: value[0].rsplit('@', 1)[-1] for name, value in lock['packages'].items()
        if isinstance(value, list) and value and isinstance(value[0], str) and not value[0].startswith('workspace:')}
    manifests = [(frontend / 'package.json', manifest)]
    manifests += [(frontend / 'packages' / name / 'package.json', json.loads((frontend / 'packages' / name / 'package.json').read_text(encoding='utf-8'))) for name in packages]
    for path, package in manifests:
        if path.parent != frontend:
            package.pop('devDependencies', None)
        for section in ['dependencies', 'devDependencies', 'peerDependencies']:
            for name, version in package.get(section, {}).items():
                if not version.startswith('workspace:') and name in locked:
                    package[section][name] = locked[name]
        path.write_text(json.dumps(package, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    path = frontend / 'packages/i18n/src/vite.ts'
    path.write_text(path.read_text(encoding='utf-8').replace(r'/\/apps\/web\/src\//', r'/\/frontend\/src\//'), encoding='utf-8')
    (ROOT / 'docs/upstream/frontend-intake.json').write_text(json.dumps({'commit': COMMIT,
        'source': 'git archive of the fixed commit; never a working-tree copy',
        'records': records}, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(f'Created {len(records)} source mappings; existing local-nodes/tests/api preserved.')


if __name__ == '__main__':
    main()
