# UniversalShop 2.1.0

Customize Java chest menus through the new `menus.yml`: choose 1–6 rows, ordered
content slots/page sizes, control positions, title overrides, backgrounds and
passive decorations. The feature covers all 13 shop, market, administration and
catalog editor menu types. Existing installations receive the default file
without overwriting their catalogs or other configuration.

Controls keep their actions when moved, including confirmation buttons. Content
pagination uses the configured capacity. Layout validation rejects collisions,
out-of-range slots, unknown settings and invalid materials. Invalid reloads retain
the previous active layout; successful reloads close open chest menus.

See [menu configuration and examples](MENUS.md). Native Bedrock forms retain their
own presentation. Automated runtime checks cover custom sizes, moved confirmation
with duplicate-click protection, passive decorations, compact category pagination,
editor content mapping and rejection of invalid layout reloads. The complete
Paper, Vault, Bedrock and legacy Unicode suites were rerun against this release.

Install `UniversalShop-2.1.0.jar` after stopping the server and backing up its data.
No balance migration is required when keeping the same economy. Menus are
configured in YAML; the catalog editor still edits products rather than layout
geometry.

## Previous release: UniversalShop 2.0.0

Create catalog products directly in game, complete player purchases without losing browsing context, and run the marketplace with the internal economy or Vault. Existing wallets, escrow, settings and legacy pending journals are retained.

## In-game catalog editor

`/shop admin editor` opens a guided editor. Create a product from the exact held item, adjust its name/category, prices, stock capacity/restocking, daily limits, worlds, permission, dynamic bounds/idle half-life, featured availability and console commands. Drafts stay private until reviewed and published; stale drafts cannot overwrite another administrator's edits. Template replacement, command edits and deletion require confirmation. Publication validates the complete catalog and preserves the previous file atomically.

Granular administrator permissions separate catalog, console commands, stock, balances, history, management, recovery, backup and reload. The existing `universalshop.admin` node remains compatible. English/French packs, personal language preferences and editable menu icons/background are included.

## Player experience

Java search, custom quantities and listing creation use private, expiring chat inputs from menu actions. Bedrock players receive native text forms. Maximum quantities respect money, inventory space/matching metadata, stock, quotas, cooldowns, order bounds and daily budgets. Previews retain item metadata, remaining quotas are visible, and confirmations return to the previous category/page. Bulk sales have a review/confirmation menu. Inputs and confirmation callbacks are consumed once.

Marketplace browsing adds search, newest/price/name/seller ordering, personal listings and expired-item views. Expired offers are hidden publicly and do not consume the active seller quota. `/shop market claim` recovers whole expired listings that fit, keeping the remainder escrowed. Sale notices survive offline periods.

Vault buyers are debited through their provider. Net seller proceeds become durable liabilities until explicitly collected. Uncertain provider effects quarantine trading; interrupted deposits are never blindly repeated. Outstanding credits are bound to the original provider name.

## Storage and operations

The durable commit point publishes sparse state changes and a checksummed audit record together. Checkpoints retain ordinary YAML with exact-byte integrity verification. Durable history survives beyond the legacy 200-entry cache. Legacy YAML/prepared journals migrate on startup. A process lease prevents two servers writing the same directory.

Legacy YAML writers previously turned supplementary Unicode text into binary and discarded custom item metadata on reload. The serializer now escapes only affected String scalars, retaining emoji names/lore, enchantments, damage and genuine binary data through journal/checkpoint reload. This is separately verified on actual Paper 1.7.10 and 1.8.8 with the production JAR.

`/shop admin history` filters UUID/product/UTC date/outcome/text and exports CSV. `/shop admin backup` captures configuration, catalog, checkpoint, audit and pending evidence in a plugin-data ZIP. `/shop admin diagnose` exposes recovery status and advisory duplicate/base-crafting price problems. Plugin backups require separate Minecraft playerdata and external economy backups.

The included offline migration tool checks the process lease, checksum and outstanding escrow/credits, creates a complete plugin backup and receipt, then changes only economy/provider bindings. Interrupted changes keep startup blocked. Provider balances must be transferred separately; the tool never moves money itself.

Dynamic prices optionally recover after inactivity: demand halves toward zero per complete configured idle interval. Active trades refresh the timer; rapid splitting cannot erase demand through rounding. Diagnostics remain advisory for farms, recipes and other plugins.

## Validation and limits

See [COMPATIBILITY.md](COMPATIBILITY.md) and machine-readable Paper/Vault/Bedrock reports for the exact JAR hash and final counts. Unit coverage includes corruption, crash/restart boundaries, legacy migration, backups, filters, permissions, input validation, money and policies. Connected-player integration drives editor draft/input/review/publish/cancel/stale flows, confirmation duplication, metadata previews, market accounting and full restarts.

The [storage benchmark](storage-benchmark.json) compares old full snapshots with the new journal on a synthetic 20,000-wallet/500-product ledger. It measures storage costs, not Minecraft TPS or multiplayer stress. Writes, copy/diff work, reports, backups and checkpoints remain synchronous. Physical power loss, rendered desktop/mobile/console UI, Microsoft authentication, other Vault providers and network-wide consistency are not certified.

## Install or upgrade

Stop the server and preserve a full backup, replace the binary with `UniversalShop-2.0.0.jar`, then restart. Run `/shop admin editor`, `/shop admin diagnose` and optionally `/shop language fr`. Existing modules retain their settings; missing keys use defaults without replacing customized catalogs.

- `UniversalShop-2.0.0.jar`: install in `plugins/`.
- `UniversalShop-2.0.0-Install.zip`: JAR, example language/configuration files and documentation.
- `UniversalShop-2.0.0-sources.jar`: Java source archive.
- `UniversalShop-2.0.0-Evidence.zip`: integration and unit evidence.
- `SHA256SUMS.txt` and `manifest.json`: exact release metadata.
