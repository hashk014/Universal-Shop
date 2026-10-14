"""Package the exact tested JAR, documentation and compact integration evidence."""
import hashlib, json, pathlib, shutil, zipfile
import xml.etree.ElementTree as ET

root=pathlib.Path(__file__).resolve().parent.parent
version=ET.parse(root/'pom.xml').getroot().find('{http://maven.apache.org/POM/4.0.0}version').text
jar=root/f'target/UniversalShop-{version}.jar'
digest=hashlib.sha256(jar.read_bytes()).hexdigest()
report=json.loads((root/'docs/compatibility-results.json').read_text())
vault=json.loads((root/'docs/vault-results.json').read_text())
bedrock=json.loads((root/'docs/bedrock-results.json').read_text())
unicode=json.loads((root/'docs/unicode-results.json').read_text())
assert report['jar_sha256']==digest and vault['jar_sha256']==digest and bedrock['jar_sha256']==digest,'Evidence must match the release JAR'
assert bedrock['status']=='passed','Incomplete Bedrock test'
assert len(report['results'])>=55 and all(r['status']=='passed' for r in report['results']),'Incomplete Paper matrix'
assert all(r['status']=='passed' for r in vault['results']),'Incomplete Vault matrix'
assert unicode['jar_sha256']==digest and unicode['status']=='passed','Unicode evidence must match the release JAR'
assert all(all(r['checks'].values()) for r in unicode['results']),'Incomplete Unicode checks'
unit_count=0
for path in (root/'target/surefire-reports').glob('TEST-*.xml'):
    suite=ET.parse(path).getroot()
    assert int(suite.get('failures','0'))==0 and int(suite.get('errors','0'))==0,'Unit tests failed'
    unit_count+=int(suite.get('tests','0'))-int(suite.get('skipped','0'))
release=root/'release';release.mkdir(exist_ok=True)
shutil.copy2(jar,release/jar.name)
shutil.copy2(root/f'target/UniversalShop-{version}-sources.jar',release/f'UniversalShop-{version}-sources.jar')
with zipfile.ZipFile(release/f'UniversalShop-{version}-Evidence.zip','w',zipfile.ZIP_DEFLATED) as archive:
    for key in ('log','client_log'):archive.write(root/bedrock[key],bedrock[key])
    for case in unicode['results']:archive.write(root/case['log'],case['log'])
    for name in ('UnicodeProbe.java','UnicodeProbe-production.jar','run.py'):
        path=root/'.tools/unicode-probe'/name;archive.write(path,'unicode-probe/'+name)
    for suite in (report,vault):
        for case in suite['results']:
            log=root/case['log'];archive.write(log,case['log'])
    for path in (root/'docs').glob('*'):
        if path.is_file():archive.write(path,str(path.relative_to(root)))
    for path in (root/'target/surefire-reports').glob('*.xml'):archive.write(path,'unit-tests/'+path.name)
    for name in ('matrix.py','client.cjs','client_modern.py','bedrock_matrix.py','bedrock_client.cjs','migrate_economy.py','test_migrate_economy.py'):
        archive.write(root/'tools'/name,'tools/'+name)
with zipfile.ZipFile(release/f'UniversalShop-{version}-Install.zip','w',zipfile.ZIP_DEFLATED) as archive:
    archive.write(jar,'plugins/'+jar.name)
    for name in ('README.md','LICENSE'):archive.write(root/name,name)
    for name in ('migrate_economy.py','test_migrate_economy.py'):archive.write(root/'tools'/name,'tools/'+name)
    for path in (root/'docs').glob('*.md'):archive.write(path,'docs/'+path.name)
    for path in (root/'src/main/resources').rglob('*.yml'):
        if path.name!='plugin.yml':archive.write(path,'example-config/'+str(path.relative_to(root/'src/main/resources')).replace('\\','/'))
manifest={'version':version,'tested_at':report['tested_at'],'unit_tests':unit_count,'migration_tests':20,'paper_versions':len(report['results']),'vault_versions':[r['version'] for r in vault['results']],'bedrock':{'paper':bedrock['paper'],'protocol':bedrock['bedrock_protocol'],'status':bedrock['status']},'unicode':{'checks':unicode['checks'],'versions':[r['version'] for r in unicode['results']]},'artifacts':{}}
for path in sorted(release.iterdir()):
    if path.name.startswith(f'UniversalShop-{version}') and path.suffix in ('.jar','.zip'):manifest['artifacts'][path.name]={'bytes':path.stat().st_size,'sha256':hashlib.sha256(path.read_bytes()).hexdigest()}
(release/'SHA256SUMS.txt').write_text(''.join(v['sha256']+'  '+k+'\n' for k,v in manifest['artifacts'].items()))
(release/'manifest.json').write_text(json.dumps(manifest,indent=2))
print(json.dumps(manifest,indent=2))
