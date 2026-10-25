"""Validate the explicitly started synthetic browser fixture at loopback 18086.

Run after its real Qwen image and browser upload complete. Removes its member at
the end for the negative browser check; never targets the normal MySQL app.
"""
import hashlib
import json
from pathlib import Path
import struct
import uuid
from urllib.parse import quote
import m2MinioDev as fixture

ROOT = Path(__file__).resolve().parents[3]
CHECKS = []


def check(ok, name):
    if not ok:
        raise RuntimeError('Acceptance check failed: ' + name)
    CHECKS.append(name)


def write(session, method, path, body=None, headers=None):
    status, _, raw = session.call('GET', '/api/v1/auth/csrf')
    check(status == 200, 'csrf')
    return session.call(method, path, body, csrf=json.loads(raw)['token'], headers=headers)


def main():
    fixture.PORT = 18086
    admin, member, outsider = [fixture.Session('http://localhost:5187') for _ in range(3)]
    admin.login('ui-admin', 'M2-browser-fixture-only')
    member_id = member.login('ui-member', 'M2-browser-fixture-only')
    outsider_id = outsider.login('ui-outsider', 'M2-browser-fixture-only')
    own = json.loads(member.call('GET', '/api/v1/projects')[2])['items']
    check(len(own) == 1 and own[0]['name'] == '浏览器验收项目', 'memberProjectFilter')
    project = own[0]['id']
    other = next(p['id'] for p in json.loads(admin.call('GET', '/api/v1/projects')[2])['items'] if p['name'] == '仅管理员管理的项目')
    base = '/api/v1/projects/' + project
    media = json.loads(member.call('GET', base + '/media')[2])['items']
    generated = next(item for item in media if item['filename'].startswith('generated-'))
    check(any(item['filename'] == 'M0-sample.png' for item in media), 'browserUploadPersisted')
    media_id = generated['id']
    run_id = generated['filename'][10:-4]
    status, _, raw = member.call('GET', base + '/runs/' + run_id)
    run = json.loads(raw)
    check(status == 200 and run['state'] == 'SUCCEEDED' and not run['capacityHeld'], 'realGenerationSucceededAndSlotReleased')
    path = base + '/media/' + media_id + '/content'
    status, _, content = member.call('GET', path)
    check(status == 200 and hashlib.sha256(content).hexdigest() == generated['sha256'], 'authorizedDownloadHash')
    check(content[:8] == b'\x89PNG\r\n\x1a\n' and struct.unpack('>II', content[16:24]) == (1024, 1024), 'real1024Png')
    status, headers, part = member.call('GET', path, headers={'Range': 'bytes=0-7'})
    check(status == 206 and part == content[:8] and headers['Content-Range'] == f'bytes 0-7/{len(content)}', 'realObjectRange')
    virtual = base + '/creative/workspaces/files/read?path=' + quote('.huimeng-media/' + media_id + '.png')
    check(member.call('GET', virtual)[2] == content, 'relativePathReadMapsToProjectObject')
    check(outsider.call('GET', path)[0] == 403, 'nonMemberDownloadDenied')
    check(admin.call('GET', '/api/v1/projects/' + other + '/media')[0] == 403, 'adminNonMemberDenied')
    check(write(admin, 'POST', '/api/v1/projects/' + other + '/members', {'userId': outsider_id})[0] == 201, 'otherProjectMemberAdded')
    check(outsider.call('GET', '/api/v1/projects/' + other + '/media/' + media_id + '/content')[0] == 404, 'crossProjectMediaIdDenied')
    check(member.call('GET', base + '/creative/workspaces/files/read?path=..%2Fprivate')[0] == 400, 'traversalDenied')
    input_body = {'providerId': 'qwenImage21Local', 'modelId': 'qwenImage21Text', 'mediaType': 'image', 'prompt': 'synthetic rejected request', 'size': '1K', 'ratio': '1:1'}
    approval = {'providerId': 'qwenImage21Local', 'modelId': 'qwenImage21Text', 'enabled': False}
    try:
        check(write(admin, 'PUT', '/api/v1/admin/models/approval', approval)[0] == 204, 'approvalWithdrawn')
        models = json.loads(member.call('GET', '/api/v1/models')[2])['items']
        check(not any(m['modelId'] == 'qwenImage21Text' for m in models), 'withdrawnModelNotSelectable')
        check(write(member, 'POST', base + '/creative/ai/media/generate', input_body, {'Idempotency-Key': str(uuid.uuid4())})[0] == 403, 'staleClientModelInvocationDenied')
    finally:
        approval['enabled'] = True
        write(admin, 'PUT', '/api/v1/admin/models/approval', approval)
    check(write(member, 'PUT', '/api/v1/admin/providers/m2-denied/secret', {'apiKey': 'synthetic-not-a-real-key'})[0] == 403, 'memberSecretManagementDenied')
    check(member.call('GET', '/api/v1/admin/configuration/export')[0] == 403, 'memberExportDenied')
    secret = 'm2-synthetic-secret-' + uuid.uuid4().hex
    check(write(admin, 'PUT', '/api/v1/admin/providers/m2-validation-' + uuid.uuid4().hex[:6] + '/secret', {'apiKey': secret})[0] == 204, 'adminWriteOnlySecretConfigured')
    safe_bodies = [admin.call('GET', endpoint)[2] for endpoint in ['/api/v1/admin/providers', '/api/v1/admin/models', '/api/v1/admin/configuration/export']]
    safe_bodies += [member.call('GET', base + '/media')[2], member.call('GET', '/api/v1/projects')[2]]
    check(all(secret.encode() not in body for body in safe_bodies), 'keyNotInResponseOrExport')
    check(all(b'/data/' not in body and b'C:\\Users\\' not in body and b'/opt/' not in body for body in safe_bodies), 'serverPathsNotInResponses')
    check(write(admin, 'DELETE', base + '/members/' + member_id)[0] == 204, 'membershipRemoved')
    check(member.call('GET', path)[0] == 403 and member.call('GET', virtual)[0] == 403, 'removedMemberCannotDownloadEitherPath')
    check(member.call('GET', base + '/media')[0] == 403, 'removedMemberCannotList')
    check(write(member, 'POST', base + '/creative/ai/media/generate', input_body, {'Idempotency-Key': str(uuid.uuid4())})[0] == 403, 'removedMemberCannotGenerate')
    evidence = ROOT / 'docs/validation/evidence'
    (evidence / 'm2-web-comfy-output.png').write_bytes(content)
    report = {'passed': True, 'scope': 'real Spring/H2 browser fixture + real ComfyUI + real dedicated MinIO development bucket',
              'checks': CHECKS, 'projectId': project, 'runId': run_id, 'mediaId': media_id,
              'sha256': generated['sha256'], 'bytes': len(content), 'run': run,
              'memberRemovedForBrowserCheck': True, 'credentialValuesIncluded': False, 'productionDeploymentChanged': False}
    (evidence / 'm2-acceptance-boundary.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'passed': True, 'checks': len(CHECKS), 'memberRemovedForBrowserCheck': True}))


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        raise SystemExit('M2 boundary validation failed: ' + type(error).__name__ + '; inspect owned fixture state without printing secrets.')
