# Compatibility and validation

Tested on 2026-10-14T12:47:37.046161+00:00. **55/55 Paper versions passed** the internal-economy suite.

Exact tested plugin SHA-256: `10699fe30245806497ec3f8457270a17b4814554a19ecbbfead0e121007dde60`.

Every passing version completed server startup, durable-store checks, two real loopback client logins, player transactions, server-driven GUI event checks, a full server restart, both client reconnections and inventory/wallet persistence checks. The release has also passed 101 JUnit tests.

## Tested versions

| Minecraft | Paper build | Channel | Java | Store / player / restart checks | Result |
| --- | --- | --- | --- | --- | --- |
| 1.7.10 | 2025 | STABLE | 8 | 10 / 122 / 7 | passed |
| 1.8.8 | 445 | STABLE | 8 | 10 / 122 / 7 | passed |
| 1.9.4 | 775 | STABLE | 8 | 10 / 122 / 7 | passed |
| 1.10.2 | 918 | STABLE | 8 | 10 / 122 / 7 | passed |
| 1.11.2 | 1106 | STABLE | 8 | 10 / 122 / 7 | passed |
| 1.12 | 1169 | STABLE | 8 | 10 / 122 / 7 | passed |
| 1.12.1 | 1204 | STABLE | 8 | 10 / 122 / 7 | passed |
| 1.12.2 | 1620 | STABLE | 8 | 10 / 122 / 7 | passed |
| 1.13 | 173 | STABLE | 8 | 10 / 122 / 7 | passed |
| 1.13.1 | 386 | STABLE | 8 | 10 / 122 / 7 | passed |
| 1.13.2 | 657 | STABLE | 8 | 10 / 122 / 7 | passed |
| 1.14 | 17 | STABLE | 8 | 10 / 122 / 7 | passed |
| 1.14.1 | 50 | STABLE | 8 | 10 / 122 / 7 | passed |
| 1.14.2 | 107 | STABLE | 8 | 10 / 122 / 7 | passed |
| 1.14.3 | 134 | STABLE | 8 | 10 / 122 / 7 | passed |
| 1.14.4 | 245 | STABLE | 8 | 10 / 122 / 7 | passed |
| 1.15 | 21 | STABLE | 8 | 10 / 122 / 7 | passed |
| 1.15.1 | 62 | STABLE | 8 | 10 / 122 / 7 | passed |
| 1.15.2 | 393 | STABLE | 8 | 10 / 122 / 7 | passed |
| 1.16.1 | 138 | STABLE | 8 | 10 / 122 / 7 | passed |
| 1.16.2 | 189 | STABLE | 8 | 10 / 122 / 7 | passed |
| 1.16.3 | 253 | STABLE | 8 | 10 / 122 / 7 | passed |
| 1.16.4 | 416 | STABLE | 8 | 10 / 122 / 7 | passed |
| 1.16.5 | 794 | STABLE | 8 | 10 / 122 / 7 | passed |
| 1.17 | 79 | STABLE | 16 | 10 / 122 / 7 | passed |
| 1.17.1 | 411 | STABLE | 17 | 10 / 122 / 7 | passed |
| 1.18 | 66 | ALPHA | 17 | 10 / 122 / 7 | passed |
| 1.18.1 | 216 | STABLE | 17 | 10 / 122 / 7 | passed |
| 1.18.2 | 388 | STABLE | 17 | 10 / 122 / 7 | passed |
| 1.19 | 81 | STABLE | 17 | 10 / 122 / 7 | passed |
| 1.19.1 | 111 | STABLE | 17 | 10 / 122 / 7 | passed |
| 1.19.2 | 307 | STABLE | 17 | 10 / 122 / 7 | passed |
| 1.19.3 | 448 | STABLE | 17 | 10 / 122 / 7 | passed |
| 1.19.4 | 550 | STABLE | 17 | 10 / 122 / 7 | passed |
| 1.20 | 17 | STABLE | 17 | 10 / 122 / 7 | passed |
| 1.20.1 | 196 | STABLE | 17 | 10 / 122 / 7 | passed |
| 1.20.2 | 318 | STABLE | 17 | 10 / 122 / 7 | passed |
| 1.20.4 | 499 | STABLE | 17 | 10 / 122 / 7 | passed |
| 1.20.5 | 22 | ALPHA | 21 | 10 / 122 / 7 | passed |
| 1.20.6 | 151 | STABLE | 21 | 10 / 122 / 7 | passed |
| 1.21 | 130 | STABLE | 21 | 10 / 122 / 7 | passed |
| 1.21.1 | 133 | STABLE | 21 | 10 / 122 / 7 | passed |
| 1.21.3 | 83 | STABLE | 21 | 10 / 122 / 7 | passed |
| 1.21.4 | 232 | STABLE | 21 | 10 / 122 / 7 | passed |
| 1.21.5 | 114 | ALPHA | 21 | 10 / 122 / 7 | passed |
| 1.21.6 | 48 | STABLE | 21 | 10 / 122 / 7 | passed |
| 1.21.7 | 32 | STABLE | 21 | 10 / 122 / 7 | passed |
| 1.21.8 | 60 | STABLE | 21 | 10 / 122 / 7 | passed |
| 1.21.9 | 59 | ALPHA | 21 | 10 / 122 / 7 | passed |
| 1.21.10 | 130 | STABLE | 21 | 10 / 122 / 7 | passed |
| 1.21.11 | 132 | STABLE | 21 | 10 / 122 / 7 | passed |
| 26.1.1 | 29 | ALPHA | 25 | 10 / 122 / 7 | passed |
| 26.1.2 | 74 | STABLE | 25 | 10 / 122 / 7 | passed |
| 26.2 | 133 | STABLE | 25 | 10 / 122 / 7 | passed |
| 26.3 | 181 | STABLE | 25 | 10 / 122 / 7 | passed |

## Vault integration

Tested with [Vault 1.7.3](https://github.com/MilkBowl/Vault/releases/tag/1.7.3) and [EssentialsX 2.22.0](https://github.com/EssentialsX/Essentials/releases/tag/2.22.0). The Vault suite verifies real provider debits/deposits, item delivery/removal, rejected funds, marketplace escrow, deferred seller credits, repeated claim guards and balance/inventory/credit persistence after a full server restart.

| Minecraft | Result | Checks (store / player / restart) |
| --- | --- | --- |
| 1.21.11 | passed | 10 / 29 / 7 |
| 26.3 | passed | 10 / 29 / 7 |

## Bedrock integration

Paper 26.2 with Geyser and Floodgate, Bedrock protocol 1.26.51: **passed**, 28 checks and 18 native forms received over the network. Real offline loopback Bedrock protocol client via Geyser and Floodgate (offline identity); native CustomForm search and quantity inputs, duplicate input/button responses, favorite-view return after purchase/sale, buy/sell delivery and balances, disabled buy rejection, hidden button, favorite persistence/filtering, recent trades, analytics and administrator module toggle. No Microsoft authentication or rendered mobile/console UI test.

See [Bedrock setup and limits](BEDROCK-AND-COMMANDS.md) and the [machine-readable results](bedrock-results.json).

## What this means

- The tested range covers the principal Bukkit-era Minecraft releases from **1.7.10 through 26.3**, including the Paper patch releases listed above. Versions absent from the table were not directly tested. There is no claim for 1.0–1.6, every historical patch, or future Minecraft releases.
- Stable builds were preferred. ALPHA/BETA labels above are preserved when no stable build was available for that exact Minecraft version.
- **Paper:** directly tested. **Spigot/CraftBukkit and Paper derivatives such as Purpur:** Bukkit API compatibility intended, but not directly boot-tested in this matrix.
- **Bedrock players:** supported through Geyser/Floodgate on a Java backend, subject to their version requirements. Native Bedrock Dedicated Server, Folia, Fabric and Forge are unsupported. BungeeCord and Velocity can sit in front of a supported backend but do not load this plugin.
- **Java:** the plugin targets Java 8 bytecode; use the server-specific runtime shown in the table, including Java 25 for the tested 26.x servers. The earliest 1.17 build uses Java 16; 1.17.1 uses Java 17.
- Modern materials are available only on servers that contain them. Serialized custom items are not guaranteed to survive server downgrades.
- Inventory events were driven on the server against connected players. No rendered Minecraft client/UI visual inspection or high-player-count load test was performed.
- Other Vault providers, third-party commands, other plugin combinations, real power loss and network-wide consistency remain deployment-specific. See [OPERATIONS.md](OPERATIONS.md).

## Reproduce

See [DEVELOPMENT.md](DEVELOPMENT.md), [machine-readable Paper results](compatibility-results.json) and [Vault results](vault-results.json). Full per-version logs are in the evidence ZIP attached to the release. Official server builds come from the [Paper downloads service](https://docs.papermc.io/misc/downloads-service/) and their SHA-256 checksums are verified by the harness.


## Unicode custom-item persistence

The exact production JAR additionally passed 26 isolated checks on Paper 1.7.10/1.8.8 for emoji names/lore, enchantments, damage, audit replay, checkpoints, Unicode descriptions and genuine binary preservation. The companion probe resolves production classes and does not bundle the serializer fix. See [Unicode results](unicode-results.json).
