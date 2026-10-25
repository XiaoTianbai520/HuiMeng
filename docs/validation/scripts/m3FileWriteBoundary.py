"""Opt-in real Linux/H2 save checks, never against an existing business instance."""
import argparse
import concurrent.futures
import hashlib
import http.client
import json
import os
from pathlib import Path
import threading
from urllib.parse import urlencode


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--fixture-root', required=True, type=Path)
    parser.add_argument('--report', required=True, type=Path)
    parser.add_argument('--isolated-h2-fixture', action='store_true', required=True)
    args = parser.parse_args()
    root = args.fixture_root.resolve()
    assert os.name == 'posix' and 'validation' in root.parts
    assert not (root / 'workspaces').exists(), 'Fresh isolated fixture required'
    origin = 'https://localhost:18443'
    checks = []

    def check(name, ok):
        assert ok, name
        checks.append(name)

    def call(method, path, actor=None, body=None, headers=None):
        fields = {'Origin': origin, **(headers or {})}
        if actor:
            fields['Cookie'] = actor['cookie']
            if method != 'GET':
                fields.setdefault('X-CSRF-Token', actor['csrf'])
        if isinstance(body, dict):
            body = json.dumps(body).encode()
            fields['Content-Type'] = 'application/json'
        connection = http.client.HTTPConnection('127.0.0.1', 18090, timeout=20)
        connection.request(method, path, body, fields)
        response = connection.getresponse()
        raw, response_headers = response.read(), response.headers
        if actor and response_headers.get('Set-Cookie'):
            actor['cookie'] = response_headers['Set-Cookie'].split(';')[0]
        connection.close()
        assert str(root).encode() not in raw and b'outside-only-marker' not in raw
        return response.status, raw, response_headers

    def login(name):
        status, raw, headers = call('GET', '/api/v1/auth/csrf')
        assert status == 200
        actor = {'cookie': headers['Set-Cookie'].split(';')[0], 'csrf': json.loads(raw)['token']}
        status, _, headers = call('POST', '/api/v1/auth/login', actor,
                                  {'username': name, 'password': 'synthetic-m3-files-only'})
        assert status == 200
        actor['cookie'] = headers['Set-Cookie'].split(';')[0]
        actor['csrf'] = json.loads(call('GET', '/api/v1/auth/csrf', actor)[1])['token']
        return actor

    admin = login('m3-admin')

    def create(path, body):
        status, raw, _ = call('POST', path, admin, body)
        assert status == 201, (status, raw)
        return json.loads(raw)

    user_data = create('/api/v1/admin/users', {'username': 'm3-member', 'password': 'synthetic-m3-files-only', 'role': 'USER'})
    create('/api/v1/admin/users', {'username': 'm3-outsider', 'password': 'synthetic-m3-files-only', 'role': 'USER'})
    project = create('/api/v1/projects', {'name': 'M3 安全保存'})['id']
    other = create('/api/v1/projects', {'name': '隔离项目'})['id']
    create('/api/v1/projects/' + project + '/members', {'userId': user_data['id']})
    user, outsider = login('m3-member'), login('m3-outsider')
    workspaces = list((root / 'workspaces').iterdir())
    assert len(workspaces) == 2
    route = '/api/v1/projects/' + project + '/creative/workspaces/files/'

    def save(path, data=b'', actor=user, **query):
        return call('PUT', route + 'write?' + urlencode({'path': path, **query}), actor, data,
                    {'Content-Type': 'application/octet-stream'})

    def snapshot(path):
        status, raw, _ = call('GET', route + 'read?' + urlencode({'path': path, 'snapshot': 'true'}), user)
        assert status == 200, (status, raw)
        return json.loads(raw)['data']

    check('anonymousRead401', call('GET', route+'read?path=a.txt')[0] == 401)
    check('anonymousWriteCsrf403', save('a.txt', actor=None, exclusive='true')[0] == 403)
    check('adminNonMember403', save('a.txt', actor=admin, exclusive='true')[0] == 403)
    check('outsider403', save('a.txt', actor=outsider, exclusive='true')[0] == 403)
    check('crossProject403', call('PUT', route.replace(project, other)+'write?path=a.txt&exclusive=true', user, b'', {'Content-Type':'application/octet-stream'})[0] == 403)
    check('csrf403', call('PUT', route+'write?path=a.txt&exclusive=true', user, b'', {'Content-Type':'application/octet-stream','X-CSRF-Token':'wrong'})[0] == 403)
    check('missingPrecondition400', save('a.txt')[0] == 400)
    check('bothPreconditions400', save('a.txt', exclusive='true', expectedRevision='a'*64)[0] == 400)
    check('invalidRevision400', save('a.txt', expectedRevision='bad')[0] == 400)
    check('privateDirectoryRejected', save('a.txt', exclusive='true', directory='/tmp')[0] == 400)
    check('reservedTemporaryPathRejected', save('.huimeng-write-guess.tmp', exclusive='true')[0] == 400)
    for path in ('../escape.txt', '/tmp/escape.txt', 'C:/escape.txt', 'a\\b', '%2e%2e/x'):
        check('pathRejected:' + path, save(path, exclusive='true')[0] == 400)
    text = '绘梦剧本\r\n角色：小白 😀\n'
    original = text.encode()
    status, raw, _ = save('剧本.md', original, exclusive='true', encoding='utf-8')
    revision = hashlib.sha256(original).hexdigest()
    check('createOriginalUtf8', status == 200 and json.loads(raw)['data']['revision'] == revision)
    check('readOriginalSnapshot', snapshot('剧本.md') == {'text':text, 'encoding':'utf-8', 'revision':revision})
    base = next(p for p in workspaces if (p/'剧本.md').exists())
    check('bytesAndLineEndingsPreserved', (base/'剧本.md').read_bytes() == original)
    status, raw, _ = save('剧本.md', b'overwrite', exclusive='true')
    check('exclusiveExisting409', status == 409 and json.loads(raw)['details']['currentRevision'] == revision)
    check('exclusiveDidNotOverwrite', (base/'剧本.md').read_bytes() == original)
    gate = threading.Barrier(2)

    def competing(data):
        gate.wait()
        return save('剧本.md', data, expectedRevision=revision)

    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as workers:
        jobs = [workers.submit(competing, data) for data in (b'winner-one', b'winner-two')]
        results = [job.result() for job in jobs]
    check('twoConcurrentWritersExactlyOneSuccess', sorted(r[0] for r in results) == [200,409])
    winning = (base/'剧本.md').read_bytes()
    next_revision = hashlib.sha256(winning).hexdigest()
    check('conflictReturnsActualRevision', json.loads(next(r[1] for r in results if r[0] == 409))['details']['currentRevision'] == next_revision)
    check('staleWrite409AndOriginalSafe', save('剧本.md', b'stale', expectedRevision=revision)[0] == 409 and (base/'剧本.md').read_bytes() == winning)
    status, raw, _ = save('missing.txt', expectedRevision='b'*64)
    check('deletedTargetConflictExplicit', status == 409 and json.loads(raw)['details']['currentRevision'] is None)
    for encoding, bom, codec in [('utf-8-bom',b'\xef\xbb\xbf','utf-8'),('utf-16le',b'\xff\xfe','utf-16le'),('utf-16be',b'\xfe\xff','utf-16be')]:
        name, expected = encoding+'.txt', bom+text.encode(codec)
        status, _, _ = save(name, original, exclusive='true', encoding=encoding)
        check('createEncoding:'+encoding, status == 200 and (base/name).read_bytes() == expected)
        snap = snapshot(name)
        check('snapshotEncoding:'+encoding, snap['text'] == text and snap['encoding'] == encoding and snap['revision'] == hashlib.sha256(expected).hexdigest())
        status, _, _ = save(name, b'replaced', expectedRevision=snap['revision'], encoding=encoding)
        check('saveEncoding:'+encoding, status == 200 and (base/name).read_bytes() == bom+'replaced'.encode(codec))
    check('emptyFile', save('empty.txt', exclusive='true')[0] == 200 and snapshot('empty.txt')['revision'] == hashlib.sha256(b'').hexdigest())
    check('malformedUtf8Rejected', save('invalid.txt', b'\xff', exclusive='true', encoding='utf-16le')[0] == 415 and not (base/'invalid.txt').exists())
    check('missingParentNoImplicitCreation', save('absent/a.txt', b'new', exclusive='true')[0] == 404 and not (base/'absent').exists())
    check('declaredSizeRejectedBeforeRead', call('PUT', route+'write?path=large.txt&exclusive=true', user, b'', {'Content-Type':'application/octet-stream','Content-Length':str(8*1024*1024+1)})[0] == 413)
    outside = root/'outside.txt'
    outside.write_text('outside-only-marker', encoding='utf-8')
    (base/'leaf-link.txt').symlink_to(outside)
    check('leafSymlinkDenied', save('leaf-link.txt', b'bad', exclusive='true')[0] == 400)
    (base/'parent-link').symlink_to(root, target_is_directory=True)
    check('parentSymlinkDenied', save('parent-link/outside.txt', b'bad', exclusive='true')[0] == 400)
    check('outsideUnchanged', outside.read_text() == 'outside-only-marker')
    pending = http.client.HTTPConnection('127.0.0.1', 18090, timeout=20)
    pending.putrequest('PUT', route+'write?'+urlencode({'path':'剧本.md','expectedRevision':next_revision}))
    for key, value in {'Content-Type':'application/octet-stream','Content-Length':'2','Cookie':user['cookie'],'Origin':origin,'X-CSRF-Token':user['csrf']}.items():
        pending.putheader(key, value)
    pending.endheaders()
    pending.send(b'x')
    check('revokeMember', call('DELETE', '/api/v1/projects/'+project+'/members/'+user_data['id'], admin)[0] == 204)
    pending.send(b'y')
    response = pending.getresponse()
    check('revokedBeforeCommit403', response.status == 403)
    response.read()
    pending.close()
    check('revocationDidNotOverwrite', (base/'剧本.md').read_bytes() == winning)
    check('temporaryFilesCleaned', not list(base.glob('.huimeng-write-*')))
    check('removedMemberReadDenied', call('GET', route+'read?path=empty.txt', user)[0] == 403)
    result = {'passed':True,'checks':checks,'checkCount':len(checks),'scope':'real Spring/H2 HTTP and Linux secure file I/O','productionDeploymentChanged':False,'businessDatabaseUsed':False,'credentialsIncluded':False}
    args.report.write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print(json.dumps({'passed':True,'checks':len(checks)}))


if __name__ == '__main__':
    main()
