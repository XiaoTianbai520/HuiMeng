"""Opt-in Linux fixture checks against an isolated Spring/H2 validation instance.

Never point this at a team database or workspaces. Uses public synthetic credentials.
Does not start/stop services, connect MySQL, or remove persistent data.
"""
import argparse
import hashlib
import http.client
import json
import os
from pathlib import Path
import threading
import time
import urllib.parse


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', required=True)
    parser.add_argument('--fixture-root', required=True, type=Path)
    parser.add_argument('--report', required=True, type=Path)
    parser.add_argument('--isolated-h2-fixture', action='store_true', required=True)
    args = parser.parse_args()
    root = args.fixture_root.resolve()
    assert os.name == 'posix' and 'validation' in root.parts
    assert not (root / 'workspaces').exists(), 'Requires fresh isolated workspaces'
    origin = 'https://localhost:18443'
    password = 'synthetic-linux-file-acceptance-only'
    checks = []

    def check(name, condition):
        assert condition, name
        checks.append(name)

    def request(method, path, actor=None, body=None, headers=None):
        fields = {'Origin': origin, **(headers or {})}
        if actor:
            fields['Cookie'] = actor['cookie']
            if method != 'GET':
                fields['X-CSRF-Token'] = actor['csrf']
        data = None if body is None else json.dumps(body).encode()
        if data is not None:
            fields['Content-Type'] = 'application/json'
        target = urllib.parse.urlsplit(args.base)
        connection = http.client.HTTPConnection(target.hostname, target.port, timeout=10)
        connection.request(method, path, body=data, headers=fields)
        response = connection.getresponse()
        raw = response.read()
        if actor and response.headers.get('Set-Cookie'):
            actor['cookie'] = response.headers['Set-Cookie'].split(';')[0]
        # Test responses never expose the container/host workspace or outside marker.
        assert b'/validation/data' not in raw and b'outside-only-sentinel' not in raw
        result = (response.status, raw, response.headers)
        connection.close()
        return result

    def login(username):
        code, raw, headers = request('GET', '/api/v1/auth/csrf')
        assert code == 200
        actor = {'cookie': headers['Set-Cookie'].split(';')[0], 'csrf': json.loads(raw)['token']}
        code, raw, headers = request('POST', '/api/v1/auth/login', actor,
                                    {'username': username, 'password': password})
        assert code == 200
        actor['cookie'] = headers['Set-Cookie'].split(';')[0]
        code, raw, _ = request('GET', '/api/v1/auth/csrf', actor)
        actor['csrf'] = json.loads(raw)['token']
        return actor

    def create(path, body):
        code, raw, _ = request('POST', path, admin, body)
        assert code == 201, (code, raw)
        return json.loads(raw)

    admin = login('file-admin')
    member = create('/api/v1/admin/users', {'username': 'file-member', 'password': password, 'role': 'USER'})
    outsider = create('/api/v1/admin/users', {'username': 'file-outsider', 'password': password, 'role': 'USER'})
    # Map only the directory created by this fixture's own API call; never accept client paths.
    workspace = root / 'workspaces'
    before = set(workspace.iterdir()) if workspace.exists() else set()
    project = create('/api/v1/projects', {'name': 'Linux 文件验收'})
    base = (set(workspace.iterdir()) - before).pop()
    second = create('/api/v1/projects', {'name': '隔离项目'})
    create('/api/v1/projects/' + project['id'] + '/members', {'userId': member['id']})
    user = login('file-member')
    stranger = login('file-outsider')
    route = '/api/v1/projects/' + project['id'] + '/creative/workspaces/files/read'

    def read(path, actor=user, range_value=None, snapshot=False, extra=None):
        query = {'path': path, **(extra or {})}
        if snapshot:
            query['snapshot'] = 'true'
        return request('GET', route + '?' + urllib.parse.urlencode(query), actor,
                       headers={'Range': range_value} if range_value else None)

    payload = bytes(range(256)) * 4
    (base / 'media.bin').write_bytes(payload)
    check('anonymous 401', read('media.bin', None)[0] == 401)
    check('admin without membership 403', read('media.bin', admin)[0] == 403)
    check('outsider 403', read('media.bin', stranger)[0] == 403)
    code, raw, _ = request('GET', '/api/v1/projects', user)
    check('member list contains only authorized project',
          code == 200 and [p['id'] for p in json.loads(raw)['items']] == [project['id']])
    code, raw, _ = request('GET', route.replace(project['id'], second['id']) + '?path=media.bin', user)
    check('cross project download 403', code == 403)
    code, raw, headers = read('media.bin')
    check('binary download exact bytes and safe attachment', code == 200 and raw == payload
          and 'attachment' in headers['Content-Disposition'] and 'media.bin' in headers['Content-Disposition'])
    for value, expected in [('bytes=2-5', payload[2:6]), ('bytes=1018-', payload[1018:]),
                            ('bytes=-3', payload[-3:]), ('bytes=1022-9999', payload[1022:])]:
        code, raw, headers = read('media.bin', range_value=value)
        check('partial ' + value, code == 206 and raw == expected and headers['Content-Range'].startswith('bytes '))
    for value in ['bytes=1024-', 'bytes=3-2', 'bytes=0-1,3-4', 'bytes=-0', 'bytes=999999999999999999999-']:
        code, raw, headers = read('media.bin', range_value=value)
        check('invalid range ' + value, code == 416 and headers['Content-Range'] == 'bytes */1024')
    text = '绘梦安全读取\n'
    for encoding, raw in [('utf-8', text.encode()), ('utf-8-bom', b'\xef\xbb\xbf' + text.encode()),
                          ('utf-16le', b'\xff\xfe' + text.encode('utf-16le')),
                          ('utf-16be', b'\xfe\xff' + text.encode('utf-16be'))]:
        (base / 'snapshot.txt').write_bytes(raw)
        code, data, _ = read('snapshot.txt', snapshot=True)
        snapshot = json.loads(data)['data']
        check('snapshot ' + encoding, code == 200 and snapshot['text'] == text
              and snapshot['encoding'] == encoding and snapshot['revision'] == hashlib.sha256(raw).hexdigest())
    check('snapshot range rejected', read('snapshot.txt', snapshot=True, range_value='bytes=0-1')[0] == 400)
    (base / 'invalid.txt').write_bytes(b'\xff\xfe\x01')
    check('invalid encoded text rejected', read('invalid.txt', snapshot=True)[0] == 415)
    check('maxBytes enforced', read('media.bin', extra={'maxBytes': '1'})[0] == 400)
    check('missing file 404', read('absent.bin')[0] == 404)
    for path in ['../media.bin', '/etc/passwd', 'C:/secret', 'a\\b', '%2e%2e/media.bin']:
        check('path rejected ' + path, read(path)[0] == 400)
    check('claimed directory rejected', read('media.bin', extra={'directory': '/tmp'})[0] == 400)
    outside = root / 'outside'
    outside.mkdir()
    (outside / 'media.bin').write_bytes(b'outside-only-sentinel')
    (base / 'leaf-link').symlink_to(outside / 'media.bin')
    (base / 'parent-link').symlink_to(outside, target_is_directory=True)
    check('leaf symlink rejected', read('leaf-link')[0] == 400)
    check('parent symlink rejected', read('parent-link/media.bin')[0] == 400)
    folder, parked = base / 'racing', base / 'parked'
    folder.mkdir()
    (folder / 'media.bin').write_bytes(payload)
    stop = threading.Event()
    def toggle():
        while not stop.is_set():
            folder.rename(parked)
            folder.symlink_to(outside, target_is_directory=True)
            time.sleep(0.001)
            folder.unlink()
            parked.rename(folder)
    thread = threading.Thread(target=toggle)
    thread.start()
    try:
        for _ in range(100):
            code, raw, _ = read('racing/media.bin')
            assert code in (200, 400, 404), code
            if code == 200:
                assert raw == payload
    finally:
        stop.set()
        thread.join()
    check('100 parent symlink replacement attempts never escaped', True)
    # Also replace an ancestor of the configured root; canonicalizing it must not authorize outside.
    outer = root.parent / (root.name + '-ancestor-outside')
    parked_root = root.parent / (root.name + '-parked')
    assert not outer.exists() and not parked_root.exists()
    external_base = outer / 'workspaces' / base.name
    external_base.mkdir(parents=True)
    (external_base / 'media.bin').write_bytes(b'outside-only-sentinel')
    root.rename(parked_root)
    root.symlink_to(outer.name, target_is_directory=True)
    try:
        check('configured root ancestor symlink rejected', read('media.bin')[0] == 400)
    finally:
        root.unlink()
        parked_root.rename(root)
    code, raw, _ = request('GET', '/api/v1/admin/configuration/export', user)
    check('member cannot export admin configuration', code == 403)
    secret = 'synthetic-linux-export-secret'
    code, _, _ = request('PUT', '/api/v1/admin/providers/acceptance/secret', admin, {'apiKey': secret})
    assert code == 204
    code, raw, _ = request('GET', '/api/v1/admin/configuration/export', admin)
    export = json.loads(raw)
    check('admin export contains status and no secrets/options/paths', code == 200
          and any(p['providerId'] == 'acceptance' and p['configured'] for p in export['providers'])
          and all(token not in raw for token in [secret.encode(), b'apiKey', b'options', b'encrypted', b'workspace']))
    code, _, _ = request('DELETE', '/api/v1/projects/' + project['id'] + '/members/' + member['id'], admin)
    check('removed member immediately loses download', code == 204 and read('media.bin')[0] == 403)
    report = {'passed': True, 'checks': checks, 'count': len(checks), 'raceRequests': 100,
              'database': 'isolated H2; no existing MySQL connection', 'browserAcceptance': False}
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({'passed': True, 'count': len(checks), 'raceRequests': 100}))


if __name__ == '__main__':
    main()
