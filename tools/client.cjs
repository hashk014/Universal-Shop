// A real offline loopback Minecraft client; server-side tests inspect its actual player inventory.
const path = require('path');
const root = process.env.USHOP_PROTOCOL_ROOT || path.join(__dirname, '../.tools/protocol');
const mc = require(path.join(root, 'node_modules/minecraft-protocol'));
const data = require(path.join(root, 'node_modules/minecraft-data'));
const [,, port, version, username] = process.argv;
const protocol = data.versions.pc.find(v => v.minecraftVersion === version).version;
const schema = data(version)?.version.version === protocol ? version : data.supportedVersions.pc.find(v => data(v)?.version.version === protocol) || version;
const options = {host: '127.0.0.1', port: Number(port), username, auth: 'offline', version: schema, disableChatSigning: true};
const client = mc.createClient(options);
options.protocolVersion = protocol;
client.on('error', e => { console.error(e); process.exitCode = 1; });
client.on('login', () => console.log('CLIENT LOGIN', username, version));
client.on('end', why => { console.log('CLIENT END', why); process.exit(); });
client.on('position', p => {
  if (p.teleportId !== undefined) client.write('teleport_confirm', {teleportId: p.teleportId});
  else if (version.startsWith('1.7.') || version.startsWith('1.8.')) {
    const y = version.startsWith('1.7.') ? p.y - 1.62 : p.y;
    client.write('position_look', {x:p.x, y, stance:y+1.62, z:p.z, yaw:p.yaw, pitch:p.pitch, onGround:false});
  }
});
setTimeout(() => client.end(), 180000).unref();
