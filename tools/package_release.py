"""Package the exact tested JAR, documentation and compact integration evidence."""
import hashlib, json, pathlib, shutil, zipfile

root=pathlib.Path(__file__).resolve().parent.parent
jar=root/'target/UniversalShop-1.0.0.jar'
digest=hashlib.sha256(jar.read_bytes()).hexdigest()
report=json.loads((root/'docs/compatibility-results.json').read_text())
vault=json.loads((root/'docs/vault-results.json').read_text())
assert report['jar_sha256']==digest and vault['jar_sha256']==digest,'Evidence must match the release JAR'
assert len(report['results'])>=55 and all(r['status']=='passed' for r in report['results']),'Incomplete Paper matrix'
assert all(r['status']=='passed' for r in vault['results']),'Incomplete Vault matrix'
release=root/'release';release.mkdir(exist_ok=True)
shutil.copy2(jar,release/jar.name)
shutil.copy2(root/'target/UniversalShop-1.0.0-sources.jar',release/'UniversalShop-1.0.0-sources.jar')
with zipfile.ZipFile(release/'UniversalShop-1.0.0-Evidence.zip','w',zipfile.ZIP_DEFLATED) as archive:
    for suite in (report,vault):
        for case in suite['results']:
            log=root/case['log'];archive.write(log,case['log'])
    for path in (root/'docs').glob('*'):
        if path.is_file():archive.write(path,str(path.relative_to(root)))
    for path in (root/'target/surefire-reports').glob('*.xml'):archive.write(path,'unit-tests/'+path.name)
with zipfile.ZipFile(release/'UniversalShop-1.0.0-Install.zip','w',zipfile.ZIP_DEFLATED) as archive:
    archive.write(jar,'plugins/'+jar.name)
    for name in ('README.md','LICENSE'):archive.write(root/name,name)
    for path in (root/'docs').glob('*.md'):archive.write(path,'docs/'+path.name)
    for path in (root/'src/main/resources').glob('*.yml'):
        if path.name!='plugin.yml':archive.write(path,'example-config/'+path.name)
manifest={'version':'1.0.0','tested_at':report['tested_at'],'paper_versions':len(report['results']),'vault_versions':[r['version'] for r in vault['results']],'artifacts':{}}
for path in sorted(release.iterdir()):
    if path.suffix in ('.jar','.zip'):manifest['artifacts'][path.name]={'bytes':path.stat().st_size,'sha256':hashlib.sha256(path.read_bytes()).hexdigest()}
(release/'SHA256SUMS.txt').write_text(''.join(v['sha256']+'  '+k+'\n' for k,v in manifest['artifacts'].items()))
(release/'manifest.json').write_text(json.dumps(manifest,indent=2))
print(json.dumps(manifest,indent=2))
