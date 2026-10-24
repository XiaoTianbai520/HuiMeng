"""Generate minimal browser route metadata; never grants a server-side permission."""
import json
import sys
from pathlib import Path
import yaml

ROOT = Path(__file__).resolve().parents[2]


def ts_type(schema):
    if '$ref' in schema:
        return schema['$ref'].rsplit('/', 1)[-1]
    if 'const' in schema:
        return json.dumps(schema['const'], ensure_ascii=False)
    if 'enum' in schema:
        return ' | '.join(json.dumps(value, ensure_ascii=False) for value in schema['enum'])
    if 'oneOf' in schema:
        return '(' + ' | '.join(ts_type(value) for value in schema['oneOf']) + ')'
    kind = schema.get('type')
    if kind == 'object':
        if not schema.get('properties'):
            return 'Record<string, unknown>'
        required = schema.get('required', [])
        return '{ ' + ' '.join(json.dumps(key) + ('' if key in required else '?') + ': ' + ts_type(value) + ';'
            for key, value in schema['properties'].items()) + ' }'
    if kind == 'array':
        return '(' + ts_type(schema['items']) + ')[]'
    return {'string': 'string', 'integer': 'number', 'number': 'number', 'boolean': 'boolean', 'null': 'null'}.get(kind, 'unknown')


def main():
    source = json.loads((ROOT / 'docs/api/route-map.json').read_text(encoding='utf-8'))
    spec = yaml.safe_load((ROOT / 'docs/api/openapi.yaml').read_text(encoding='utf-8'))
    mapping = {'routes': [{key: route[key] for key in ['id', 'sourceMethod', 'sourcePath', 'targetPath', 'disposition']}
                          for route in source['routes'] if route['targetPath']]}
    mapping['platform'] = [{'method': method.upper(), 'path': path} for path, methods in spec['paths'].items() for method in methods]
    text = json.dumps(mapping, ensure_ascii=False, indent=2) + '\n'
    types = '// Generated from docs/api/openapi.yaml. Run docs/scripts/sync_frontend_contracts.py.\n'
    types += '\n'.join('export type ' + name + ' = ' + ts_type(schema) + ';' for name, schema in spec['components']['schemas'].items()) + '\n'
    for target, content in [(ROOT / 'frontend/packages/api/route-map.json', text),
                            (ROOT / 'frontend/packages/contracts/platform.ts', types)]:
        if '--check' in sys.argv:
            if target.read_text(encoding='utf-8') != content:
                raise SystemExit('Frontend contract artifact is stale: ' + str(target.relative_to(ROOT)))
        else:
            target.write_text(content, encoding='utf-8')
    print('Frontend contract metadata verified' if '--check' in sys.argv else 'Frontend contract metadata generated')


if __name__ == '__main__':
    main()
