"""Run as root on the verified server; create only HuiMeng development resources.

Never prints credentials. Existing resources without this provisioner's protected
record are refused. Does not modify MinIO's root identity, exposure or service.
"""
import json
import os
from pathlib import Path
import secrets
import subprocess
from urllib.parse import quote


def main():
    os.umask(0o077)
    target = Path('/opt/apps/huimeng-dev/secrets/object-storage.properties')
    if target.exists():
        raise SystemExit('Protected storage configuration already exists; inspect, do not replace it.')
    settings = {}
    for line in Path('/opt/middleware/minio/minio.env').read_text().splitlines():
        if '=' in line and not line.lstrip().startswith('#'):
            key, value = line.split('=', 1)
            settings[key.strip()] = value.strip().strip('\"\'')
    host = 'http://' + quote(settings['MINIO_ROOT_USER'], safe='') + ':' + quote(settings['MINIO_ROOT_PASSWORD'], safe='') + '@127.0.0.1:9000'

    def mc(*args, check=True):
        result = subprocess.run(['docker', 'exec', '-e', 'MC_HOST_bootstrap=' + host,
                                 'minio', 'mc', '--json', *args], capture_output=True)
        if check and result.returncode:
            raise SystemExit('MinIO provisioning step failed: ' + args[0] + '; inspect on server without printing secrets.')
        return result

    bucket = 'huimeng-dev-media'
    if mc('stat', 'bootstrap/' + bucket, check=False).returncode == 0:
        raise SystemExit('Target bucket already exists without protected ownership record; refusing to adopt it.')
    mc('mb', 'bootstrap/' + bucket)
    # A new bucket is private by default; never change existing bucket policies.
    access = 'huimeng-dev-' + secrets.token_hex(8)
    secret = secrets.token_urlsafe(40)
    policy_name = 'huimeng-dev-media-' + secrets.token_hex(4)
    policy = {'Version': '2012-10-17', 'Statement': [
        {'Effect': 'Allow', 'Action': ['s3:GetBucketLocation'], 'Resource': ['arn:aws:s3:::' + bucket]},
        {'Effect': 'Allow', 'Action': ['s3:PutObject', 's3:GetObject', 's3:DeleteObject'],
         'Resource': ['arn:aws:s3:::' + bucket + '/projects/*']}]}
    # Feed policy through stdin, avoiding an expanded environment or secret file dump.
    result = subprocess.run(['docker', 'exec', '-i', '-e', 'MC_HOST_bootstrap=' + host,
                             'minio', 'mc', 'admin', 'policy', 'create', 'bootstrap', policy_name, '/dev/stdin'],
                            input=json.dumps(policy).encode(), capture_output=True)
    if result.returncode:
        raise SystemExit('Policy creation failed; empty dedicated bucket remains for inspection.')
    mc('admin', 'user', 'add', 'bootstrap', access, secret)
    mc('admin', 'policy', 'attach', 'bootstrap', policy_name, '--user', access)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text('\n'.join([
        'huimeng.storage.enabled=true',
        'huimeng.storage.endpoint=http://192.168.1.102:18090',
        'huimeng.storage.region=us-east-1',
        'huimeng.storage.bucket=' + bucket,
        'huimeng.storage.access-key=' + access,
        'huimeng.storage.secret-key=' + secret, '']), encoding='utf-8')
    target.chmod(0o600)
    target.with_suffix('.policy.json').write_text(json.dumps(policy, indent=2) + '\n')
    print(json.dumps({'bucket': bucket, 'privateByDefault': True, 'dedicatedIdentity': True,
                      'credentialsFile': str(target), 'mode': '0600', 'rootIdentityUnchanged': True}))


if __name__ == '__main__':
    main()
