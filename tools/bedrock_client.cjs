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
  const space = line.indexOf(' ');
  const id = Number(line.slice(0, space));
  const response = line.slice(space + 1);
  JSON.parse(response); // A SimpleForm index or a CustomForm response array.
  client.queue('modal_form_response', {form_id:id,has_response_data:true,data:response,has_cancel_reason:false});
});
client.on('disconnect', p => console.log('DISCONNECT', JSON.stringify(p)));
setTimeout(() => process.exit(2), 180000).unref();

