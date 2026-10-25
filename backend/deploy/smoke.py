#!/usr/bin/env python3
"""Live M2 checks on the isolated HuiMeng deployment; outputs only redacted evidence.

Run on the server as root. Creates synthetic accounts/projects, disables/archives them,
revokes its credential and removes its synthetic provider secret. No model call occurs.
"""
import base64
import hashlib
import hmac
import http.cookiejar
import json
from pathlib import Path
import secrets
import socket
import ssl
import subprocess
import time
import urllib.error
import urllib.request
import uuid

APP = Path('/opt/apps/huimeng')
ORIGIN = 'https://192.168.1.102:18443'
RESULTS = {}


def check(name, condition):
    if not condition:
        raise AssertionError(name)
    RESULTS[name] = 'PASS'


class Client:
    def __init__(self):
        self.cookies = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(
            urllib.request.ProxyHandler({}),
            urllib.request.HTTPSHandler(context=ssl.create_default_context(cafile=str(APP / 'secrets/tls/ca.crt'))),
            urllib.request.HTTPCookieProcessor(self.cookies))
        self.csrf = None

    def request(self, method, path, data=None, csrf=True, origin=ORIGIN, bearer=None):
        headers = {'Content-Type': 'application/json'}
        if method not in ('GET', 'HEAD'):
            headers['Origin'] = origin
            if csrf and self.csrf:
                headers['X-CSRF-Token'] = self.csrf
        if bearer:
            headers['Authorization'] = 'Bearer ' + bearer
        request = urllib.request.Request(ORIGIN + path, data=None if data is None else json.dumps(data).encode(), headers=headers, method=method)
        try:
            response = self.opener.open(request, timeout=10)
        except urllib.error.HTTPError as ex:
            response = ex
        content = response.read()
        parsed = json.loads(content) if content and 'json' in response.headers.get('Content-Type', '') else None
        return response.status, parsed, response.headers

    def token(self):
        status, body, headers = self.request('GET', '/api/v1/auth/csrf')
        check('csrf_https', status == 200)
        self.csrf = body['token']
        return headers

    def login(self, username, password):
        self.token()
        old = self.csrf
        status, body, headers = self.request('POST', '/api/v1/auth/login', {'username': username, 'password': password})
        check('login', status == 200)
        self.token()
        check('csrf_rotated', self.csrf != old)
        return body['user']


def sql(statement):
    return subprocess.run(['docker', 'exec', '-i', 'mysql', 'sh', '-c',
                           'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql -uroot -N huimeng'],
                          input=statement, text=True, capture_output=True, check=True).stdout.strip()


def main():
    admin = Client()
    headers = admin.token()
    cookie = headers.get('Set-Cookie', '')
    check('secure_cookie', all(flag.lower() in cookie.lower() for flag in ['__Host-huimeng-session=', 'Secure', 'HttpOnly', 'SameSite=Lax', 'Path=/']) and 'Domain=' not in cookie)
    password = (APP / 'secrets/bootstrap-password').read_text().strip()
    identity = admin.login('huimeng-admin', password)
    check('me', admin.request('GET', '/api/v1/auth/me')[0] == 200)
    status, initial_preferences, _ = admin.request('GET', '/api/v1/preferences')
    check('admin_preferences_readable', status == 200)
    status, catalog, _ = admin.request('GET', '/api/v1/admin/models')
    check('admin_model_catalog', status == 200 and len(catalog['items']) >= 8 and all('enabled' in model for model in catalog['items']))
    suffix = uuid.uuid4().hex[:10]
    name, synthetic_password = 'm2-check-' + suffix, secrets.token_urlsafe(32)
    status, member, _ = admin.request('POST', '/api/v1/admin/users', {'username': name, 'password': synthetic_password})
    check('create_account', status == 201 and 'password' not in member)
    projects = []
    for label in ['own', 'other']:
        status, project, _ = admin.request('POST', '/api/v1/projects', {'name': 'M2 验证 ' + label + ' ' + suffix})
        check('create_project_' + label, status == 201 and 'workspace' not in json.dumps(project).lower())
        projects.append(project['id'])
    own, other = projects
    check('add_member', admin.request('POST', '/api/v1/projects/' + own + '/members', {'userId': member['id']})[0] == 201)
    employee = Client()
    employee.login(name, synthetic_password)
    check('member_admin_catalog_denied', employee.request('GET', '/api/v1/admin/models')[0] == 403)
    check('save_personal_preferences', employee.request('PUT', '/api/v1/preferences', {'ui': {'theme': 'dark'}})[0] == 204)
    status, preferences, _ = employee.request('GET', '/api/v1/preferences')
    check('personal_preferences_roundtrip', status == 200 and preferences.get('ui', {}).get('theme') == 'dark')
    status, admin_preferences, _ = admin.request('GET', '/api/v1/preferences')
    check('personal_preferences_isolated', status == 200 and admin_preferences == initial_preferences)
    check('preference_secret_rejected', employee.request('PUT', '/api/v1/preferences', {'stores': {'apiKey': 'synthetic-test-only'}})[0] == 400)
    status, listed, _ = employee.request('GET', '/api/v1/projects')
    check('member_list_isolated', status == 200 and [p['id'] for p in listed['items']] == [own])
    check('cross_project_denied', employee.request('GET', '/api/v1/projects/' + other)[0] == 403)
    check('member_admin_denied', employee.request('GET', '/api/v1/admin/providers')[0] == 403)
    check('creative_stays_closed', employee.request('POST', '/api/v1/projects/' + own + '/creative/ai/image', {})[0] == 403)
    check('missing_csrf_denied', employee.request('POST', '/api/v1/auth/logout', {}, csrf=False)[0] == 403)
    check('foreign_origin_denied', employee.request('POST', '/api/v1/auth/logout', {}, origin='https://foreign.invalid')[0] == 403)
    check('claimed_directory_denied', admin.request('POST', '/api/v1/projects', {'name': 'invalid', 'directory': '/etc'})[0] == 400)
    synthetic_key = secrets.token_urlsafe(32)
    provider = 'm2-check-' + suffix
    check('key_write_only', admin.request('PUT', '/api/v1/admin/providers/' + provider + '/secret', {'apiKey': synthetic_key})[0] == 204)
    status, providers, _ = admin.request('GET', '/api/v1/admin/providers')
    check('key_not_in_response', status == 200 and synthetic_key not in json.dumps(providers) and 'encrypted' not in json.dumps(providers))
    check('key_encrypted_at_rest', synthetic_key not in sql("SELECT encrypted_value FROM provider_secrets WHERE provider_id='" + provider + "';"))
    approval = {'providerId': 'qwenImage21Local', 'modelId': 'qwenImage21Text', 'enabled': True}
    check('approve_model', admin.request('PUT', '/api/v1/admin/models/approval', approval)[0] == 204)
    status, models, _ = employee.request('GET', '/api/v1/models')
    check('approved_model_visible_2k_preserved', status == 200 and any(m['modelId'] == 'qwenImage21Text' and '2K' in m['options']['resolutions'] for m in models['items']))
    approval['enabled'] = next(model['enabled'] for model in catalog['items'] if model['providerId'] == approval['providerId'] and model['modelId'] == approval['modelId'])
    admin.request('PUT', '/api/v1/admin/models/approval', approval)
    status, issued, _ = admin.request('POST', '/api/v1/admin/external-clients', {'name': name, 'projectId': own, 'mcpTools': ['getCanvas'], 'a2aTeams': ['writers']})
    check('external_issue', status == 201)
    token = issued['token']
    external = Client()
    rpc = {'jsonrpc': '2.0', 'id': 42, 'method': 'tools/call', 'params': {'name': 'getCanvas'}}
    status, result, _ = external.request('POST', '/mcp', rpc, bearer=token)
    check('protocol_not_executed_m2', status == 503 and result['id'] == 42)
    rpc['params']['name'] = 'deleteFile'
    check('external_capability_denied', external.request('POST', '/mcp', rpc, bearer=token)[0] == 403)
    check('external_team_denied', external.request('POST', '/a2a/other', rpc, bearer=token)[0] == 403)
    check('cookie_not_external_identity', employee.request('POST', '/mcp', rpc)[0] == 401)
    check('bearer_not_employee_session', external.request('GET', '/api/v1/auth/me', bearer=token)[0] == 401)
    status, client_list, _ = admin.request('GET', '/api/v1/admin/external-clients')
    check('external_token_only_once', status == 200 and token not in json.dumps(client_list) and 'secret_hash' not in json.dumps(client_list))
    check('external_revoke', admin.request('POST', '/api/v1/admin/external-clients/' + issued['id'] + '/revoke', {})[0] == 204)
    check('revoked_token_denied', external.request('POST', '/mcp', rpc, bearer=token)[0] == 401)
    check('remove_member', admin.request('DELETE', '/api/v1/projects/' + own + '/members/' + member['id'])[0] == 204)
    check('removed_member_denied', employee.request('GET', '/api/v1/projects/' + own)[0] == 403)
    check('disable_account', admin.request('PATCH', '/api/v1/admin/users/' + member['id'], {'status': 'DISABLED'})[0] == 200)
    check('disabled_session_denied', employee.request('GET', '/api/v1/auth/me')[0] == 401)
    # Exercise the deployed guard using a trusted context held only in this process.
    workspace_key = sql("SELECT workspace_key FROM projects WHERE id='" + own + "';")
    now = int(time.time())
    context = {'v': 1, 'iat': now, 'exp': now + 20, 'nonce': uuid.uuid4().hex, 'actorId': identity['id'], 'projectId': own, 'workspaceKey': workspace_key, 'requestId': uuid.uuid4().hex, 'method': 'GET', 'path': '/internal/ready', 'bodySha256': hashlib.sha256(b'').hexdigest()}
    payload = base64.urlsafe_b64encode(json.dumps(context, separators=(',', ':')).encode()).decode().rstrip('=')
    key = base64.b64decode((APP / 'secrets/internal.key').read_text().strip())
    signature = base64.urlsafe_b64encode(hmac.digest(key, payload.encode(), 'sha256')).decode().rstrip('=')
    signed = payload + '.' + signature
    code = "const token=await Bun.stdin.text(); const url='http://127.0.0.1:3000/internal/ready'; const result=[]; for(const headers of [{},{'Authorization':'Bearer legacy'},{'X-HuiMeng-Internal':token},{'X-HuiMeng-Internal':token}]) result.push((await fetch(url,{headers})).status); console.log(JSON.stringify(result));"
    result = subprocess.run(['docker', 'exec', '-i', 'huimeng-boundary', 'bun', '-e', code], input=signed, text=True, capture_output=True, check=True)
    check('deployed_internal_guard_and_replay', json.loads(result.stdout) == [401, 401, 200, 401])
    for container in ['huimeng-api', 'huimeng-boundary']:
        ports = json.loads(subprocess.check_output(['docker', 'inspect', container, '--format', '{{json .NetworkSettings.Ports}}']))
        check(container + '_no_published_ports', not ports or all(value is None for value in ports.values()))
    for port in [18085, 3000]:
        try:
            connection = socket.create_connection(('192.168.1.102', port), timeout=2)
        except OSError:
            check('host_port_' + str(port) + '_closed', True)
        else:
            connection.close()
            check('host_port_' + str(port) + '_closed', False)
    for project in projects:
        admin.request('PATCH', '/api/v1/projects/' + project, {'status': 'ARCHIVED'})
    check('logout', admin.request('POST', '/api/v1/auth/logout', {})[0] == 204)
    check('logged_out_session_denied', admin.request('GET', '/api/v1/auth/me')[0] == 401)
    logs = subprocess.check_output(['docker', 'logs', 'huimeng-api'], stderr=subprocess.STDOUT).decode()
    audit = sql('SELECT action,target,result,request_id FROM audit_events;')
    check('no_secret_in_logs_or_audit', not any(value in logs or value in audit for value in [password, synthetic_password, synthetic_key, token]))
    sql("DELETE FROM provider_secrets WHERE provider_id='" + provider + "';")
    RESULTS['synthetic_cleanup'] = 'accounts disabled; projects archived; credential revoked; synthetic provider secret removed'
    RESULTS['model_calls'] = 0
    print(json.dumps(RESULTS, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    try:
        main()
    except Exception as ex:
        # HTTP data, cookies and credential-bearing exceptions must never reach output.
        print(json.dumps({'passed': RESULTS, 'failed': str(ex) if isinstance(ex, AssertionError) else type(ex).__name__}))
        raise SystemExit(1)
