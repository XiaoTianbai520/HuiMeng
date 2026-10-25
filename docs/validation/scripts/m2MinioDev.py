"""Normal local Spring + independent MySQL + real MinIO acceptance.

Run from repository root after Migrate-MySqlDev.py and app-table grants.
Starts a fresh loopback process at 18088, never touches an existing IDEA process.
Credentials remain in ignored protected files and memory; report contains checks.
"""
import hashlib
import http.client
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import time
from urllib.parse import quote

ROOT = Path(__file__).resolve().parents[3]
BACKEND = ROOT / 'backend'
PORT = 18088
CHECKS = []


def check(condition, name):
    if not condition:
        raise RuntimeError('Acceptance check failed: ' + name)
    CHECKS.append(name)


class Session:
    def __init__(self, origin):
        self.cookies = {}
        self.origin = origin

    def call(self, method, path, data=None, binary=False, csrf=None, headers=None):
        conn = http.client.HTTPConnection('127.0.0.1', PORT, timeout=90)
        request_headers = {'Cookie': '; '.join(k + '=' + v for k, v in self.cookies.items())}
        if method not in ['GET', 'HEAD']:
            request_headers['Origin'] = self.origin
        if csrf:
            request_headers['X-CSRF-Token'] = csrf
        if data is not None:
            request_headers['Content-Type'] = 'application/octet-stream' if binary else 'application/json'
        if headers:
            request_headers.update(headers)
        conn.request(method, path, data if binary or data is None else json.dumps(data).encode(), request_headers)
        response = conn.getresponse()
        for name, value in response.getheaders():
            if name.lower() == 'set-cookie':
                key, value = value.split(';', 1)[0].split('=', 1)
                self.cookies[key] = value
        raw = response.read()
        result = (response.status, dict(response.getheaders()), raw)
        conn.close()
        return result

    def write(self, method, path, data=None, binary=False):
        status, _, raw = self.call('GET', '/api/v1/auth/csrf')
        check(status == 200, 'csrfFetched')
        return self.call(method, path, data, binary, json.loads(raw)['token'])

    def login(self, username, password):
        status, _, raw = self.write('POST', '/api/v1/auth/login', {'username': username, 'password': password})
        check(status == 200, 'normalApplicationLogin')
        return json.loads(raw)['user']['id']


def main():
    settings = dict(line.split('=', 1) for line in (BACKEND / '.runtime/mysql-dev/application.properties').read_text().splitlines()
                    if '=' in line and not line.startswith('#'))
    with socket.socket() as probe:
        if probe.connect_ex(('127.0.0.1', PORT)) == 0:
            raise SystemExit('Validation port occupied; existing process unchanged.')
    log = (BACKEND / '.runtime/object-storage/acceptance.log').open('wb')
    process = subprocess.Popen([shutil.which('java'), '-jar', 'target/huimeng-api-1.0.0-SNAPSHOT.jar',
                                '--spring.profiles.active=local', '--server.port=' + str(PORT)],
                               cwd=BACKEND, stdout=log, stderr=subprocess.STDOUT)
    admin = Session(settings['huimeng.public-origin'])
    member = Session(settings['huimeng.public-origin'])
    projects, media, member_id = [], [], None
    cleanup_results = []
    try:
        for _ in range(60):
            if process.poll() is not None:
                raise RuntimeError('Normal application startup failed; inspect protected log.')
            try:
                if admin.call('GET', '/api/v1/auth/csrf')[0] == 200:
                    break
            except (OSError, http.client.HTTPException):
                pass
            time.sleep(1)
        else:
            raise RuntimeError('Normal application did not become ready.')
        admin_id = admin.login('dev-admin', (BACKEND / '.runtime/mysql-dev/bootstrap-password').read_text().strip())
        for label in ['own', 'other']:
            status, _, raw = admin.write('POST', '/api/v1/projects', {'name': 'MinIO synthetic ' + label + ' ' + secrets.token_hex(3)})
            check(status == 201, 'syntheticProjectCreated')
            project = json.loads(raw)['id']
            projects.append(project)
        own, other = projects
        path = '/api/v1/projects/' + own + '/media'
        check(admin.call('GET', path)[0] == 403, 'adminWithoutMembershipDenied')
        for project in projects:
            check(admin.write('POST', '/api/v1/projects/' + project + '/members', {'userId': admin_id})[0] == 201, 'adminMembershipAdded')
        username, password = 'minio-check-' + secrets.token_hex(4), secrets.token_urlsafe(24)
        status, _, raw = admin.write('POST', '/api/v1/admin/users', {'username': username, 'password': password})
        check(status == 201, 'syntheticMemberCreated')
        member_id = json.loads(raw)['id']
        member.login(username, password)
        check(member.call('GET', path)[0] == 403, 'nonMemberListDenied')
        check(admin.write('POST', '/api/v1/projects/' + own + '/members', {'userId': member_id})[0] == 201, 'memberAdded')
        payload = (b'HuiMeng MinIO synthetic media\x00\xff\n' * 65536)
        status, _, raw = member.write('POST', path + '?filename=' + quote('验证素材.bin'), payload, True)
        check(status == 201, 'realMinioUpload')
        value = json.loads(raw)
        media_id = value['id']
        media.append((own, media_id))
        check(value['sha256'] == hashlib.sha256(payload).hexdigest() and value['byteSize'] == len(payload), 'uploadedHashAndSizeMatch')
        check(not any(field in value for field in ['objectKey', 'bucket', 'endpoint', 'secret']), 'metadataDoesNotExposeStorage')
        download = path + '/' + media_id + '/content'
        status, headers, raw = member.call('GET', download)
        check(status == 200 and raw == payload and int(headers['Content-Length']) == len(payload), 'windowsDownloadFullBytes')
        status, headers, raw = member.call('GET', download, headers={'Range': 'bytes=3-12'})
        check(status == 206 and raw == payload[3:13] and headers['Content-Range'] == f'bytes 3-12/{len(payload)}', 'realMinioRange')
        check(member.call('GET', download, headers={'Range': 'bytes=999999999-'})[0] == 416, 'invalidRange416')
        check(admin.call('GET', '/api/v1/projects/' + other + '/media/' + media_id + '/content')[0] == 404, 'crossProjectMediaIdDenied')
        check(member.call('POST', path + '?filename=blocked.bin', b'x', True)[0] == 403, 'uploadWithoutCsrfDenied')
        check(member.write('POST', path + '?filename=..%2Fbad', b'x', True)[0] == 400, 'filenameTraversalDenied')
        check(member.write('POST', path + '?filename=safe.bin&objectKey=other', b'x', True)[0] == 400, 'clientObjectKeyDenied')
        check(admin.write('DELETE', '/api/v1/projects/' + own + '/members/' + member_id)[0] == 204, 'memberRemoved')
        check(member.call('GET', download)[0] == 403, 'downloadDeniedImmediatelyAfterRemoval')
        check(admin.write('DELETE', path + '/' + media_id)[0] == 204, 'realMinioDelete')
        media.clear()
        check(admin.call('GET', download)[0] == 404, 'deletedMediaNotFound')
        check(json.loads(admin.call('GET', path)[2])['items'] == [], 'emptyReadyListAfterDelete')
        # Empty objects exercise the SDK stream boundary separately.
        status, _, raw = admin.write('POST', path + '?filename=empty.bin', b'', True)
        check(status == 201, 'realMinioZeroByteUpload')
        empty_id = json.loads(raw)['id']
        media.append((own, empty_id))
        check(admin.call('GET', path + '/' + empty_id + '/content')[2] == b'', 'realMinioZeroByteDownload')
        check(admin.write('DELETE', path + '/' + empty_id)[0] == 204, 'zeroByteRemoved')
        media.clear()
    finally:
        for project, media_id in media:
            try:
                cleanup_results.append(admin.write('DELETE', '/api/v1/projects/' + project + '/media/' + media_id)[0] in [204, 404])
            except Exception:
                cleanup_results.append(False)
        for project in projects:
            try:
                if member_id:
                    cleanup_results.append(admin.write('DELETE', '/api/v1/projects/' + project + '/members/' + member_id)[0] == 204)
                cleanup_results.append(admin.write('PATCH', '/api/v1/projects/' + project, {'status': 'ARCHIVED'})[0] == 200)
            except Exception:
                cleanup_results.append(False)
        if member_id:
            try:
                cleanup_results.append(admin.write('PATCH', '/api/v1/admin/users/' + member_id, {'status': 'DISABLED'})[0] == 200)
            except Exception:
                cleanup_results.append(False)
        if os.name == 'nt':
            # Windows javapath launcher can spawn a JVM child; stop the owned tree.
            subprocess.run(['taskkill', '/PID', str(process.pid), '/T', '/F'], capture_output=True)
        else:
            process.terminate()
        try:
            process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            process.kill()
        log.close()
    check(all(cleanup_results), 'syntheticAccountsAndProjectsCleaned')
    for _ in range(10):
        with socket.socket() as probe:
            if probe.connect_ex(('127.0.0.1', PORT)) != 0:
                break
        time.sleep(0.2)
    else:
        raise RuntimeError('Validation JVM remains alive; inspect owned process tree.')
    check(True, 'ownedValidationPortClosed')
    report = {'passed': True, 'date': '2026-10-25', 'normalSpringMySqlAndMinio': True,
              'checks': CHECKS, 'credentialValuesRecorded': False, 'canonicalDeploymentChanged': False}
    (BACKEND / 'target/minio-http-result.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({'passed': True, 'checks': len(CHECKS), 'productionChanged': False}))


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        raise SystemExit('MinIO acceptance failed: ' + type(error).__name__ + '; inspect protected evidence, do not print credentials.')
