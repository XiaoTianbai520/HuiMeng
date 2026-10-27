"""Generate the frozen M1/M2 baseline contract and trace every fixed upstream route.

Since M3 the committed ``docs/api/openapi.yaml`` and ``docs/api/route-map.json`` are the source
of truth: platform-only paths and reviewed descriptions were added by hand and this generator
cannot reproduce them. It therefore writes to ``docs/api/generated/`` by default; ``--write``
is only for an intentional M1 baseline rebuild and will drop the M3 additions.

Requires PyYAML (build only). Does not read a service, credentials or M0 scratch.
"""
import argparse
import json
from pathlib import Path
import yaml

ROOT = Path(__file__).resolve().parents[2]
API = ROOT / 'docs/api'
IMPLEMENTED = set(json.loads((ROOT / 'backend/api-implementation.json').read_text(encoding='utf-8'))['operations'])
FR_BY_ACCEPTANCE = {'A01': ['FR-01'], 'A02': ['FR-02'], 'A03': ['FR-02', 'FR-04'],
    'A04': ['FR-03'], 'A05': ['FR-05'], 'A06': ['FR-04'], 'A07': ['FR-06'], 'A08': ['FR-06'],
    'A09': ['FR-07'], 'A10': ['FR-08'], 'A11': ['FR-09'], 'A12': ['FR-14'], 'A13': ['FR-10'],
    'A14': ['FR-13'], 'A15': ['FR-04'], 'A16': ['FR-11'], 'A17': ['FR-02', 'FR-04'],
    'A18': ['FR-06', 'FR-07', 'FR-08', 'FR-11']}


def ref(name):
    return {'$ref': '#/components/schemas/' + name}


def obj(properties, required=None):
    result = {'type': 'object', 'additionalProperties': False, 'properties': properties}
    if required:
        result['required'] = required
    return result


def arr(items):
    return {'type': 'array', 'items': items}


def string(**kwargs):
    return {'type': 'string', **kwargs}


ID = string(minLength=1, maxLength=128, pattern='^[A-Za-z0-9_-]+$')
NAME = string(minLength=1, maxLength=128)
BOOL = {'type': 'boolean'}
TIME = string(format='date-time')
NULL = {'type': 'null'}
JSON_VALUE = {'description': 'JSON value; only within an explicitly documented payload.'}
SCHEMAS = {
    'Error': obj({'code': ref('ErrorCode'), 'message': string(), 'requestId': string(),
                  'details': {'oneOf': [{'type': 'object'}, NULL]}}, ['code', 'message', 'requestId', 'details']),
    'ErrorCode': string(enum=['INVALID_ARGUMENT', 'PATH_NOT_ALLOWED', 'UNAUTHENTICATED', 'CLIENT_TOKEN_INVALID',
        'CSRF_INVALID', 'PROJECT_FORBIDDEN', 'CAPABILITY_FORBIDDEN', 'NOT_FOUND', 'REVISION_CONFLICT',
        'IDEMPOTENCY_CONFLICT', 'PROJECT_CAPACITY_EXCEEDED', 'RATE_LIMITED', 'EXECUTOR_UNAVAILABLE', 'RUN_LOST',
        'PLUGIN_INTEGRITY_FAILED', 'PLUGIN_VERSION_CONFLICT', 'PLUGIN_IN_USE', 'MARKET_UNAVAILABLE',
        'UPLOAD_TOO_LARGE', 'UNSUPPORTED_MEDIA_TYPE', 'FILE_ALREADY_EXISTS', 'DIRECTORY_NOT_EMPTY', 'FILE_OPERATION_INCOMPLETE']),
    'User': obj({'id': ID, 'username': NAME, 'role': string(enum=['ADMIN', 'USER']),
                 'status': string(enum=['ACTIVE', 'DISABLED'])}, ['id', 'username', 'role', 'status']),
    'Login': obj({'username': NAME, 'password': string(minLength=1, maxLength=256, writeOnly=True)}, ['username', 'password']),
    'CreateUser': obj({'username': NAME, 'password': string(minLength=12, maxLength=256, writeOnly=True),
                       'role': string(enum=['ADMIN', 'USER'], default='USER')}, ['username', 'password']),
    'UpdateUser': obj({'status': string(enum=['ACTIVE', 'DISABLED']), 'role': string(enum=['ADMIN', 'USER']),
                       'password': string(minLength=12, maxLength=256, writeOnly=True)}),
    'Project': obj({'id': ID, 'name': NAME, 'status': string(enum=['ACTIVE', 'ARCHIVED'])}, ['id', 'name', 'status']),
    'ProjectInput': obj({'name': NAME}, ['name']),
    'ProjectUpdate': obj({'name': NAME, 'status': string(enum=['ACTIVE', 'ARCHIVED'])}),
    'MemberInput': obj({'userId': ID}, ['userId']),
    'Member': obj({'userId': ID, 'projectId': ID}, ['userId', 'projectId']),
    'SecretInput': obj({'apiKey': string(minLength=1, maxLength=16384, writeOnly=True)}, ['apiKey']),
    'ProviderStatus': obj({'providerId': ID, 'configured': BOOL, 'updatedAt': {'oneOf': [TIME, NULL]}},
                          ['providerId', 'configured', 'updatedAt']),
    'Model': obj({'providerId': ID, 'modelId': NAME, 'label': NAME,
        'capabilities': arr(string(enum=['text', 'image', 'video', 'audio', 'agent'])),
        'enabled': BOOL, 'options': {'type': 'object', 'description': '获批模型的公开规格；不得含端点、密钥或服务器目录。'}},
        ['providerId', 'modelId', 'label', 'capabilities', 'enabled', 'options']),
    'Approval': obj({'providerId': ID, 'modelId': NAME, 'enabled': BOOL}, ['providerId', 'modelId', 'enabled']),
    'ExportedModel': obj({'providerId': ID, 'modelId': NAME, 'enabled': BOOL}, ['providerId', 'modelId', 'enabled']),
    'ConfigurationExport': obj({'version': {'type': 'integer', 'const': 1}, 'exportedAt': TIME,
        'providers': arr(ref('ProviderStatus')), 'marketConfigured': BOOL, 'models': arr(ref('ExportedModel'))},
        ['version', 'exportedAt', 'providers', 'marketConfigured', 'models']),
    'ClientInput': obj({'name': NAME, 'projectId': ID, 'mcpTools': arr(NAME), 'a2aTeams': arr(NAME)},
                       ['name', 'projectId', 'mcpTools', 'a2aTeams']),
    'Client': obj({'id': ID, 'name': NAME, 'projectId': ID, 'mcpTools': arr(NAME), 'a2aTeams': arr(NAME),
        'createdAt': TIME, 'revokedAt': {'oneOf': [TIME, NULL]}}, ['id', 'name', 'projectId', 'mcpTools', 'a2aTeams', 'createdAt', 'revokedAt']),
    'ClientIssued': obj({'id': ID, 'token': string(readOnly=True), 'scope': ref('ClientInput')}, ['id', 'token', 'scope']),
    'MarketSearch': obj({'page': {'type': 'integer', 'minimum': 1, 'default': 1},
        'limit': {'type': 'integer', 'minimum': 1, 'maximum': 50, 'default': 20},
        'type': string(enum=['all', 'node', 'ext', 'skill', 'tool', 'agent', 'my', 'collection']),
        'searchKeyword': string(maxLength=200)}, ['page', 'limit', 'type']),
    'MarketItem': obj({'id': ID, 'type': string(enum=['node', 'ext', 'skill', 'tool', 'agent']),
        **{key: string() for key in ['name', 'displayName', 'author', 'description', 'version', 'fileName']},
        'isCollected': BOOL, 'installable': BOOL, 'manifest': {'oneOf': [ref('PluginManifest'), NULL]}}, ['id', 'type', 'name', 'displayName', 'author', 'description',
        'version', 'fileName', 'isCollected', 'installable', 'manifest']),
    'MarketResult': obj({'items': arr(ref('MarketItem')), 'total': {'type': 'integer', 'minimum': 0}}, ['items', 'total']),
    'PluginManifest': obj({'type': string(enum=['node', 'ext', 'skill', 'tool']), 'name': NAME,
        'version': NAME, 'sha256': string(pattern='^[a-f0-9]{64}$')}, ['type', 'name', 'version', 'sha256']),
    'PluginInstall': obj({'pluginId': ID, 'manifest': ref('PluginManifest'), 'force': BOOL}, ['pluginId', 'manifest']),
    'InstalledPlugin': obj({'type': string(enum=['node', 'ext', 'skill', 'tool', 'agent']), 'name': NAME,
        'displayName': string(), 'author': string(), 'description': string(), 'version': NAME, 'enabled': BOOL,
        'canManage': BOOL, 'canConfigure': BOOL, 'configRules': arr({'type': 'object'}),
        'config': {'type': 'object', 'description': '仅非秘密配置；secret 字段省略，不能返回脱敏占位符作为值'},
        'configuredSecrets': arr(string())}, ['type', 'name', 'displayName', 'author', 'description', 'version',
        'enabled', 'canManage', 'canConfigure', 'configRules', 'config', 'configuredSecrets']),
    'Run': obj({'id': ID, 'projectId': ID, 'kind': string(enum=['image', 'video', 'audio', 'agent', 'singleAgent']),
        'state': string(enum=['RESERVED', 'RUNNING', 'CANCELLING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'LOST']),
        # 失败原因只给固定词表代码：前端按 USER_/MODEL_/EXECUTION_ 前缀分类展示，服务端不下发异常文本，
        # 避免把上游地址、桶名或堆栈带到客户端。SUCCEEDED/CANCELLED/进行中为 null。
        'reason': {'oneOf': [string(enum=['USER_INVALID_REQUEST', 'USER_NOT_PERMITTED', 'USER_CAPACITY_EXCEEDED',
                                          'MODEL_UNAVAILABLE', 'MODEL_TIMEOUT',
                                          'EXECUTION_STORAGE_UNAVAILABLE', 'EXECUTION_BUSY', 'EXECUTION_LOST',
                                          'EXECUTION_INTERRUPTED', 'EXECUTION_FAILED', 'EXECUTION_INTERNAL']), NULL]},
        'capacityHeld': BOOL, 'createdAt': TIME, 'endedAt': {'oneOf': [TIME, NULL]}},
        ['id', 'projectId', 'kind', 'state', 'reason', 'capacityHeld', 'createdAt', 'endedAt']),
    'Preferences': obj({'ui': {'type': 'object'}, 'general': {'type': 'object'}, 'stores': {'type': 'object'},
                        'documentExtensions': {'type': 'object', 'additionalProperties': string()}}),
    'RelativePath': string(maxLength=2048, pattern=r'^(?![\\/])(?!.*(?:^|[\\/])\.\.(?:[\\/]|$))(?!.*[:\x00-\x1f]).*$'),
    'Snapshot': obj({'text': string(), 'encoding': string(enum=['utf-8', 'utf-8-bom', 'utf-16le', 'utf-16be']),
                      'revision': string(pattern='^[a-f0-9]{64}$')}, ['text', 'encoding', 'revision']),
}
SCHEMAS['ProjectMedia'] = obj({'id': ID, 'projectId': ID,
    'filename': string(minLength=1, maxLength=255), 'byteSize': {'type': 'integer', 'minimum': 0, 'maximum': 536870912},
    'sha256': string(pattern='^[a-f0-9]{64}$'), 'createdAt': TIME},
    ['id', 'projectId', 'filename', 'byteSize', 'sha256', 'createdAt'])
for name in ['User', 'Project', 'Member', 'Client', 'ProjectMedia']:
    SCHEMAS[name + 'Page'] = obj({'items': arr(ref(name)), 'nextCursor': {'oneOf': [string(), NULL]}}, ['items', 'nextCursor'])
for name in ['UpdateUser', 'ProjectUpdate']:
    SCHEMAS[name]['minProperties'] = 1

SPEC = {'openapi': '3.1.0', 'info': {'title': '绘梦 1.0 API', 'version': '1.0.0-m1',
    'description': 'M1 平台契约冻结；实现仍在 M2/M3。兼容路由状态见 route-map.json；无通配代理。'},
    'servers': [{'url': '/', 'description': '局域网同源 HTTPS；开发代理仅指向 Spring'}],
    'security': [{'Session': []}], 'paths': {},
    'components': {'securitySchemes': {'Session': {'type': 'apiKey', 'in': 'cookie', 'name': '__Host-huimeng-session'},
        'ExternalBearer': {'type': 'http', 'scheme': 'bearer'}}, 'schemas': SCHEMAS}}


def param(name, location, schema, required=False):
    return {'name': name, 'in': location, 'required': required, 'schema': schema}


def operation(method, path, op_id, summary, roles, acceptance, request=None, response=None, status=200,
              csrf=True, pagination=False, description=''):
    parameters = [param(p, 'path', ID, True) for p in __import__('re').findall(r'\{(\w+)\}', path)]
    parameters.append(param('X-Request-Id', 'header', string(maxLength=128)))
    if csrf and method not in ['get', 'head']:
        parameters.append(param('X-CSRF-Token', 'header', string(minLength=1), True))
    if pagination:
        parameters += [param('cursor', 'query', string(maxLength=2048)),
            param('limit', 'query', {'type': 'integer', 'minimum': 1, 'maximum': 50, 'default': 20})]
    op = {'operationId': op_id, 'summary': summary, 'description': description or summary,
        'tags': [path.split('/')[3]], 'x-contract-status': 'frozen', 'x-roles': roles, 'x-acceptance': acceptance,
        'x-requirements': sorted({fr for a in acceptance for fr in FR_BY_ACCEPTANCE[a]}),
        'x-audit-required': 'ADMIN' in roles and method not in ['get', 'head'],
        'x-implemented': op_id in IMPLEMENTED, 'parameters': parameters,
        'responses': {str(status): {'description': '成功', 'headers': {
            'X-Request-Id': {'schema': string(), 'description': '规范化关联 ID'}}}}}
    if response:
        op['responses'][str(status)]['content'] = {'application/json': {'schema': response}}
    for code in [400, 401, 403, 404, 409, 413, 415, 429, 502, 503]:
        op['responses'][str(code)] = {'description': '见 ErrorCode 及 CONTRACTS；仅开始流前使用',
            'content': {'application/json': {'schema': ref('Error')}}}
    if request:
        op['requestBody'] = {'required': True, 'content': {'application/json': {'schema': request}}}
    SPEC['paths'].setdefault(path, {})[method] = op
    return op


def main(argv=None):
    parser = argparse.ArgumentParser(description='重建 M1/M2 契约基线；默认不覆盖提交版。')
    parser.add_argument(
        '--write',
        action='store_true',
        help='覆盖 docs/api/openapi.yaml 与 route-map.json；会丢失 M3 之后的手工平台路径，仅用于有意重建基线',
    )
    args = parser.parse_args(argv)
    member = ['AUTHENTICATED']
    admin = ['ADMIN']
    project = ['PROJECT_MEMBER']
    p = '/api/v1'
    media_path = p + '/projects/{projectId}/media'
    operation('get', media_path, 'listProjectMedia', '读取当前项目 READY 媒体列表', project,
        ['A03', 'A06', 'A17'], response=ref('ProjectMediaPage'), pagination=True)
    upload = operation('post', media_path, 'uploadProjectMedia', '流式上传媒体到私有对象桶', project,
        ['A03', 'A06', 'A17'], response=ref('ProjectMedia'), status=201,
        description='Content-Length 必填，0–512 MiB；禁止自动重试。仅显示文件名，不接收对象键/目录/URL。PENDING/FAILED 不出现在成员列表，DB/S3 不声称原子提交。')
    upload['parameters'] += [param('filename', 'query', string(minLength=1, maxLength=255), True)]
    upload['requestBody'] = {'required': True, 'content': {'application/octet-stream': {'schema': string(format='binary')}}}
    upload['x-audit-required'] = True
    download = operation('get', media_path + '/{mediaId}/content', 'downloadProjectMedia', '重新授权后下载媒体原字节', project,
        ['A03', 'A06', 'A17'], description='不重定向到对象存储，不发预签名 URL；每次重新检查项目成员，单 Range。')
    download['parameters'] += [param('Range', 'header', string())]
    download['responses']['200']['content'] = {'application/octet-stream': {'schema': string(format='binary')}}
    download['responses']['206'] = {'description': '单 Range，Content-Range 与 Content-Length 对应原对象',
        'content': {'application/octet-stream': {'schema': string(format='binary')}}}
    download['responses']['416'] = {'description': '无效范围，Content-Range: bytes */长度'}
    delete = operation('delete', media_path + '/{mediaId}', 'deleteProjectMedia', '删除当前项目媒体', project,
        ['A03', 'A06', 'A17'], status=204, description='失败保留 DELETING，可用相同 ID 重试；禁止覆盖原对象。归档项目禁止写入。')
    delete['x-audit-required'] = True
    op = operation('get', p + '/auth/csrf', 'getCsrf', '取得预登录或当前会话 CSRF 令牌', ['ANONYMOUS'], ['A01'],
        response=obj({'token': string()}, ['token']), csrf=False,
        description='Cache-Control: no-store；建立预会话 Cookie。登录轮换会话及 CSRF，客户端登录成功后重新读取。')
    op['security'] = []
    op = operation('post', p + '/auth/login', 'login', '内部账号登录', ['ANONYMOUS'], ['A01'],
        ref('Login'), obj({'user': ref('User')}, ['user']), description='预登录 CSRF + 同源 Origin 校验；轮换会话；禁止自动重试。')
    op['security'] = []
    operation('get', p + '/auth/me', 'me', '读取当前账号', member, ['A01'], response=ref('User'))
    operation('post', p + '/auth/logout', 'logout', '退出并撤销当前会话', member, ['A01'], status=204)
    operation('get', p + '/admin/users', 'listUsers', '账号列表', admin, ['A01'], response=ref('UserPage'), pagination=True)
    operation('post', p + '/admin/users', 'createUser', '创建内部账号', admin, ['A01'], ref('CreateUser'), ref('User'), 201)
    operation('patch', p + '/admin/users/{userId}', 'updateUser', '启停账号、改角色或重置密码', admin, ['A01'],
        ref('UpdateUser'), ref('User'), description='至少一个字段；禁止停用或降权最后一个活跃管理员；停用/重置密码撤销已有会话。')
    operation('get', p + '/projects', 'listProjects', '列出授权项目或管理员可管理的元数据', member, ['A02', 'A17'],
        response=ref('ProjectPage'), pagination=True)
    operation('post', p + '/projects', 'createProject', '建立项目', admin, ['A02', 'A17'], ref('ProjectInput'), ref('Project'), 201)
    operation('get', p + '/projects/{projectId}', 'getProject', '读取项目元数据', ['ADMIN', 'PROJECT_MEMBER'], ['A02', 'A17'], response=ref('Project'))
    operation('patch', p + '/projects/{projectId}', 'updateProject', '修改项目元数据', admin, ['A02'], ref('ProjectUpdate'), ref('Project'))
    operation('get', p + '/projects/{projectId}/members', 'listMembers', '成员列表', admin, ['A02'], response=ref('MemberPage'), pagination=True)
    operation('post', p + '/projects/{projectId}/members', 'addMember', '增加项目成员', admin, ['A02'], ref('MemberInput'), ref('Member'), 201)
    operation('delete', p + '/projects/{projectId}/members/{userId}', 'removeMember', '移除成员，阻断后续项目读取', admin, ['A02'], status=204)
    operation('get', p + '/models', 'listModels', '仅返回获批且启用的模型', member, ['A07', 'A08', 'A11'], response=obj({'items': arr(ref('Model'))}, ['items']))
    operation('get', p + '/admin/providers', 'listProviders', '供应商配置状态', admin, ['A11'], response=obj({'items': arr(ref('ProviderStatus'))}, ['items']))
    op = operation('get', p + '/admin/configuration/export', 'exportConfiguration', '导出团队配置的安全元数据', admin, ['A11'], response=ref('ConfigurationExport'),
        description='仅导出供应商配置状态、市场是否配置及模型批准标识；排除密钥、密文、端点、任意模型 options 和物理目录。记录导出审计。')
    op['x-audit-required'] = True
    operation('get', p + '/admin/models', 'listAdminModels', '管理员读取已登记模型及批准状态', admin, ['A11'], response=obj({'items': arr(ref('Model'))}, ['items']), description='包含未批准模型的公开元数据，供管理员启停批准；不返回端点、密钥或物理目录。')
    operation('put', p + '/admin/providers/{providerId}/secret', 'setProviderSecret', '写入团队供应商密钥', admin, ['A11'], ref('SecretInput'), status=204)
    operation('put', p + '/admin/models/approval', 'approveModel', '启停模型批准状态', admin, ['A11'], ref('Approval'), status=204)
    operation('post', p + '/admin/external-clients', 'issueClient', '签发单项目外部凭证，原文仅返回一次', admin, ['A13'], ref('ClientInput'), ref('ClientIssued'), 201)
    operation('get', p + '/admin/external-clients', 'listClients', '查看外部授权，不返回凭证原文', admin, ['A13'], response=ref('ClientPage'), pagination=True)
    operation('post', p + '/admin/external-clients/{clientId}/revoke', 'revokeClient', '撤销外部凭证', admin, ['A13'], status=204)
    operation('get', p + '/preferences', 'getPreferences', '读取当前账号个人偏好', member, ['A11', 'A14'], response=ref('Preferences'))
    operation('put', p + '/preferences', 'setPreferences', '保存个人偏好，不接受供应商或市场配置', member, ['A11', 'A14'], ref('Preferences'), status=204)
    base = p + '/admin/plugin-market'
    operation('post', base + '/search', 'searchMarket', '公司市场发现、搜索和分页', admin, ['A12'], ref('MarketSearch'), ref('MarketResult'))
    operation('post', base + '/collections/toggle', 'toggleCollection', '切换公司收藏，禁止自动重试', admin, ['A12'], obj({'pluginId': ID}, ['pluginId']), obj({'collected': BOOL}, ['collected']))
    operation('put', base + '/secret', 'setMarketSecret', '写入公司 TF-Router 密钥', admin, ['A11', 'A12'], ref('SecretInput'), status=204)
    operation('get', base + '/status', 'getMarketStatus', '市场配置状态', admin, ['A11', 'A12'], response=obj({'configured': BOOL}, ['configured']))
    operation('post', base + '/install', 'installMarketPlugin', '校验清单后安装市场制品', admin, ['A12'], ref('PluginInstall'), status=204,
        description='pluginId 由目录查找地址；类型/标识/版本/明确的 SHA-256 必须与包一致。force 必须经界面明确确认；有运行占用仍拒绝。Agent 市场安装关闭。')
    operation('get', p + '/admin/plugins', 'listInstalledPlugins', '管理员读取全部已安装能力及安全配置', admin, ['A11', 'A12'],
        response=obj({'items': arr(ref('InstalledPlugin'))}, ['items']),
        description='包括已禁用能力；不能复用成员只含批准且启用能力的列表。配置规则不含秘密默认值；秘密省略，保留 configuredSecrets 标识，写接口未提供的秘密保持原值。')
    operation('get', p + '/projects/{projectId}/runs/{runId}', 'getRun', '查询发起人所属运行', project, ['A16', 'A18'], response=ref('Run'))
    operation('post', p + '/projects/{projectId}/runs/{runId}/cancel', 'cancelRun', '请求取消，收到确认后才释放名额', project, ['A16', 'A18'], response=ref('Run'), status=202,
        description='重复取消不重复执行。202 仅表示请求已接收，state 可能仍为 CANCELLING；断开流不等于取消。')
    media_ref = obj({'path': ref('RelativePath'), 'mimeType': string(pattern='^(image|video|audio)/[a-zA-Z0-9.+-]+$')}, ['path', 'mimeType'])
    shared = {'providerId': ID, 'modelId': string(minLength=1, maxLength=256), 'prompt': string(minLength=1, maxLength=100000),
              'outputDirectory': ref('RelativePath'), 'images': {**arr(media_ref), 'maxItems': 64}}
    variants = []
    for kind, extras in {
        'image': {'ratio': string(), 'size': string(minLength=1, maxLength=64)},
        'video': {'ratio': string(), 'videos': arr(media_ref), 'audios': arr(media_ref), 'firstFrame': media_ref, 'lastFrame': media_ref,
            'resolution': string(minLength=1, maxLength=64), 'duration': {'type': 'number', 'exclusiveMinimum': 0, 'maximum': 3600},
            'generateAudio': BOOL, 'mode': {'oneOf': [string(enum=['singleImage', 'startEndRequired', 'endFrameOptional', 'startFrameOptional', 'text']),
                {**arr(string(pattern='^(image|video|audio)Reference:([1-9][0-9]?|1[0-9]{2}|2[0-4][0-9]|25[0-6])$')), 'minItems': 1, 'maxItems': 3}]}},
        'audio': {'audios': arr(media_ref), 'voice': string(), 'speed': {'type': 'number', 'exclusiveMinimum': 0},
            'volume': {'type': 'number'}, 'pitch': {'type': 'number'}, 'language': string(), 'format': string(),
            'sampleRate': {'type': 'integer', 'minimum': 1}},
    }.items():
        variants.append(obj({**shared, 'mediaType': string(enum=[kind]), **extras}, ['providerId', 'modelId', 'prompt', 'mediaType']))
    SCHEMAS['MediaGenerate'] = {'oneOf': variants}
    SCHEMAS['LegacyResult'] = obj({'code': {'type': 'integer'}, 'data': JSON_VALUE, 'message': string()}, ['code', 'data', 'message'])
    SCHEMAS['GeneratedMedia'] = obj({'path': ref('RelativePath'), 'mimeType': string(),
        'mediaType': string(enum=['image', 'video', 'audio'])}, ['path', 'mimeType', 'mediaType'])
    SCHEMAS['MediaResult'] = {'oneOf': [obj({'code': {'const': 200}, 'data': arr(ref('GeneratedMedia')), 'message': string()}, ['code', 'data', 'message']),
        obj({'code': {'type': 'integer', 'not': {'const': 200}}, 'data': JSON_VALUE, 'message': string()}, ['code', 'data', 'message'])]}
    SCHEMAS['SnapshotResult'] = obj({'code': {'const': 200}, 'data': ref('Snapshot'), 'message': string()}, ['code', 'data', 'message'])
    SCHEMAS['WriteResult'] = obj({'code': {'const': 200}, 'data': obj({'revision': string(pattern='^[a-f0-9]{64}$')}, ['revision']),
        'message': string()}, ['code', 'data', 'message'])
    creative = p + '/projects/{projectId}/creative/'
    op = operation('post', creative + 'ai/media/generate', 'generateMedia', '媒体生成，JSON 前导空白保活', project,
        ['A08', 'A16', 'A18'], ref('MediaGenerate'), ref('MediaResult'),
        description='先占位再调用执行器。HTTP 200 仍须解析正文 code；仅 code=200 表示成功。X-Run-Id 标识运行。模型切换保留合法已选规格，缺省用模型推荐值；提交前显示有效规格，不强制将 2K 改成 1K。')
    op['x-implementation-scope'] = 'Qwen qwenImage21Text / image / 1K / 1:1 only; other providers and specs pending M3'
    op['parameters'].append(param('Idempotency-Key', 'header', string(minLength=1, maxLength=128), True))
    op['responses']['200']['headers']['X-Run-Id'] = {'schema': ID, 'description': '运行 ID，用于查询及显式取消'}
    op['responses']['202'] = {'description': '同一幂等键的重试；只返回原运行，不重复执行或重放结果流',
        'content': {'application/json': {'schema': ref('Run')}},
        'headers': {'X-Run-Id': {'schema': ID, 'description': '原运行 ID'},
                    'Location': {'schema': string(), 'description': '同源项目运行查询路径'}}}
    read = operation('get', creative + 'workspaces/files/read', 'readFile', '读取原字节或带修订号的快照', project,
        ['A03', 'A04', 'A05', 'A06', 'A17'], response=ref('SnapshotResult'))
    read['parameters'] += [param('path', 'query', ref('RelativePath'), True), param('snapshot', 'query', BOOL),
        param('maxBytes', 'query', {'type': 'integer', 'minimum': 1}), param('Range', 'header', string())]
    read['responses']['200']['content']['application/octet-stream'] = {'schema': string(format='binary')}
    read['responses']['200']['description'] = 'snapshot=true 时 data 为 Snapshot，否则原文件字节；快照不接受 Range。'
    read['responses']['206'] = {'description': 'Range 部分文件字节', 'content': {'application/octet-stream': {'schema': string(format='binary')}}}
    read['responses']['416'] = {'description': 'Range 超出文件范围，Content-Range: bytes */长度'}
    write = operation('put', creative + 'workspaces/files/write', 'writeFile', '在同一文件锁内校验修订并原子保存', project,
        ['A03', 'A04', 'A05', 'A06'], response=ref('WriteResult'),
        description='已有文件 expectedRevision 必填；新建 exclusive=true。二者必须且只能提供一种。409.details.currentRevision 返回当前修订；前端停止自动覆盖。')
    write['parameters'] += [param('path', 'query', ref('RelativePath'), True),
        param('expectedRevision', 'query', string(pattern='^[a-f0-9]{64}$')), param('exclusive', 'query', BOOL),
        param('encoding', 'query', string(enum=['utf-8', 'utf-8-bom', 'utf-16le', 'utf-16be']))]
    write['parameters'].append(param('Content-Length', 'header', {'type': 'integer', 'minimum': 0, 'maximum': 8388608}, True))
    write['description'] += ' 当前 M3 首批要求已有父目录、单 Spring 写入者，正文及编码后均不超过 8 MiB；缺少安全目录句柄或原子替换能力时 503。'
    for status, description in [('413', '正文或编码后文件超过 8 MiB'), ('415', '指定 encoding 时输入不是有效 UTF-8 文本')]:
        write['responses'][status] = {'description': description, 'content': {'application/json': {'schema': ref('Error')}}}
    write['requestBody'] = {'required': True, 'content': {'application/octet-stream': {'schema': string(format='binary')}}}
    SCHEMAS['FileEntry'] = obj({'name': string(minLength=1), 'path': ref('RelativePath'), 'type': string(enum=['file', 'directory'])}, ['name', 'path', 'type'])
    SCHEMAS['FileListResult'] = obj({'code': {'const': 200}, 'data': obj({'directory': ID, 'empty': BOOL,
        'entries': arr(ref('FileEntry'))}, ['directory', 'empty', 'entries']), 'message': string()}, ['code', 'data', 'message'])
    SCHEMAS['FileOperationResult'] = obj({'code': {'const': 200}, 'data': NULL, 'message': string()}, ['code', 'data', 'message'])
    SCHEMAS['FileTransferInput'] = obj({'path': ref('RelativePath'), 'target': ref('RelativePath')}, ['path', 'target'])
    SCHEMAS['FilePathInput'] = obj({'path': ref('RelativePath')}, ['path'])
    SCHEMAS['FileRemoveInput'] = obj({'path': ref('RelativePath'), 'recursive': {'type': 'boolean', 'default': False}}, ['path'])
    listing = operation('get', creative+'workspaces/files/list', 'listWorkspaceFiles', '列出授权项目目录', project,
        ['A03', 'A04', 'A06', 'A17'], response=ref('FileListResult'),
        description='path 空值表示项目根。directory 返回项目 ID；不返回服务器目录，隐藏内部临时项与链接，最多检查 5000 条目录项。')
    listing['parameters'].append(param('path', 'query', string(maxLength=2048, default='')))
    for method, suffix, operation_id, request_schema in [
        ('post', 'mkdir', 'createWorkspaceDirectory', 'FilePathInput'),
        ('post', 'copy', 'copyWorkspaceFile', 'FileTransferInput'),
        ('post', 'rename', 'renameWorkspaceFile', 'FileTransferInput'),
        ('delete', 'remove', 'removeWorkspaceFile', 'FileRemoveInput')]:
        operation(method, creative+'workspaces/files/'+suffix, operation_id, '授权项目文件操作：'+suffix,
            project, ['A03', 'A04', 'A06', 'A17'], ref(request_schema), ref('FileOperationResult'),
            description='活动项目成员逐次授权。新建/复制/重命名不覆盖目标；Linux x86_64 安全目录句柄必需，缺失时 503。单 Spring 共用项目写锁；路径最多 2048 字符、复制总量 512 MiB、10000 项、递归 64 层。remove 需 recursive=true 才清空目录，部分失败可能已改变文件，须刷新核对。')
    # Opaque model context parts remain JSON as defined by the pinned model SDK;
    # top-level request fields and transport are stable at the gateway boundary.
    SCHEMAS['TextContext'] = obj({'systemPrompt': string(maxLength=100000),
        'messages': {**arr({'type': 'object', 'required': ['role', 'content', 'timestamp'], 'properties': {
            'role': string(enum=['user', 'assistant', 'toolResult']), 'content': {'oneOf': [string(), arr({'type': 'object'})]},
            'timestamp': {'type': 'number', 'minimum': 0}}}), 'minItems': 1, 'maxItems': 2000},
        'tools': {**arr(obj({'name': string(pattern='^[a-z][a-zA-Z0-9]{0,63}$'), 'description': string(minLength=1, maxLength=10000),
            'parameters': {'type': 'object'}, 'constrainedSampling': {'oneOf': [{'const': False}, {'type': 'object'}]}},
            ['name', 'description', 'parameters'])), 'maxItems': 32}}, ['messages'])
    SCHEMAS['TextContext']['description'] = 'SDK Context 原结构；总 UTF-8 JSON <=8 MB，tools 名称唯一；工具 schema 必须 type=object 且 <=100 KB。嵌套 assistant/工具消息按固定 SDK 校验，见 route-map R020 源字段。'
    SCHEMAS['AiReference'] = {'oneOf': [obj({'dataType': {'const': 'STRING'}, 'value': string(maxLength=1000000)}, ['dataType', 'value']),
        obj({'dataType': string(enum=['IMAGE', 'VIDEO', 'FILE']), 'value': obj({'url': ref('RelativePath'), 'mimeType': string(minLength=1, maxLength=128)},
            ['url', 'mimeType'])}, ['dataType', 'value'])]}
    SCHEMAS['TextGenerate'] = obj({'providerId': ID, 'modelId': NAME, 'context': ref('TextContext'),
        'references': {**arr(ref('AiReference')), 'maxItems': 32}}, ['providerId', 'modelId', 'context'])
    SCHEMAS['TextEvent'] = {'oneOf': [obj({'type': string(enum=['text', 'reasoning']), 'delta': string()}, ['type', 'delta']),
        obj({'type': {'const': 'done'}, 'message': {'type': 'object'}}, ['type', 'message']),
        obj({'type': {'const': 'error'}, 'message': string()}, ['type', 'message'])]}
    attachment = obj({'name': string(minLength=1, maxLength=255), 'path': ref('RelativePath'),
        'mimeType': string(minLength=1, maxLength=128)}, ['name', 'path', 'mimeType'])
    mention_source = {'oneOf': [obj({'kind': {'const': 'asset'}, 'path': ref('RelativePath')}, ['kind', 'path']),
        obj({'kind': {'const': 'canvas'}, **{field: string(minLength=1) for field in
            ['canvasId', 'canvasName', 'nodeId', 'nodeName', 'outputId', 'outputName']}},
            ['kind', 'canvasId', 'canvasName', 'nodeId', 'nodeName', 'outputId', 'outputName'])]}
    SCHEMAS['AgentGenerate'] = obj({'prompt': string(maxLength=100000), 'providerId': ID, 'modelId': NAME,
        'attachments': {**arr(attachment), 'maxItems': 20}, 'mentions': {**arr(obj({'id': string(format='uuid'),
            'label': string(minLength=1, maxLength=500), 'source': mention_source, 'dataType': string(minLength=1, maxLength=64),
            'value': JSON_VALUE}, ['id', 'label', 'source', 'dataType', 'value'])), 'maxItems': 20},
        'thinkingLevel': string(enum=['off', 'low', 'medium', 'high']),
        'sessionFile': string(pattern=r'^[\w-]+\.jsonl$'), 'resendFrom': string(minLength=1, maxLength=128),
        'canvas': obj({'id': string(minLength=1, maxLength=256), 'tools': {**arr(obj({'nodeId': string(minLength=1, maxLength=256),
            'name': string(pattern='^node:[a-z][a-zA-Z0-9]*$'), 'nodeLabel': string(maxLength=200),
            'description': string(maxLength=4000), 'parameters': {'type': 'object'}}, ['nodeId', 'name', 'description', 'parameters'])),
            'maxItems': 1000}}, ['id', 'tools'])}, ['prompt', 'providerId', 'modelId'])
    SCHEMAS['AgentGenerate']['description'] = 'mentions 按 dataType 校验；单 value 深度<=32、节点<=10000、JSON 长度<=100000，总 mentions<=500000。附件格式使用固定 documentFormats 可读格式白名单；canvas tools parameters.type 必须 object。'
    SCHEMAS['AgentEvent'] = {'type': 'object', 'required': ['type'], 'properties': {'type': string(enum=[
        'text', 'thinking', 'compaction', 'tool', 'canvasCall', 'question', 'error', 'session', 'userMessage',
        'subAgent', 'subAgentEvent', 'report', 'accepted', 'stats', 'done'])},
        'description': '保留固定提交 AgentEvent 嵌套结构；完整字段类型保存在独立前端 contracts/agent-types，递归子事件不结束父流。'}
    SCHEMAS['SingleAgentRun'] = obj({'sessionId': string(format='uuid'), 'runId': string(format='uuid'),
        'prompt': string(minLength=1, maxLength=100000), 'providerId': string(minLength=1, maxLength=200),
        'modelId': string(minLength=1, maxLength=500), 'thinkingLevel': string(enum=['off', 'minimal', 'low', 'medium', 'high', 'xhigh'])},
        ['sessionId', 'runId', 'prompt', 'providerId', 'modelId'])
    for path, schema, event_schema, content_type, slot, acceptance in [
        ('ai/generate', 'TextGenerate', 'TextEvent', 'text/event-stream', False, ['A07', 'A18']),
        ('agent', 'AgentGenerate', 'AgentEvent', 'application/x-ndjson', True, ['A09', 'A16', 'A18']),
        ('singleAgent/run', 'SingleAgentRun', 'AgentEvent', 'application/x-ndjson', True, ['A09', 'A16', 'A18']),
    ]:
        op = operation('post', creative + path, schema[0].lower() + schema[1:], '保留原流式帧与错误终态', project,
            acceptance, ref(schema), description='按 M1_CONTRACT_REVIEW 的心跳/分块/终态规则处理；不缓冲成普通 JSON。完整嵌套事件字段由固定提交类型定义约束。')
        op['responses']['200']['content'] = {content_type: {'schema': string(), 'x-event-schema': ref(event_schema)}}
        if slot:
            op['parameters'].append(param('Idempotency-Key', 'header', string(minLength=1, maxLength=128), True))
            op['responses']['200']['headers']['X-Run-Id'] = {'schema': ID, 'description': '服务端运行 ID；与上游 runId 分开'}
            op['responses']['202'] = {'description': '同一幂等键的重试；查询原运行，不重开流或再次执行',
                'content': {'application/json': {'schema': ref('Run')}},
                'headers': {'X-Run-Id': {'schema': ID, 'description': '原运行 ID'},
                            'Location': {'schema': string(), 'description': '同源项目运行查询路径'}}}
    call = obj({'method': string(minLength=1, maxLength=64), 'args': {**arr(JSON_VALUE), 'maxItems': 128},
        'undefinedArgs': {**arr({'type': 'integer', 'minimum': 0, 'maximum': 127}), 'maxItems': 128}}, ['method', 'args'])
    SCHEMAS['FfmpegExecute'] = obj({'requestId': string(format='uuid'), 'options': obj({'source': ref('RelativePath'),
        'cwd': ref('RelativePath'), 'niceness': {'type': 'integer', 'minimum': -20, 'maximum': 20},
        'priority': {'type': 'integer', 'minimum': -20, 'maximum': 20}, 'stdoutLines': {'type': 'integer', 'minimum': 0},
        'timeout': {'type': 'number', 'minimum': 0}}), 'calls': {**arr(call), 'maxItems': 2048}, 'operation': call},
        ['requestId', 'options', 'calls', 'operation'])
    ff = operation('post', creative + 'ffmpeg/execute', 'executeFfmpeg', '隔离环境中的 FFmpeg 调用/prepare/取消', project,
        ['A10', 'A18'], ref('FfmpegExecute'), ref('LegacyResult'),
        description='必须先通过项目文件系统/网络隔离及资源限制验收；未实现隔离不得开放。requestId 绑定发起人+项目；取消等待进程结束。')
    ff['responses']['200']['content']['text/event-stream'] = {'schema': string(),
        'x-event-schema': obj({'event': string(enum=['start', 'progress', 'end', 'error', 'codecData', 'stderr']), 'args': arr(JSON_VALUE)}, ['event', 'args'])}
    routes = json.loads((API / 'upstream/route-details.json').read_text(encoding='utf-8-sig'))['routes']
    mapping = []
    for source in routes:
        path = source['path'][5:]
        disposition = source['disposition']
        target = None
        if disposition == '项目创作适配':
            target = p + '/projects/{projectId}/creative/' + path
        elif disposition == '成员安全视图':
            target = p + '/capabilities/' + path
        elif disposition == '管理员适配':
            target = p + '/admin/' + path
        elif disposition == '个人配置适配':
            target = p + '/preferences/personalization'
        elif disposition == '替换项目入口':
            target = p + '/projects'
        fields = [{'in': block['location'], 'fields': [field for field in block.get('fields', [])
            if field.get('name') not in ['directory', 'absolutePath']], 'sourceExpression': block.get('resolvedExpression', block['expression'])}
            for block in source['validation']]
        group = path.split('/')[0]
        acceptance = {'agent': ['A09', 'A16', 'A18'], 'singleAgent': ['A09', 'A16', 'A18'],
            'workspaces': ['A03', 'A04', 'A05', 'A06', 'A17'], 'assets': ['A03', 'A06', 'A17'],
            'ai': ['A07', 'A08', 'A11', 'A16', 'A18'], 'ffmpeg': ['A10', 'A18'],
            'mcp': ['A13', 'A18']}.get(group, ['A02', 'A11', 'A12', 'A14'])
        target_op = SPEC['paths'].get(target, {}).get(source['method'].lower(), {})
        mapping.append({'id': source['id'], 'sourceMethod': source['method'], 'sourcePath': source['path'],
            'sourceFile': source['sourceFile'], 'sourceSha256': source['sourceSha256'],
            'callSites': source['clientCallSites'], 'disposition': disposition, 'targetMethod': source['method'] if target else None,
            'targetPath': target, 'roles': source['targetRoles'], 'projectRule': source['targetEntry'],
            'slot': source['projectSlot'], 'csrf': bool(target and source['method'] not in ['GET', 'HEAD']),
            'idempotency': 'required' if path in ['agent', 'ai/media/generate', 'singleAgent/run'] else 'no-auto-retry',
            'request': fields, 'responseSource': source['responseExpressions'],
            'baselineSampleId': source['baselineSampleId'], 'acceptance': acceptance,
            'requirements': sorted({fr for a in acceptance for fr in FR_BY_ACCEPTANCE[a]}),
            'contractStatus': 'excluded' if disposition == '排除' else 'internal' if disposition == '仅内部诊断' else target_op.get('x-contract-status', 'proposed'),
            'publicEnabled': False})
    contract = yaml.safe_dump(SPEC, allow_unicode=True, sort_keys=False, width=120)
    mapping_text = json.dumps({'fixedCommit': 'd2e94aca7b59659ff6b4ca15f03cf134ec7f0e1d',
        'description': '目标显式映射及源字段；proposed 不表示 DTO 全部冻结，更不是可公开白名单。', 'routes': mapping},
        ensure_ascii=False, indent=2) + '\n'
    if args.write:
        (API / 'openapi.yaml').write_text(contract, encoding='utf-8')
        (API / 'route-map.json').write_text(mapping_text, encoding='utf-8')
        print(f'Generated {len(SPEC["paths"])} baseline paths and {len(mapping)} route mappings '
              f'into the committed contract (--write)')
    else:
        target = API / 'generated'
        target.mkdir(exist_ok=True)
        (target / 'openapi.baseline.yaml').write_text(contract, encoding='utf-8')
        (target / 'route-map.baseline.json').write_text(mapping_text, encoding='utf-8')
        print(f'Baseline written to {target}: {len(SPEC["paths"])} paths, {len(mapping)} mappings; '
              'committed docs/api/openapi.yaml remains the source of truth')


if __name__ == '__main__':
    main()
