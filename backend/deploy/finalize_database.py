#!/usr/bin/env python3
"""Tighten runtime grants after migrations, without exposing root or application credentials.

表清单从 information_schema 现算，不再按版本写死：V7 起的 plugins/plugin_documents/market_*/app_documents、
V9 的 media_providers 都必须在授权范围内，否则应用能启动但登记内置节点、读写技能文档会报 SQL 语法错。
"""
import subprocess


def run(sql):
    return subprocess.run(['docker', 'exec', '-i', 'mysql', 'sh', '-c',
                           'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql -uroot -N'],
                          input=sql, text=True, capture_output=True, check=True).stdout


ACCOUNT = "'huimeng_app'@'172.29.46.%'"
READ_ONLY = {'flyway_schema_history'}
INSERT_DELETE_ONLY = {'audit_events'}


def main():
    latest = run(
        "SELECT version FROM huimeng.flyway_schema_history WHERE success=1 "
        "ORDER BY installed_rank DESC LIMIT 1;").strip()
    if not latest:
        raise SystemExit('Expected a migrated schema')
    tables = [line.strip() for line in run(
        "SELECT table_name FROM information_schema.tables "
        "WHERE table_schema='huimeng' AND table_type='BASE TABLE' ORDER BY table_name;").splitlines() if line.strip()]
    if 'plugins' not in tables or 'external_grants' not in tables:
        raise SystemExit('Schema does not look like the expected business schema')
    # 撤销初次构建时授予的整库 DML，改成逐表授权。
    current = run(
        "SELECT PRIVILEGE_TYPE FROM information_schema.SCHEMA_PRIVILEGES "
        "WHERE GRANTEE=CONCAT(CHAR(39),'huimeng_app',CHAR(39),'@',CHAR(39),'172.29.46.%',CHAR(39)) "
        "AND TABLE_SCHEMA='huimeng';")
    statements = []
    for privilege in ['INSERT', 'UPDATE', 'DELETE']:
        if privilege in current.splitlines():
            statements.append(f'REVOKE {privilege} ON huimeng.* FROM {ACCOUNT};')
    statements.append(f'GRANT SELECT ON huimeng.* TO {ACCOUNT};')
    for table in tables:
        if table in READ_ONLY:
            continue
        if table in INSERT_DELETE_ONLY:
            statements.append(f'GRANT INSERT,DELETE ON huimeng.{table} TO {ACCOUNT};')
        elif table == 'bootstrap_lock':
            statements.append(f'GRANT UPDATE ON huimeng.{table} TO {ACCOUNT};')
        else:
            statements.append(f'GRANT INSERT,UPDATE,DELETE ON huimeng.{table} TO {ACCOUNT};')
    run('\n'.join(statements))
    print(f'Runtime grants tightened at schema v{latest}: {len(tables)} tables; '
          'schema history read-only; audit cannot be updated')


if __name__ == '__main__':
    try:
        main()
    except subprocess.CalledProcessError:
        raise SystemExit('Database grant finalization failed; no secret output')
