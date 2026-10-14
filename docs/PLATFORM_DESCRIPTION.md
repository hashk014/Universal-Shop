# UniversalShop

**A configurable survival shop and player marketplace for Minecraft Java servers.**

UniversalShop lets players buy and sell items through chest menus, while
administrators build the catalog directly in game. It includes a standalone
economy and can use an existing economy through Vault. Players need no client mod
or resource pack.

## Shops that fit your server

- Create products from held items, including names, lore, enchantments and damage.
- Browse categories, search, choose custom quantities and review purchases or inventory sales.
- Customize chest rows, ordered product slots, button positions, titles, fillers
  and passive decorations in `menus.yml`.
- Set buy/sell prices, finite stock and restocking, daily limits, worlds,
  permissions, rotating deals and optional dynamic pricing.
- Enable favorites, recent trades, schedules, budgets, campaigns, loyalty,
  sale protection and analytics independently. Optional management modules are
  disabled by default.

## Player marketplace

List held items for a total asking price, search and sort listings, manage your
own offers, and recover expired items. Items remain in escrow until purchased or
reclaimed. Offline sale notifications persist across restarts. In Vault mode,
seller earnings are journaled until collected with `/shop market claim`.

## Administration and languages

`/shop admin editor` provides private drafts, product previews and confirmed
publication. English and French interfaces are included, with per-player language
preferences and editable text files. Administrator permissions can be delegated
separately for catalog, stock, balance, history, management and recovery.

`/shop admin diagnose`, `/shop admin history`, CSV exports and plugin-data backups
help administrators inspect transactions and configuration. Ambiguous interrupted
external effects stop trading for manual reconciliation instead of replaying them.

## Installation

1. Stop your server and preserve your existing plugin data.
2. Put `UniversalShop-2.1.0.jar` in `plugins/`, replacing the old UniversalShop JAR.
3. Start the server, then run `/shop` and `/shop admin editor` as an administrator.
4. Edit `shops.yml`, `config.yml`, `menus.yml` and the language files as needed.
   Run `/shop admin reload` to validate and apply changes.

The default internal economy works without dependencies. To use an external
economy, install **Vault and a Vault-compatible economy provider**, then set
`economy: VAULT`. EssentialsX has been tested. Other providers require their own
deployment checks. Changing economy mode on an existing server requires planned
balance migration; it does not automatically transfer money.

Bedrock players can use native forms through **Geyser and Floodgate** on the Java
backend. Chest geometry customization applies to Java menus, not native forms.

## Useful commands

```text
/shop
/shop buy stone 16
/shop sell stone 8
/shop sellall
/shop balance
/shop market
/shop market list 16 100
/shop market claim
/shop language fr
/shop admin editor
/shop admin diagnose
/shop admin backup
```

Players receive `universalshop.use` by default. Administrators use
`universalshop.admin` or the documented granular permissions. In Vault mode use
the economy provider's payment command; `/shop pay` belongs to internal mode.

## Compatibility and validation

The Bukkit API implementation targets Java 8 bytecode and is intended for
Bukkit, Spigot, Paper and compatible derivatives. Automated tests boot actual
Paper servers across 55 selected releases from **1.7.10 through 26.3**, connect two
loopback Java clients, exercise transactions and menu events, and restart servers
to verify persistence. Vault/EssentialsX and Geyser/Floodgate have separate tests.
Use the Java runtime required by your server version.

Paper is directly tested. Spigot/CraftBukkit/Purpur compatibility is based on the
Bukkit API, not a separate server boot matrix. Folia, Fabric, Forge and native
Bedrock Dedicated Server are unsupported. Rendered GUI appearance, Microsoft
authentication, large production loads and every third-party plugin combination
are not certified. The author has also tested the 2.0 gameplay locally.

Default prices are examples: tune them against your farms, recipes and other
plugins. Plugin backups do not replace coordinated world/playerdata and external
economy backups.

**Development disclosure:** substantial code, translations, documentation and
publication text were created with generative AI assistance under the author's
direction. Source code and automated test evidence are available for inspection.
No AI service is required by the plugin at runtime.

**Author:** hash014. **License:** MIT.

[Source and documentation](https://github.com/hashk014/Universal-Shop) ·
[Report an issue](https://github.com/hashk014/Universal-Shop/issues)
