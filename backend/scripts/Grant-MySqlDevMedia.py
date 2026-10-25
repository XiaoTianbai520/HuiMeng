"""Server-side, additive DML grant for V4 on the independent development DB only."""
import json
import subprocess


def sql(query):
    result = subprocess.run(['docker', 'exec', '-i', 'mysql', 'sh', '-c',
                             'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql -uroot -N'],
                            input=query, text=True, capture_output=True)
    if result.returncode:
        raise SystemExit('Development media grant failed; output suppressed.')
    return result.stdout.strip()


def main():
    if sql("SELECT COUNT(*) FROM huimeng_dev.flyway_schema_history WHERE version='4' AND success=1;") != '1':
        raise SystemExit('V4 must be applied before granting runtime DML.')
    hosts = sql("SELECT Host FROM mysql.user WHERE User='huimeng_dev_app';").splitlines()
    if not hosts or not set(hosts) <= {'192.168.1.100', '172.20.0.1'}:
        raise SystemExit('Unexpected development identity sources; inspect instead of broadening access.')
    for host in hosts:
        sql("GRANT SELECT,INSERT,UPDATE,DELETE ON huimeng_dev.project_media TO 'huimeng_dev_app'@'" + host + "';")
    print(json.dumps({'developmentSchema': 'huimeng_dev', 'migrationVersion': 4,
                      'runtimeTable': 'project_media', 'runtimeDDLGranted': False,
                      'productionChanged': False}))


if __name__ == '__main__':
    main()
