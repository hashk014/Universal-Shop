"""Minimal 26.2+ loopback client. Packet IDs are exported from the tested server registry."""
import json, os, pathlib, socket, struct, sys, uuid

port, version, name, registry = sys.argv[1:]
ids = json.loads(pathlib.Path(registry).read_text())
root = pathlib.Path(os.environ.get('USHOP_PROTOCOL_ROOT', '.tools/protocol'))
versions = json.loads((root/'node_modules/minecraft-data/minecraft-data/data/pc/common/protocolVersions.json').read_text())
protocol = next(v['version'] for v in versions if v['minecraftVersion'] == version)

def vint(n):
    n &= 0xffffffff
    result = bytearray()
    while n > 127:
        result.append((n & 127) | 128)
        n >>= 7
    return bytes(result + bytes([n]))

def string(s):
    value = s.encode()
    return vint(len(value)) + value

def integer(data, offset=0):
    result = 0
    for i in range(5):
        byte = data[offset+i]
        result |= (byte & 127) << (7*i)
        if byte < 128:
            return result, offset+i+1
    raise ValueError('Invalid VarInt')

sock = socket.create_connection(('127.0.0.1', int(port)), timeout=30)
sock.settimeout(180)

def packet(number, payload=b''):
    data = vint(number) + payload
    sock.sendall(vint(len(data)) + data)

def send(state, key, payload=b''):
    packet(ids[state+'_serverbound']['minecraft:'+key], payload)

def read(n):
    result = bytearray()
    while len(result) < n:
        part = sock.recv(n-len(result))
        if not part:
            raise EOFError()
        result.extend(part)
    return bytes(result)

packet(0, vint(protocol)+string('127.0.0.1')+struct.pack('>H', int(port))+vint(2))
send('login', 'hello', string(name)+uuid.uuid3(uuid.NAMESPACE_DNS, name).bytes)
state = 'login'
try:
    while True:
        length_bytes = bytearray()
        while True:
            byte = read(1)[0]
            length_bytes.append(byte)
            if byte < 128: break
        length, _ = integer(length_bytes)
        data = read(length)
        number, offset = integer(data)
        payload = data[offset:]
        key = next((k.split(':',1)[1] for k,v in ids[state+'_clientbound'].items() if v==number), '')
        if key in ('disconnect', 'login_disconnect'):
            print('CLIENT DISCONNECT', payload.hex(), flush=True)
            break
        if state == 'login' and key == 'login_finished':
            send(state, 'login_acknowledged')
            state = 'configuration'
            send(state, 'client_information', string('en_us')+b'\x02'+vint(0)+b'\x01\x7f'+vint(1)+b'\x00\x01'+vint(0))
            print('CLIENT LOGIN', name, version, flush=True)
        elif key == 'select_known_packs': send(state, key, vint(0))
        elif key == 'code_of_conduct': send(state, 'accept_code_of_conduct')
        elif key == 'finish_configuration':
            send(state, key)
            state = 'game'
        elif key == 'keep_alive': send(state, key, payload)
        elif key == 'ping': send(state, 'pong', payload)
        elif state == 'game' and key == 'player_position':
            teleport, start = integer(payload)
            send(state, 'accept_teleportation', vint(teleport)+(payload[start:start+24]+payload[start+48:start+56] if version=='26.3' else b''))
except EOFError:
    print('CLIENT END', flush=True)
finally:
    sock.close()
