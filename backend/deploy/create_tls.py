#!/usr/bin/env python3
"""Create a fresh internal CA and server certificate with strict X.509 key usage."""
from pathlib import Path
import os
import subprocess

TLS = Path('/opt/apps/huimeng/secrets/tls')


def openssl(*args):
    subprocess.run(['openssl', *args], cwd=TLS, check=True, capture_output=True)


def main():
    if any((TLS / name).exists() for name in ['ca.key', 'ca.crt', 'server.key', 'server.crt']):
        raise SystemExit('Existing TLS material; no overwrite')
    os.umask(0o077)
    TLS.mkdir(parents=True, exist_ok=True, mode=0o700)
    openssl('req', '-x509', '-newkey', 'rsa:3072', '-sha256', '-nodes', '-keyout', 'ca.key', '-out', 'ca.crt', '-days', '365', '-subj', '/CN=HuiMeng-M2-Internal-CA', '-addext', 'basicConstraints=critical,CA:TRUE', '-addext', 'keyUsage=critical,keyCertSign,cRLSign')
    openssl('req', '-newkey', 'rsa:2048', '-nodes', '-keyout', 'server.key', '-out', 'server.csr', '-subj', '/CN=192.168.1.102')
    (TLS / 'server.ext').write_text('subjectAltName=IP:192.168.1.102\nbasicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\nsubjectKeyIdentifier=hash\nauthorityKeyIdentifier=keyid,issuer\n')
    openssl('x509', '-req', '-in', 'server.csr', '-CA', 'ca.crt', '-CAkey', 'ca.key', '-CAcreateserial', '-out', 'server.crt', '-days', '180', '-sha256', '-extfile', 'server.ext')
    openssl('verify', '-x509_strict', '-CAfile', 'ca.crt', 'server.crt')
    print('Created strictly validated internal CA and LAN certificate; private keys stay on server')


if __name__ == '__main__':
    main()
