# UniversalShop 1.0.0

An English survival shop for Minecraft Java with a single legacy/modern plugin JAR.

Features include GUI purchases and sales with confirmation, exact custom-item templates, bulk selling, command services, an internal economy, optional Vault, dynamic prices, finite stock and timed restocking, daily limits, weekly/daily featured items, timed promotions, permission discounts, taxes, a player marketplace with escrow, shop signs, catalog editing, backups, history and transaction recovery.

## Validation

- 18 unit tests passed.
- 55 Paper versions from 1.7.10 through 26.3 passed the internal-economy integration suite with two connected protocol clients, server-side GUI checks and a full restart.
- Vault 1.7.3 with EssentialsX 2.22.0 passed real debit/deposit and restart checks on Paper 1.21.11 and 26.3.
- The compatibility JSON records the exact tested JAR hash, server builds, channels and check counts. Full logs are provided in the evidence archive.

Spigot/CraftBukkit/Purpur are intended API targets but were not directly boot-tested. Folia, Forge, Fabric, Bedrock and proxy servers are not plugin targets. No rendered-client visual or high-concurrency load test is claimed. Review the compatibility and operations documents for precise limits, including ambiguous third-party delivery recovery.

## Downloads

- `UniversalShop-1.0.0.jar`: install in your server's `plugins/` folder.
- `UniversalShop-1.0.0-Install.zip`: JAR, example configuration and English documentation.
- `UniversalShop-1.0.0-sources.jar`: plugin source archive.
- `UniversalShop-1.0.0-Evidence.zip`: full per-version integration logs and test reports.
- `SHA256SUMS.txt` and `manifest.json`: artifact checksums and release metadata.

Start the server and run `/shop`. Internal mode requires no dependencies. The player marketplace and `/shop pay` use internal wallets; Vault mode delegates balances to the installed provider.
