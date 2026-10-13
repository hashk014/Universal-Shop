// Offline protocol client for the isolated loopback Geyser test only.
const path = require('path');
const bp = require(path.resolve(process.env.USHOP_BEDROCK_ROOT || '.tools/bedrock/client', 'node_modules/bedrock-protocol'));
const client = bp.createClient({host:'127.0.0.1', port:Number(process.argv[2]), username:'ShopBedrock', offline:true, version:'1.26.51', raknetBackend:'raknet-native', useRaknetWorkers:false});
client.on('error', e => console.error(e));
client.on('spawn', () => console.log('BEDROCK SPAWN'));
client.on('modal_form_request', packet => {
  console.log('FORM ' + JSON.stringify({id:packet.form_id, form:JSON.parse(packet.data)}));
});
require('readline').createInterface({input:process.stdin}).on('line', line => {
  const [id,index] = line.split(' ').map(Number);
  client.queue('modal_form_response', {form_id:id,has_response_data:true,data:JSON.stringify(index),has_cancel_reason:false});
});
client.on('disconnect', p => console.log('DISCONNECT', JSON.stringify(p)));
setTimeout(() => process.exit(2), 180000).unref();

