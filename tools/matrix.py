"""Reproducible isolated Paper tests with two network clients and a full server restart.
Cached server binaries may be imported; no worlds or plugin state are imported.
"""
import argparse, concurrent.futures, hashlib, json, os, pathlib, queue, re, shutil, socket, subprocess, sys, threading, time, urllib.request
from datetime import datetime, timezone

ROOT = pathlib.Path(__file__).resolve().parent.parent
import xml.etree.ElementTree as ET
VERSION=ET.parse(ROOT/'pom.xml').getroot().find('{http://maven.apache.org/POM/4.0.0}version').text
JAR = ROOT/f'target/UniversalShop-{VERSION}.jar'

def java_version(version):
    if version.startswith('26.'): return 25
    parts=tuple(map(int,version.split('.')))
    if parts<(1,17): return 8
    if version=='1.17': return 16
    return 17 if parts<(1,20,5) else 21

def fetch(url):
    request=urllib.request.Request(url,headers={'User-Agent':'UniversalShop/1.0 (https://github.com/hashk014/Universal-Shop)'})
    return urllib.request.urlopen(request,timeout=120).read()

def prepare(version,args):
    folder=ROOT/'test-servers'/(version+('-vault' if args.vault_fixtures else ''))
    folder.mkdir(parents=True,exist_ok=True)
    source=pathlib.Path(args.cache)/version if args.cache else folder
    if source!=folder and (source/'server.jar').exists():
        for name in ('server.jar','download.json'):
            if (source/name).exists(): shutil.copy2(source/name,folder/name)
        for name in ('cache','libraries','versions'):
            if (source/name).exists(): shutil.copytree(source/name,folder/name,dirs_exist_ok=True)
    if not (folder/'server.jar').exists() or args.refresh:
        builds=json.loads(fetch('https://fill.papermc.io/v3/projects/paper/versions/'+version+'/builds'))
        metadata=next((b for b in builds if b['channel']=='STABLE'),builds[0])
        download=metadata['downloads']['server:default']
        if not (folder/'server.jar').exists() or hashlib.sha256((folder/'server.jar').read_bytes()).hexdigest()!=download['checksums']['sha256']:
            data=fetch(download['url'])
            assert hashlib.sha256(data).hexdigest()==download['checksums']['sha256']
            (folder/'server.jar').write_bytes(data)
        (folder/'download.json').write_text(json.dumps(metadata,indent=2))
    metadata=json.loads((folder/'download.json').read_text())
    assert hashlib.sha256((folder/'server.jar').read_bytes()).hexdigest()==metadata['downloads']['server:default']['checksums']['sha256']
    return folder,metadata

def scenario(version,args):
    started=time.monotonic()
    folder,metadata=prepare(version,args)
    plugins=folder/'plugins'
    plugins.mkdir(exist_ok=True)
    for previous in plugins.glob('UniversalShop-*.jar'):
        if previous.name!=JAR.name:previous.unlink()
    shutil.copy2(JAR,plugins/JAR.name)
    if args.vault_fixtures:
        for jar in pathlib.Path(args.vault_fixtures).glob('*.jar'):shutil.copy2(jar,plugins/jar.name)
    for name in ('test-world','test-world_nether','test-world_the_end','plugins/UniversalShop'):
        target=(folder/name).resolve()
        assert target.is_relative_to((ROOT/'test-servers').resolve())
        if target.exists(): shutil.rmtree(target)
    if args.vault_fixtures:
        data=plugins/'UniversalShop';data.mkdir(exist_ok=True)
        config=(ROOT/'src/main/resources/config.yml').read_text().replace('economy: INTERNAL','economy: VAULT')
        (data/'config.yml').write_text(config)
    with socket.socket() as reservation:
        reservation.bind(('127.0.0.1',0));port=reservation.getsockname()[1]
    (folder/'eula.txt').write_text('eula=true\n')
    (folder/'server.properties').write_text(f'server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\nenforce-secure-profile=false\nwhite-list=false\nlevel-name=test-world\nlevel-type=flat\nspawn-protection=0\nview-distance=2\nsimulation-distance=2\nmax-players=4\nallow-nether=false\nspawn-npcs=false\nspawn-monsters=false\nspawn-animals=false\ngenerate-structures=false\nnetwork-compression-threshold=-1\nallow-flight=true\n')
    (folder/'bukkit.yml').write_text('settings:\n  allow-end: false\n  connection-throttle: 0\nspawn-limits:\n  monsters: 0\n  animals: 0\n  water-animals: 0\n  ambient: 0\n')
    (folder/'spigot.yml').write_text('world-settings:\n  default:\n    verbose: false\n    view-distance: 2\n')
    (folder/'paper.yml').write_text('world-settings:\n  default:\n    keep-spawn-loaded: false\n    keep-spawn-loaded-range: 0\n')
    (folder/'config').mkdir(exist_ok=True)
    (folder/'config/paper-world-defaults.yml').write_text('spawn:\n  keep-spawn-loaded: false\n  keep-spawn-loaded-range: 0\n')
    java=pathlib.Path(args.runtimes)/f'jdk{java_version(version)}'/'bin'/('java.exe' if os.name=='nt' else 'java')
    command=[str(java),'-Xms128M','-Xmx768M','-XX:ActiveProcessorCount=2','-Dfile.encoding=UTF-8','-Duniversalshop.test=true','-jar','server.jar','nogui']
    logs=[];counts={};clients=[];client_files=[]
    def boot(restart):
        process=subprocess.Popen(command,cwd=folder,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf-8',errors='replace',bufsize=1)
        lines=queue.Queue()
        def pump():
            for line in process.stdout: logs.append(line);lines.put(line)
        thread=threading.Thread(target=pump,daemon=True);thread.start()
        def send(text): process.stdin.write(text+'\n');process.stdin.flush()
        def connect():
            for name in ('Hash014Test','Hash014Buyer'):
                output=open(folder/f'client-{name}-{int(restart)}.log','w',encoding='utf-8');client_files.append(output)
                if version in ('26.2','26.3'): cmd=[sys.executable,str(ROOT/'tools/client_modern.py'),str(port),version,name,str(plugins/'UniversalShop/protocol.json')]
                else: cmd=['node',str(ROOT/'tools/client.cjs'),str(port),version,name]
                env=os.environ.copy();env['USHOP_PROTOCOL_ROOT']=str(pathlib.Path(args.protocol).resolve())
                clients.append(subprocess.Popen(cmd,cwd=ROOT,stdout=output,stderr=subprocess.STDOUT,env=env))
        begin=time.monotonic();sent=False;connected=False;players=set();due=None;tested=False
        try:
            while time.monotonic()-begin<args.timeout:
                if due and time.monotonic()>=due and not tested: tested=True;send('shop test '+('restart' if restart else 'player'))
                try: line=lines.get(timeout=.3)
                except queue.Empty:
                    if process.poll() is not None: return 'boot-failed'
                    if clients and any(c.poll() is not None for c in clients): return 'client-failed'
                    continue
                if 'Done (' in line and not sent:
                    sent=True
                    if not restart: send('shop test self')
                    elif version in ('26.2','26.3'): send('shop test protocol')
                    else: connect();connected=True
                if 'SELFTEST PASS' in line:
                    counts['self']=int(re.search(r'checks=(\d+)',line).group(1))
                    if version in ('26.2','26.3'): send('shop test protocol')
                    else: connect();connected=True
                if 'PROTOCOL READY' in line and not connected: connect();connected=True
                if 'logged in with entity id' in line:
                    for name in ('Hash014Test','Hash014Buyer'):
                        if name in line: players.add(name)
                    if len(players)==2 and due is None: due=time.monotonic()+2
                if 'PLAYERTEST PASS' in line:
                    counts['player']=int(re.search(r'checks=(\d+)',line).group(1));return 'passed'
                if 'RESTART PASS' in line:
                    counts['restart']=int(re.search(r'checks=(\d+)',line).group(1));return 'passed'
                if 'TEST FAIL' in line or 'Could not safely start' in line or 'Unhandled exception executing command' in line or 'could not safely start' in line: return 'test-failed'
            return 'timeout'
        finally:
            if process.poll() is None:
                try:send('stop');process.wait(timeout=45)
                except (OSError,subprocess.TimeoutExpired):process.kill();process.wait()
            thread.join(timeout=5)
            for client in clients:
                if client.poll() is None: client.terminate();client.wait(timeout=10)
            clients.clear()
            for output in client_files:output.close()
            client_files.clear()
    status=boot(False)
    if status=='passed': status=boot(True)
    (folder/'universalshop-test.log').write_text(''.join(logs),encoding='utf-8')
    errors=[s.strip() for s in logs if any(x in s for x in ('TEST FAIL','Could not pass event','Error occurred while enabling UniversalShop','Error occurred while disabling UniversalShop','Unhandled exception executing command','NoSuchMethodError','NoClassDefFoundError'))]
    if errors:status='test-failed'
    return {'version':version,'economy':'VAULT' if args.vault_fixtures else 'INTERNAL','status':status,'checks':counts,'seconds':round(time.monotonic()-started,1),'java':java_version(version),'paper_build':metadata.get('id',metadata.get('build')),'paper_channel':metadata.get('channel'),'errors':errors,'log':str((folder/'universalshop-test.log').relative_to(ROOT)).replace('\\','/')}

if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('versions',nargs='*');parser.add_argument('--all',action='store_true');parser.add_argument('--refresh',action='store_true');parser.add_argument('--cache');parser.add_argument('--runtimes',default='.tools');parser.add_argument('--protocol',default='.tools/protocol');parser.add_argument('--workers',type=int,default=4);parser.add_argument('--timeout',type=int,default=240);parser.add_argument('--report',default='docs/compatibility-results.json')
    parser.add_argument('--vault-fixtures',help='Directory containing Vault and an economy provider JAR; uses separate version-vault test folders')
    args=parser.parse_args()
    versions=args.versions
    if args.all:
        project=json.loads(fetch('https://fill.papermc.io/v3/projects/paper'))
        versions=[v for group in project['versions'].values() for v in group if '-' not in v]
    if not versions:parser.error('Specify versions or --all')
    results=[]
    report=ROOT/args.report;report.parent.mkdir(exist_ok=True,parents=True)
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as pool:
        tasks={pool.submit(scenario,v,args):v for v in versions}
        for task in concurrent.futures.as_completed(tasks):
            try:result=task.result()
            except Exception as error:result={'version':tasks[task],'status':'harness-error','error':repr(error)}
            results.append(result);print(json.dumps(result),flush=True)
            report.write_text(json.dumps({'tested_at':datetime.now(timezone.utc).isoformat(),'jar_sha256':hashlib.sha256(JAR.read_bytes()).hexdigest(),'results':sorted(results,key=lambda r:tuple(map(int,r['version'].split('.'))))},indent=2),encoding='utf-8')
    sys.exit(0 if all(r['status']=='passed' for r in results) else 1)
