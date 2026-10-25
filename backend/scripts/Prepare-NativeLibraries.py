"""Extract pinned JNA bootstrap from the built JAR for read-only/noexec-tmp Linux images."""
from pathlib import Path
from io import BytesIO
import hashlib
import json
import zipfile

ROOT = Path(__file__).resolve().parents[1]
JAR = ROOT/'target/huimeng-api-1.0.0-SNAPSHOT.jar'
ENTRY = 'com/sun/jna/linux-x86-64/libjnidispatch.so'
with zipfile.ZipFile(JAR) as application:
    dependency = application.read('BOOT-INF/lib/jna-5.17.0.jar')
    with zipfile.ZipFile(BytesIO(dependency)) as native_jar:
        library = native_jar.read(ENTRY)
target = ROOT/'target/native'
target.mkdir(parents=True, exist_ok=True)
(target/'libjnidispatch.so').write_bytes(library)
(target/'libjnidispatch.so').chmod(0o644)
manifest = {'dependency':'net.java.dev.jna:jna:5.17.0', 'platform':'linux-x86-64',
            'sourceEntry':ENTRY, 'sha256':hashlib.sha256(library).hexdigest(),
            'chosenLicense':'Apache-2.0', 'sourceJarSha256':hashlib.sha256(dependency).hexdigest()}
manifest['supportingLegalTexts'] = []
for name, source in [('JNA-AL2.0.txt', 'https://raw.githubusercontent.com/java-native-access/jna/5.17.0/AL2.0'),
                     ('LIBFFI-LICENSE.txt', 'https://raw.githubusercontent.com/java-native-access/jna/5.17.0/native/libffi/LICENSE')]:
    text = (ROOT/'licenses/texts/jna-5.17.0.jar'/name).read_bytes()
    (target/name).write_bytes(text)
    manifest['supportingLegalTexts'].append({'path':'texts/jna-5.17.0.jar/'+name, 'source':source,
                                           'sha256':hashlib.sha256(text).hexdigest()})
(ROOT/'licenses/native-runtime.json').write_text(json.dumps(manifest,indent=2)+'\n',encoding='utf-8')
print(json.dumps({'prepared':True,'platform':manifest['platform'],'sha256':manifest['sha256']}))
