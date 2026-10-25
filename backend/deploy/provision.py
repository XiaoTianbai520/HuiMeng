#!/usr/bin/env python3
"""Provision new HuiMeng-only state. Run as root after inspecting the existing host.

No credential is read from chat or printed. Existing targets cause a fail-closed exit.
"""
import base64
import os
from pathlib import Path
import secrets
import subprocess

APP = Path('/opt/apps/huimeng')
TARGETS = [APP, Path('/data/huimeng'), Path('/var/www/huimeng'),
           Path('/var/log/apps/huimeng'), Path('/data/backup/huimeng')]


def mysql(sql):
    return subprocess.run(
        ['docker', 'exec', '-i', 'mysql', 'sh', '-c',
         'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql -uroot -N'],
        input=sql, text=True, check=True, capture_output=True).stdout


def main():
    if os.geteuid() != 0:
        raise SystemExit('Root required for new application directories')
    if any(path.exists() for path in TARGETS):
        raise SystemExit('Existing HuiMeng target; inspect and resume manually, never overwrite')
    existing = mysql("SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='huimeng'; "
                     "SELECT User FROM mysql.user WHERE User IN ('huimeng_app','huimeng_migrate');")
    if existing.strip():
        raise SystemExit('Existing HuiMeng schema/account; no changes made')
    engine = mysql('SELECT @@default_storage_engine;').strip()
    if engine != 'InnoDB':
        raise SystemExit('InnoDB required')
    os.umask(0o077)
    for path in TARGETS:
        path.mkdir(parents=True, mode=0o750)
    # Public build artifacts need directory traversal for the Nginx worker.
    Path('/var/www/huimeng').chmod(0o755)
    private = APP / 'secrets'
    private.mkdir(mode=0o700)
    for name in ['master.key', 'internal.key']:
        (private / name).write_text(base64.b64encode(secrets.token_bytes(32)).decode() + '\n')
    app_password, migration_password = secrets.token_hex(32), secrets.token_hex(32)
    (private / 'bootstrap-password').write_text(secrets.token_urlsafe(32) + '\n')
    # Credentials remain on the existing host and are passed through stdin, not argv or logs.
    sql = f"""CREATE DATABASE huimeng CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
CREATE USER 'huimeng_app'@'172.29.46.%' IDENTIFIED BY '{app_password}';
CREATE USER 'huimeng_migrate'@'172.29.46.%' IDENTIFIED BY '{migration_password}';
GRANT SELECT,INSERT,UPDATE,DELETE ON huimeng.* TO 'huimeng_app'@'172.29.46.%';
GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,INDEX,REFERENCES ON huimeng.* TO 'huimeng_migrate'@'172.29.46.%';
"""
    mysql(sql)
    (private / 'api.env').write_text(
        'HM_DB_URL=jdbc:mysql://mysql:3306/huimeng?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true\n'
        f'HM_DB_USER=huimeng_app\nHM_DB_PASSWORD={app_password}\n'
        'HM_MASTER_KEY_FILE=/run/huimeng-secrets/master.key\n'
        'HM_INTERNAL_KEY_FILE=/run/huimeng-secrets/internal.key\n'
        'HM_BOOTSTRAP_USERNAME=huimeng-admin\n'
        'HM_BOOTSTRAP_PASSWORD_FILE=/run/huimeng-secrets/bootstrap-password\n')
    (private / 'migration.env').write_text(
        'HM_DB_URL=jdbc:mysql://mysql:3306/huimeng?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true\n'
        f'HM_MIGRATION_USER=huimeng_migrate\nHM_MIGRATION_PASSWORD={migration_password}\n')
    workspaces = Path('/data/huimeng/workspaces')
    workspaces.mkdir(mode=0o750)
    os.chown(workspaces, 10001, 10001)
    # Bind-mounted secrets are readable only by the application uid and host root.
    for path in [private, *private.iterdir()]:
        os.chown(path, 10001, 10001)
    print('Created HuiMeng-only schema, scoped accounts, directories and protected secret files')


if __name__ == '__main__':
    try:
        main()
    except subprocess.CalledProcessError:
        raise SystemExit('Provisioning command failed; inspect state before retrying (no secret output)')
