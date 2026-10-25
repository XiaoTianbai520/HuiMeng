"""Apply versioned migrations to the existing independent development database.

Run from backend/. Credentials come only from its ignored, protected runtime
directory. Production is explicitly refused; logs stay in that same directory.
"""
import os
from pathlib import Path
import re
import shutil
import subprocess
from urllib.parse import urlencode


def main():
    root = Path(__file__).resolve().parents[1]
    protected = root / '.runtime/mysql-dev'
    environment = os.environ.copy()
    for line in (protected / 'migration.env').read_text().splitlines():
        key, value = line.split('=', 1)
        if key not in ['HM_DB_URL', 'HM_MIGRATION_USER', 'HM_MIGRATION_PASSWORD']:
            raise SystemExit('Unexpected migration setting; no database connection made.')
        environment[key] = value
    settings = dict(line.split('=', 1) for line in (protected / 'application.properties').read_text().splitlines()
                    if '=' in line and not line.startswith('#'))
    environment['HM_DB_URL'] = settings['spring.datasource.url']
    prefix = 'spring.datasource.hikari.data-source-properties.'
    tls = {key: settings[prefix + key] for key in ['trustCertificateKeyStoreUrl',
           'trustCertificateKeyStoreType', 'trustCertificateKeyStorePassword']}
    environment['HM_DB_URL'] += '&' + urlencode(tls)
    if not re.match(r'^jdbc:mysql://192\.168\.1\.102:3306/huimeng_dev\?', environment['HM_DB_URL']) or 'sslMode=VERIFY_CA' not in environment['HM_DB_URL']:
        raise SystemExit('Only the independent huimeng_dev schema is allowed.')
    jar = root / 'target/huimeng-api-1.0.0-SNAPSHOT.jar'
    for attempt in [1, 2]:
        result = subprocess.run([shutil.which('java'), '-jar', str(jar), '--migrate-only'],
                                cwd=root, env=environment, capture_output=True, timeout=90)
        output = result.stdout + result.stderr
        # Flyway/JDBC diagnostics can repeat the URL, including driver TLS secrets.
        for value in [environment['HM_DB_URL'], environment['HM_MIGRATION_PASSWORD'],
                      tls['trustCertificateKeyStorePassword']]:
            if value:
                output = output.replace(value.encode(), b'[redacted]')
                output = output.replace(urlencode({'v': value})[2:].encode(), b'[redacted]')
        (protected / f'storage-migration-{attempt}.log').write_bytes(output)
        if result.returncode:
            raise SystemExit('Development migration failed; inspect protected log without printing credentials.')
        print(f'development_migration_attempt_{attempt}=PASS')
    print('production_database_targeted=false')


if __name__ == '__main__':
    main()
