# UniversalShop

A configurable survival shop for Minecraft Java servers, built by **hash014**. One plugin JAR, an English inventory interface, and an internal economy that works without dependencies.

**Download:** [GitHub releases](https://github.com/hashk014/Universal-Shop/releases). See the [tested compatibility matrix](docs/COMPATIBILITY.md) before choosing a server version.

## Features

- Buy and sell through a paginated inventory GUI with categories, search, quantity selection and a confirmation screen.
- Sell the held item or preview and confirm an entire inventory sale. Armor and offhand slots are excluded.
- Add the exact item in your hand to the shop, including its name, lore, enchantments and metadata. Selling requires an exact metadata match.
- Create command products for experience, ranks, keys or other services provided by your installed plugins.
- Fixed prices or bounded supply-and-demand prices. Prices are calculated per item, so splitting a bulk order does not produce a better rate.
- Unlimited or finite stock, automatic timed restocking, daily per-player buy/sell limits and product-specific permissions/world restrictions.
- Deterministic rotating deals: weekly by default, configurable to daily or another hourly interval. Products can be permanently available or exclusive to their featured period.
- Scheduled promotions, permission-based discounts, buy/sell taxes, and a no-immediate-resale-profit price floor.
- Built-in wallets with exact integer cents, player payments, administrator balance management and transaction history.
- Optional Vault connection for the server shop. Missing economy providers fail closed.
- Player marketplace with item escrow, offline seller credits, listing expiry, sale tax and safe cancellation. Uses the internal economy.
- Clickable shop signs, command completion, reload validation, catalog backups, ledger exports and a cancellable API event.
- Prepared transaction journal, atomic state-file replacement and explicit recovery for ambiguous inventory or third-party effects.

## Install

1. Stop your Bukkit-compatible Minecraft Java server.
2. Put `UniversalShop-1.0.0.jar` in `plugins/` and start the server with the Java version required by that server.
3. Run `/shop`. The internal economy starts each player with `$100.00` by default.
4. Edit `plugins/UniversalShop/config.yml` and `shops.yml`, then run `/shop admin reload`.

No resource pack, client mod, database server or Vault installation is required for internal mode. The default catalog includes building blocks, food, farming supplies, ores and tools. Materials absent from an older Minecraft version are skipped and logged.

All prices, messages, menus and documentation ship in English. The default prices are examples: balance farming, crafting, mob drops and other plugins against your server's economy before a public launch.

## Quick commands

| Command | What it does |
| --- | --- |
| `/shop` | Browse the shop |
| `/shop search diamond` | Search IDs, names and categories |
| `/shop category Farming` | Filter by category text |
| `/shop buy stone 64` | Review an item purchase |
| `/shop sell stone 64` | Review an item sale |
| `/shop sellhand` | Review selling the held stack |
| `/shop sellall` | Preview sellable storage contents |
| `/shop sellall confirm` | Execute the preview as one transaction |
| `/shop deals` | Show the current featured selection |
| `/shop balance` | Show your wallet/provider balance |
| `/shop pay Alex 25.50` | Send internal money to an online player |
| `/shop market` | Browse player listings |
| `/shop market list 16 100` | Escrow 16 matching held items for a **total** price of 100 |
| `/shop market buy ab12cd34 confirm` | Buy that listing |
| `/shop market cancel ab12cd34 confirm` | Reclaim your unsold listing |
| `/shop admin add ruby 250 50 Resources` | Add your held item as a product |
| `/shop admin price ruby 300 60` | Update prices; `-1` disables a direction |
| `/shop admin stock diamond 128` | Set current stock within configured capacity |
| `/shop admin remove ruby` | Remove a product |
| `/shop admin balance Alex 1000` | Set an internal balance; UUIDs also work |
| `/shop admin history 20` | Show recent transactions |
| `/shop admin export` | Save a timestamped ledger snapshot |
| `/shop admin reload` | Validate and reload configuration/catalog |
| `/shop admin recovery` | Inspect a pending transaction |

In the shop, left click buys and right click sells. Shift click selects a stack. Every normal shop purchase/sale opens a confirmation screen; changing quantities recalculates its quote. Quotes expire after 30 seconds, and a changed price requires a fresh confirmation.

An administrator can place a sign with `[ushop]` on line one and an optional product ID on line two. Right clicking it opens the shop or a product confirmation.

## Documentation

- [Configuration and product examples](docs/CONFIGURATION.md)
- [Recovery, persistence and operational limits](docs/OPERATIONS.md)
- [Compatibility evidence and test scope](docs/COMPATIBILITY.md)
- [Development and test harness](docs/DEVELOPMENT.md)

## Scope

UniversalShop targets Bukkit/Spigot/Paper-style Java servers. It is not a Bedrock, Forge, Fabric, Folia, Velocity or BungeeCord plugin. The matrix distinguishes actual Paper integration tests from API compatibility assumptions. It does not claim to cover every economy add-on or replace auctions, land protection, banks, rentals or a network-wide database.

Player marketplace trades and `/shop pay` require `INTERNAL` mode. Vault users should use their economy provider's payment command. Arbitrary command deliveries cannot be made atomic with another plugin: uncertain effects lock trading for an administrator to reconcile.

## Build

Use Maven 3.9+ and JDK 17+:

```sh
mvn clean verify
```

The JAR targets Java 8 bytecode. Modern Minecraft servers still require their own newer Java runtime. Test harness instructions are in [DEVELOPMENT.md](docs/DEVELOPMENT.md).

## License

MIT. Copyright (c) 2026 hash014. Original plugin implementation; Bukkit and optional integrations remain the property of their respective authors.
