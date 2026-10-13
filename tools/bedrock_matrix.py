"""Real Bedrock protocol forms through Geyser. Offline, loopback-only disposable fixture."""
import argparse, hashlib, json, pathlib, queue, shutil, socket, subprocess, threading, time
from datetime import datetime, timezone
from matrix import ROOT, JAR

def run(args):
    folder=ROOT/'test-servers/26.2-bedrock';folder.mkdir(parents=True,exist_ok=True)
    source=ROOT/'test-servers/26.2'
    for name in ('server.jar','download.json'):shutil.copy2(source/name,folder/name)
    for name in ('cache','libraries','versions'):
        if (source/name).exists():shutil.copytree(source/name,folder/name,dirs_exist_ok=True)
    for name in ('test-world','test-world_nether','test-world_the_end','plugins/UniversalShop'):
        target=(folder/name).resolve();assert target.is_relative_to((ROOT/'test-servers').resolve())
        if target.exists():shutil.rmtree(target)
    plugins=folder/'plugins';plugins.mkdir(exist_ok=True)
    for old in plugins.glob('UniversalShop-*.jar'):old.unlink()
    shutil.copy2(JAR,plugins/JAR.name)
    fixtures=pathlib.Path(args.fixtures)
    for name in ('Geyser-Spigot.jar','floodgate-spigot.jar'):shutil.copy2(fixtures/name,plugins/name)
    def port(kind):
        with socket.socket(socket.AF_INET,kind) as s:s.bind(('127.0.0.1',0));return s.getsockname()[1]
    java_port=port(socket.SOCK_STREAM);bedrock_port=port(socket.SOCK_DGRAM)
    (folder/'eula.txt').write_text('eula=true\n')
    (folder/'server.properties').write_text(f'server-ip=127.0.0.1\nserver-port={java_port}\nonline-mode=false\nenforce-secure-profile=false\nlevel-name=test-world\nlevel-type=flat\nview-distance=2\nsimulation-distance=2\nallow-nether=false\nallow-flight=true\nspawn-protection=0\n')
    (folder/'bukkit.yml').write_text('settings:\n  allow-end: false\n  connection-throttle: 0\n')
    geyser=plugins/'Geyser-Spigot';geyser.mkdir(exist_ok=True)
    (geyser/'config.yml').write_text(f'bedrock:\n  address: 127.0.0.1\n  port: {bedrock_port}\n  clone-remote-port: false\n  transport: raknet\n  signaling:\n    mode: none\njava:\n  auth-type: offline\nadvanced:\n  bedrock:\n    validate-bedrock-login: false\n')
    java=pathlib.Path(args.runtimes)/'jdk25/bin/java.exe'
    server=subprocess.Popen([str(java),'-Xms128M','-Xmx1G','-XX:ActiveProcessorCount=2','-Duniversalshop.test=true','-jar','server.jar','nogui'],cwd=folder,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf-8',errors='replace')
    events=queue.Queue();logs=[];clientlogs=[];client=None;checks=0;forms=0;status='timeout';stage='browse';errors=[]
    def pump(proc,kind,out):
        for line in proc.stdout:out.append(line);events.put((kind,line))
    threading.Thread(target=pump,args=(server,'server',logs),daemon=True).start()
    def command(text):server.stdin.write(text+'\n');server.stdin.flush()
    def click(packet,prefix,duplicate=False):
        matches=[i for i,b in enumerate(packet['form']['buttons']) if b['text'].startswith(prefix)]
        assert matches, f'Missing {prefix}: {packet}'
        text=f"{packet['id']} {matches[0]}\n";client.stdin.write(text*(2 if duplicate else 1));client.stdin.flush()
    started=time.monotonic();start_due=None;pending=None
    try:
        while time.monotonic()-started<180:
            if start_due and time.monotonic()>start_due:command('shop test bedrock start');start_due=None
            if pending and time.monotonic()>pending[0]:command('shop test bedrock '+pending[1]);pending=None
            try:kind,line=events.get(timeout=.2)
            except queue.Empty:continue
            if kind=='server':
                if 'Done (' in line and client is None:
                    client=subprocess.Popen(['node',str(ROOT/'tools/bedrock_client.cjs'),str(bedrock_port)],cwd=ROOT,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf-8',errors='replace')
                    threading.Thread(target=pump,args=(client,'client',clientlogs),daemon=True).start()
                if 'ShopBedrock' in line and 'logged in with entity id' in line:start_due=time.monotonic()+5
                if 'TEST FAIL' in line:status='failed';break
                if 'BEDROCK PASS' in line:
                    checks+=int(line.split('checks=')[1].strip())
                    if 'phase=finish' in line:status='passed';break
            elif line.startswith('FORM '):
                packet=json.loads(line[5:]);forms+=1
                if stage=='browse':click(packet,'Stone');stage='select'
                elif stage=='select':click(packet,'Buy');stage='buy'
                elif stage=='buy':click(packet,'Confirm purchase',True);stage='sell';pending=(time.monotonic()+2,'bought')
                elif stage=='sell':click(packet,'Confirm sale',True);stage='disabled-browse';pending=(time.monotonic()+2,'sold')
                elif stage=='disabled-browse':click(packet,'Stone');stage='disabled-select'
                elif stage=='disabled-select':
                    assert not any(b['text'].startswith('Buy') for b in packet['form']['buttons']), 'Disabled buy button visible'
                    checks+=1;pending=(time.monotonic()+1,'finish');stage='done'
    except Exception as error:
        status='failed';errors.append(repr(error))
    finally:
        if client and client.poll() is None:client.terminate();client.wait(timeout=10)
        if server.poll() is None:
            command('stop')
            try:server.wait(timeout=45)
            except subprocess.TimeoutExpired:server.kill();server.wait()
        (folder/'universalshop-test.log').write_text(''.join(logs),encoding='utf-8')
        (folder/'bedrock-client.log').write_text(''.join(clientlogs),encoding='utf-8')
    report={'tested_at':datetime.now(timezone.utc).isoformat(),'jar_sha256':hashlib.sha256(JAR.read_bytes()).hexdigest(),'status':status,'errors':errors,'paper':'26.2','bedrock_protocol':'1.26.51','client':'bedrock-protocol 3.60.1','checks':checks,'forms_received':forms,'bridge_sha256':{n:hashlib.sha256((fixtures/n).read_bytes()).hexdigest() for n in ('Geyser-Spigot.jar','floodgate-spigot.jar')},'scope':'Real offline loopback Bedrock protocol client via Geyser and Floodgate (offline identity); native form responses including duplicate submission, buy/sell delivery and balances, disabled buy rejection and hidden button. No Microsoft authentication or rendered mobile/console UI test.','log':'test-servers/26.2-bedrock/universalshop-test.log','client_log':'test-servers/26.2-bedrock/bedrock-client.log'}
    (ROOT/'docs/bedrock-results.json').write_text(json.dumps(report,indent=2));print(json.dumps(report),flush=True)
    return status=='passed'

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--runtimes',required=True);parser.add_argument('--fixtures',default='.tools/bedrock');args=parser.parse_args()
    raise SystemExit(0 if run(args) else 1)
