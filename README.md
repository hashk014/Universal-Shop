# UniversalShop

A configurable survival shop for Minecraft Java servers, built by **hash014**. One plugin JAR, editable English/French interfaces, and an internal economy that works without dependencies.

**Download:** [GitHub releases](https://github.com/hashk014/Universal-Shop/releases). See the [tested compatibility matrix](docs/COMPATIBILITY.md) before choosing a server version.

## Features

- Guided in-game catalog editor: exact held-item templates, price/category/name editing, stock, limits, worlds, permissions, dynamic prices and reviewed command products. Drafts are published only after review, with conflict detection and an atomic catalog backup.
- Private integrated search/quantity inputs on Java, native text forms on Bedrock, maximum feasible orders, metadata-preserving previews, visible remaining quotas and return to the previous category/page.
- Player-market search, price/name/seller sorting, personal listings, expired-item recovery, sale notices and guided listing creation. Vault sellers receive journaled credits collected explicitly with `/shop market claim`.
- Durable, checksummed transaction audit beyond the 200-line compatibility cache; UUID/product/date/status filters, CSV exports, plugin-data ZIP backups and recovery diagnostics.
- Granular administrator permissions, editable language packs and `/shop language en|fr|auto` preferences.
- Delta write-ahead persistence, checkpoints and a process-held data-directory lease. Existing 1.x ledgers and pending journals are migrated without resetting wallets or escrow.
- Advisory duplicate-item/base-recipe economy diagnostics and optional lazy dynamic-demand recovery.

- Buy and sell through a paginated inventory GUI with categories, search, quantity selection and a confirmation screen.
- Sell the held item or preview and confirm an entire inventory sale. Armor and offhand slots are excluded.
- Add the exact item in your hand to the shop, including its name, lore, enchantments and metadata. Selling requires an exact metadata match.
- Create command products for experience, ranks, keys or other services provided by your installed plugins.
- Fixed prices or bounded supply-and-demand prices. Prices are calculated per item, so splitting a bulk order does not produce a better rate.
- Unlimited or finite stock, automatic timed restocking, daily per-player buy/sell limits and product-specific permissions/world restrictions.
- Deterministic rotating deals: weekly by default, configurable to daily or another hourly interval. Products can be permanently available or exclusive to their featured period.
- Scheduled promotions, permission-based discounts, buy/sell taxes, and a no-immediate-resale-profit price floor.
- Built-in wallets with exact integer cents, player payments, administrator balance management and transaction history.
- Configurable chest rows, content slots, control positions, titles and decorations in menus.yml; see [menu layouts](docs/MENUS.md).
- Optional Vault connection for the server shop. Missing economy providers fail closed.
- Player marketplace with item escrow, offline seller credits, listing expiry, sale tax and safe cancellation, in internal or Vault mode.
- Clickable shop signs, command completion, reload validation, catalog backups, ledger exports and a cancellable API event.
- Prepared transaction journal, atomic delta publication/checkpoints and explicit recovery for ambiguous inventory or third-party effects.
- Disable individual commands or entire command groups, including their aliases and corresponding trading actions.
- Bedrock-native forms through Geyser/Floodgate, with separate Buy/Sell buttons, quantity selection, categories, deals and player-market confirmations.
- Nine opt-in management modules: opening hours/maintenance, product rules, daily budgets, targeted campaigns, loyalty tiers, sale protection, analytics, personal tools and low-stock alerts.
- A Java/Bedrock administrator dashboard, product duplication/renaming/category changes, manual restock, demand reset and CSV sales reports.
- Optional persistent favorites, recent trades and sorting. **Every new management module defaults to OFF.**

## Install

1. Stop your Bukkit-compatible Minecraft Java server.
2. Put `UniversalShop-2.1.0.jar` in `plugins/` and start the server with the Java version required by that server.
3. Run `/shop`. The internal economy starts each player with `$100.00` by default.
4. Edit `plugins/UniversalShop/config.yml` and `shops.yml`, then run `/shop admin reload`.

No resource pack, client mod, database server or Vault installation is required for internal mode. The default catalog includes building blocks, food, farming supplies, ores and tools. Materials absent from an older Minecraft version are skipped and logged.

English and French UI packs are included; documentation uses English. Configure `language` or use `/shop language fr`. The default prices are examples: balance farming, crafting, mob drops and other plugins against your server's economy before a public launch. `/shop admin diagnose` highlights catalog issues for review.

For optional features, open `/shop admin panel` and enable only the modules you need. A small SMP can start with `personal` for favorites and `analytics` for sales reports. The [management guide](docs/MANAGEMENT.md) documents every switch and provides configuration examples. Upgrades retain your existing catalog, configuration and ledger.

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
| `/shop admin panel` | Open the optional-module dashboard |
| `/shop admin editor` | Edit or create products through reviewed drafts |
| `/shop admin diagnose` | Inspect catalog economy and storage/recovery status |
| `/shop admin backup` | Create a coordinated ZIP of plugin data |
| `/shop admin history product:stone from:2026-10-01 30` | Filter the durable transaction audit |
| `/shop admin history csv player:<UUID>` | Export matching audit records |
| `/shop market claim` | Collect Vault seller credits and reclaim expired listings that fit |
| `/shop market notices` | Read and clear stored sale notices |
| `/shop language fr` | Select a persistent interface language |
| `/shop admin manage enable analytics` | Start recording shop statistics |
| `/shop admin manage close` | Put the server shop into maintenance |
| `/shop admin manage restock all` | Refill finite stock |
| `/shop admin manage report` | Export tracked sales to CSV |
| `/shop admin duplicate diamond rare_diamond` | Copy a catalog product to a new ID |
| `/shop favorite diamond` | Toggle a favorite when `personal` is enabled |
| `/shop admin command disable pay` | Disable player payments |
| `/shop admin command enable pay` | Enable player payments again |
| `/shop admin command disable market.list` | Disable new listings while retaining buying/cancellation |
| `/shop admin recovery` | Inspect a pending transaction |

In the shop, left click buys and right click sells. Shift click selects a stack. Every normal shop purchase/sale opens a confirmation screen; changing quantities recalculates its quote. Quotes expire after 30 seconds, and a changed price requires a fresh confirmation.

An administrator can place a sign with `[ushop]` on line one and an optional product ID on line two. Right clicking it opens the shop or a product confirmation.

## Documentation

- [Configuration and product examples](docs/CONFIGURATION.md)
- [Optional management modules and dashboard](docs/MANAGEMENT.md)
- [Command switches and Bedrock setup](docs/BEDROCK-AND-COMMANDS.md)
- [Recovery, persistence and operational limits](docs/OPERATIONS.md)
- [Compatibility evidence and test scope](docs/COMPATIBILITY.md)
- [Development and test harness](docs/DEVELOPMENT.md)

## Scope

UniversalShop targets Bukkit/Spigot/Paper-style Java servers. Bedrock players can use it through Geyser/Floodgate; UniversalShop installs on the Java backend, not Bedrock Dedicated Server or a proxy. Forge, Fabric and Folia are unsupported. The matrix distinguishes actual Paper integration tests from API compatibility assumptions. It does not claim to cover every economy add-on or replace auctions, land protection, banks, rentals or a network-wide database.

`/shop pay` requires `INTERNAL` mode; Vault users use their economy provider's payment command. The marketplace supports Vault through deferred seller credits. It does not guess whether an interrupted provider deposit succeeded. Arbitrary command deliveries cannot be made atomic with another plugin: uncertain effects lock trading for an administrator to reconcile. Shared network databases and Folia remain outside this release's scope.

## Build

Use Maven 3.9+ and JDK 17+:

```sh
mvn clean verify
```

The JAR targets Java 8 bytecode. Modern Minecraft servers still require their own newer Java runtime. Test harness instructions are in [DEVELOPMENT.md](docs/DEVELOPMENT.md).

## License

MIT. Copyright (c) 2026 hash014. Original plugin implementation; Bukkit and optional integrations remain the property of their respective authors.
