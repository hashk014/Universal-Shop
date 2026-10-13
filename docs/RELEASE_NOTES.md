# UniversalShop 1.1.0

Administrators can now disable individual UniversalShop commands or entire groups. Bedrock players connecting through Geyser/Floodgate receive native forms with separate Buy/Sell buttons and confirmation screens. All plugin messages and documentation remain in English.

## New features

- `disabled-commands` configuration with parent/child rules such as `pay`, `buy`, `market.list` or `admin.add`.
- `/shop admin command disable <path>` and `/shop admin command enable <path>` persist changes immediately. The console remains able to restore access, even when `shop` or `admin` is disabled.
- Shared enforcement across aliases, namespaced commands, signs, Java menus and Bedrock transactions; disabled options are filtered from help/completion and relevant buttons.
- Optional Geyser/Floodgate integration without mandatory runtime dependencies. Bedrock forms support product/category/deal browsing, quantities, buy/sell confirmation, bulk sales and player-market purchase/reclaim confirmation.
- One-use form tokens, expired quote rejection and invalidation on reload, disconnect or policy change. Missing form APIs fall back to the inventory interface.

## Validation

- 26 JUnit tests passed.
- 55 Paper versions from 1.7.10 through 26.3 passed with two connected Java protocol clients and a full server restart.
- Vault 1.7.3 with EssentialsX 2.22.0 passed on Paper 1.21.11 and 26.3.
- A Bedrock 26.51 protocol client connected through real Geyser/Floodgate on Paper 26.2: 6 native forms received, 15 checks passed, including purchase/sale accounting, duplicate responses, disabled purchase rejection and hidden Buy button.
- All reports identify the exact release JAR SHA-256. Logs and unit reports are bundled in the evidence ZIP.

The Bedrock fixture uses an offline loopback identity. Microsoft/Xbox authentication, account linking, proxy forwarding and rendered mobile/console UI were not tested. The Java compatibility matrix does not imply Geyser supports every listed backend. UniversalShop runs on the Java backend; native Bedrock Dedicated Server, Folia, Fabric and Forge are unsupported. Spigot/CraftBukkit/Purpur are intended API targets but were not directly boot-tested.

## Upgrade and download

Stop the server, back up the UniversalShop folder, replace the previous JAR with `UniversalShop-1.1.0.jar`, and restart. Existing configuration/catalog/ledger files are retained. New switches default to no disabled commands and Bedrock forms enabled. See [setup and command examples](https://github.com/hashk014/Universal-Shop/blob/codex/universal-shop/docs/BEDROCK-AND-COMMANDS.md).

- `UniversalShop-1.1.0.jar`: plugin to install in `plugins/`.
- `UniversalShop-1.1.0-Install.zip`: JAR, example configuration and English documentation.
- `UniversalShop-1.1.0-sources.jar`: source archive.
- `UniversalShop-1.1.0-Evidence.zip`: integration logs and test reports.
- `SHA256SUMS.txt` and `manifest.json`: artifact checksums and release metadata.
