"""Opt-in loopback checks for WorkspaceBrowserAcceptanceHarness, synthetic data only."""
import hashlib
import json
from pathlib import Path
import uuid
from urllib.parse import urlencode
import m2MinioDev as fixture

ROOT=Path(__file__).resolve().parents[3]

def main():
    fixture.PORT=18086
    fixture.CHECKS.clear()
    checks=[]
    def check(ok,name):
        if not ok: raise RuntimeError('Workspace check failed: '+name)
        checks.append(name)
    admin,member,outsider=[fixture.Session('http://localhost:5187') for _ in range(3)]
    admin.login('ui-admin','M3-browser-fixture-only')
    member_id=member.login('ui-member','M3-browser-fixture-only')
    outsider.login('ui-outsider','M3-browser-fixture-only')
    status,_,raw=admin.write('POST','/api/v1/projects',{'name':'M3-http-'+uuid.uuid4().hex[:8]})
    check(status==201,'createSyntheticProject')
    project=json.loads(raw)['id']
    check(admin.write('POST','/api/v1/projects/'+project+'/members',{'userId':member_id})[0]==201,'addSyntheticMembership')
    base='/api/v1/projects/'+project+'/creative/workspaces/files/'
    def read(path): return member.call('GET',base+'read?'+urlencode({'path':path}))
    def write(path,data,**query): return member.write('PUT',base+'write?'+urlencode({'path':path,**query}),data,True)
    def listing(path=''):
        status,_,raw=member.call('GET',base+'list?'+urlencode({'path':path}))
        check(status==200,'authorizedList')
        check(b'/opt/apps/' not in raw and b'workspace_key' not in raw,'noPhysicalPath')
        return json.loads(raw)['data']
    check(listing()['directory']==project,'listDirectoryIsProjectId')
    check(outsider.call('GET',base+'list')[0]==403,'outsiderList403')
    check(admin.call('GET',base+'list')[0]==403,'adminNonMember403')
    check(member.call('GET',base+'list?path=..')[0]==400,'parentTraversal400')
    check(member.write('POST',base+'mkdir',{'path':'素材'})[0]==200,'mkdir')
    check(member.write('POST',base+'mkdir',{'path':'素材'})[0]==409,'mkdirNoOverwrite')
    check(member.write('POST',base+'mkdir',{'path':'素材/分镜'})[0]==200,'nestedMkdir')
    payload='原格式剧本\r\n人物：小白\n'.encode()
    status,_,raw=write('素材/分镜/剧本.md',payload,exclusive='true',encoding='utf-8')
    revision=json.loads(raw)['data']['revision']
    check(status==200 and revision==hashlib.sha256(payload).hexdigest(),'createOriginalFormat')
    check(member.write('POST',base+'copy',{'path':'素材','target':'素材副本'})[0]==200,'copyDirectoryTree')
    check(read('素材副本/分镜/剧本.md')[2]==payload,'copiedExactBytes')
    check(member.write('POST',base+'copy',{'path':'素材','target':'素材副本'})[0]==409,'copyExistingTarget409')
    check(member.write('POST',base+'copy',{'path':'素材','target':'素材/自身副本'})[0]==400,'copyIntoSelf400')
    check(member.write('POST',base+'rename',{'path':'素材副本','target':'移动后'})[0]==200,'renameDirectoryTree')
    check(read('移动后/分镜/剧本.md')[2]==payload,'renamedExactBytes')
    check(member.write('POST',base+'rename',{'path':'移动后','target':'素材'})[0]==409,'renameExistingTarget409')
    check(read('素材/分镜/剧本.md')[2]==payload,'existingTargetPreserved')
    check(member.write('DELETE',base+'remove',{'path':'移动后'})[0]==409,'nonRecursiveDirectoryNotEmpty409')
    check(read('移动后/分镜/剧本.md')[2]==payload,'nonRecursiveDeletePreservedTree')
    check(member.write('DELETE',base+'remove',{'path':'移动后','recursive':True})[0]==200,'recursiveDelete')
    check(read('移动后/分镜/剧本.md')[0]==404,'deletedContent404')
    check(member.write('POST',base+'rename',{'path':'素材/分镜/剧本.md','target':'剧本.md'})[0]==200,'moveFileAcrossDirectories')
    check(member.write('POST',base+'copy',{'path':'剧本.md','target':'副本.md'})[0]==200,'copyFile')
    check(read('副本.md')[2]==payload,'copyFileHash')
    check(member.write('DELETE',base+'remove',{'path':'副本.md'})[0]==200,'deleteFile')
    check(member.write('DELETE',base+'remove',{'path':''})[0]==400,'cannotRemoveProjectRoot')
    check(member.write('POST',base+'mkdir',{'path':'.huimeng-private'})[0]==400,'reservedNamespace400')
    check(member.write('POST',base+'mkdir',{'path':'normal','directory':'/tmp'})[0]==400,'legacyDirectoryRejected')
    check(not any(x['name'].startswith('.huimeng-') for x in listing()['entries']),'noTemporaryItemsPublished')
    check(admin.write('DELETE','/api/v1/projects/'+project+'/members/'+member_id)[0]==204,'revokeMembership')
    check(member.call('GET',base+'list')[0]==403,'revokedList403')
    check(member.write('POST',base+'copy',{'path':'剧本.md','target':'forbidden.md'})[0]==403,'revokedCopy403')
    check(member.write('DELETE',base+'remove',{'path':'剧本.md'})[0]==403,'revokedDelete403')
    check(admin.write('PATCH','/api/v1/projects/'+project,{'status':'ARCHIVED'})[0]==200,'archiveSyntheticProject')
    result={'passed':True,'checks':checks,'checkCount':len(checks),'fixture':'explicit Linux/H2 workspace browser fixture through loopback SSH forwarding','productionDeploymentChanged':False,'realStorageOrModelUsed':False,'credentialsIncluded':False}
    (ROOT/'docs/validation/evidence/m3-workspace-http.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'passed':True,'checks':len(checks)}))

if __name__=='__main__': main()
