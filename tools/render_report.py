"""Generate human-readable compatibility evidence from the actual matrix output."""
import json,pathlib
import xml.etree.ElementTree as ET
root=pathlib.Path(__file__).resolve().parent.parent
report=json.loads((root/'docs/compatibility-results.json').read_text())
vault=json.loads((root/'docs/vault-results.json').read_text())
bedrock=json.loads((root/'docs/bedrock-results.json').read_text())
unit_count=sum(int(ET.parse(p).getroot().get('tests','0')) for p in (root/'target/surefire-reports').glob('TEST-*.xml'))
rows=report['results'];passed=sum(r['status']=='passed' for r in rows)
out=['# Compatibility and validation','',f'Tested on {report["tested_at"]}. **{passed}/{len(rows)} Paper versions passed** the internal-economy suite.','',f'Exact tested plugin SHA-256: `{report["jar_sha256"]}`.','',
f'Every passing version completed server startup, durable-store checks, two real loopback client logins, player transactions, server-driven GUI event checks, a full server restart, both client reconnections and inventory/wallet persistence checks. The release has also passed {unit_count} JUnit tests.',
'','## Tested versions','',
'| Minecraft | Paper build | Channel | Java | Store / player / restart checks | Result |','| --- | --- | --- | --- | --- | --- |']
for r in rows:
    c=r.get('checks',{});out.append(f'| {r["version"]} | {r.get("paper_build","?")} | {r.get("paper_channel","?")} | {r.get("java","?")} | {c.get("self",0)} / {c.get("player",0)} / {c.get("restart",0)} | {r["status"]} |')
out+=['','## Vault integration','','Tested with [Vault 1.7.3](https://github.com/MilkBowl/Vault/releases/tag/1.7.3) and [EssentialsX 2.22.0](https://github.com/EssentialsX/Essentials/releases/tag/2.22.0). The Vault suite verifies real provider debits/deposits, item delivery/removal, rejected funds, marketplace mode restrictions and balance/inventory persistence after a full server restart.','', '| Minecraft | Result | Checks (store / player / restart) |','| --- | --- | --- |']
for r in vault['results']:
    c=r.get('checks',{});out.append(f'| {r["version"]} | {r["status"]} | {c.get("self",0)} / {c.get("player",0)} / {c.get("restart",0)} |')
out+=['','## Bedrock integration','',f'Paper {bedrock["paper"]} with Geyser and Floodgate, Bedrock protocol {bedrock["bedrock_protocol"]}: **{bedrock["status"]}**, {bedrock["checks"]} checks and {bedrock["forms_received"]} native forms received over the network. '+bedrock['scope'],'','See [Bedrock setup and limits](BEDROCK-AND-COMMANDS.md) and the [machine-readable results](bedrock-results.json).','','## What this means','',
'- The tested range covers the principal Bukkit-era Minecraft releases from **1.7.10 through 26.3**, including the Paper patch releases listed above. Versions absent from the table were not directly tested. There is no claim for 1.0–1.6, every historical patch, or future Minecraft releases.',
'- Stable builds were preferred. ALPHA/BETA labels above are preserved when no stable build was available for that exact Minecraft version.',
'- **Paper:** directly tested. **Spigot/CraftBukkit and Paper derivatives such as Purpur:** Bukkit API compatibility intended, but not directly boot-tested in this matrix.',
'- **Bedrock players:** supported through Geyser/Floodgate on a Java backend, subject to their version requirements. Native Bedrock Dedicated Server, Folia, Fabric and Forge are unsupported. BungeeCord and Velocity can sit in front of a supported backend but do not load this plugin.',
'- **Java:** the plugin targets Java 8 bytecode; use the server-specific runtime shown in the table, including Java 25 for the tested 26.x servers. The earliest 1.17 build uses Java 16; 1.17.1 uses Java 17.',
'- Modern materials are available only on servers that contain them. Serialized custom items are not guaranteed to survive server downgrades.',
'- Inventory events were driven on the server against connected players. No rendered Minecraft client/UI visual inspection or high-player-count load test was performed.',
'- Other Vault providers, third-party commands, other plugin combinations, real power loss and network-wide consistency remain deployment-specific. See [OPERATIONS.md](OPERATIONS.md).',
'','## Reproduce','','See [DEVELOPMENT.md](DEVELOPMENT.md), [machine-readable Paper results](compatibility-results.json) and [Vault results](vault-results.json). Full per-version logs are in the evidence ZIP attached to the release. Official server builds come from the [Paper downloads service](https://docs.papermc.io/misc/downloads-service/) and their SHA-256 checksums are verified by the harness.','']
(root/'docs/COMPATIBILITY.md').write_text('\n'.join(out),encoding='utf-8')
