"""Validate OpenAPI and the fixed-source route mapping without network access."""
import json
import re
from collections import Counter
from pathlib import Path
import yaml
from openapi_spec_validator import validate_spec
from jsonschema import Draft202012Validator

ROOT = Path(__file__).resolve().parents[2]


def main():
    api = ROOT / 'docs/api'
    spec = yaml.safe_load((api / 'openapi.yaml').read_text(encoding='utf-8'))
    validate_spec(spec)
    source = json.loads((api / 'upstream/route-details.json').read_text(encoding='utf-8-sig'))['routes']
    mapped = json.loads((api / 'route-map.json').read_text(encoding='utf-8'))['routes']
    assert len(mapped) == 127
    assert {r['id'] for r in mapped} == {r['id'] for r in source}
    originals = {r['id']: r for r in source}
    operation_ids = set()
    implemented = set(json.loads((ROOT / 'backend/api-implementation.json').read_text(encoding='utf-8'))['operations'])
    for path, methods in spec['paths'].items():
        assert '*' not in path and '{upstreamPath}' not in path
        for method, operation in methods.items():
            assert operation['operationId'] not in operation_ids
            operation_ids.add(operation['operationId'])
            assert operation['x-implemented'] is (operation['operationId'] in implemented)
            assert operation['x-roles'] and operation['x-acceptance'] and operation['x-requirements']
            assert set(re.findall(r'\{(\w+)\}', path)) == {
                p['name'] for p in operation['parameters'] if p['in'] == 'path' and p['required']}
            if method not in ['get', 'head']:
                assert any(p['name'] == 'X-CSRF-Token' and p['required'] for p in operation['parameters'])
    for route in mapped:
        original = originals[route['id']]
        assert route['sourceMethod'] == original['method'] and route['sourcePath'] == original['path']
        assert route['sourceSha256'] == original['sourceSha256']
        assert route['publicEnabled'] is False
        assert route['roles'] and route['acceptance'] and route['requirements']
        if route['contractStatus'] in ['excluded', 'internal']:
            assert route['targetPath'] is None
        if route['contractStatus'] == 'frozen':
            assert route['targetPath'] in spec['paths']
        for block in route['request']:
            assert not any(f.get('name') in ['directory', 'absolutePath'] for f in block['fields'])
    assert implemented <= operation_ids
    assert len(implemented) == 41
    counts = Counter(r['contractStatus'] for r in mapped)
    assert counts['excluded'] == 25 and counts['internal'] == 2
    for name, schema in spec['components']['schemas'].items():
        Draft202012Validator.check_schema(schema)
    # Regression: current Qwen specs survive, but old provider identifiers are not defaults.
    media = {'mediaType': 'image', 'providerId': 'qwenImage21Local', 'modelId': 'qwenImage21Text',
             'prompt': '合成契约样例', 'size': '2K', 'ratio': '16:9'}
    validator = Draft202012Validator({'$ref': '#/components/schemas/MediaGenerate', **spec})
    validator.validate(media)
    for bad in [dict(media, directory='C:/private'), dict(media, absolutePath='/etc/passwd'), dict(media, mediaType='other')]:
        assert not validator.is_valid(bad)
    print(json.dumps({'passed': True, 'operations': len(operation_ids), 'routes': len(mapped),
                      'states': dict(counts), 'businessAcceptance': 'not run'}, ensure_ascii=False))


if __name__ == '__main__':
    main()
