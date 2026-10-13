# Operations and recovery

## Persistence design

UniversalShop is a **single-server** plugin. Its ledger lives in `plugins/UniversalShop/state.yml`. It contains wallet balances, stock, demand, daily counters, marketplace escrow and the latest 200 transaction descriptions. Catalog edits are separate in `shops.yml`.

A trade validates permissions, world, quantity, price, balance, stock, limits and inventory capacity before beginning. It then:

1. Writes `pending.yml` with a transaction ID, previous/next ledger snapshots, and before/after player inventory evidence.
2. Applies external economy effects if Vault is enabled, changes the inventory, and executes any configured delivery commands.
3. Calls Bukkit's player-data save.
4. Replaces `state.yml` atomically with the committed ledger, then removes the pending journal.

Writes use a flushed temporary file and an atomic filesystem move. If the filesystem does not support that operation, the plugin does not fall back to truncating the live ledger. Parse errors fail closed rather than silently resetting balances. A committed transaction ID prevents replay when a crash happens between the ledger replacement and journal removal.

Minecraft player saves, Vault providers, command plugins and this ledger do not share a database transaction. A hard process/OS/storage failure can therefore leave an ambiguous prepared transaction. This plugin does **not** promise automatic exactly-once effects across those systems or power-loss-safe Minecraft player data. It keeps evidence and stops further trades for manual reconciliation instead of guessing or replaying deliveries.

## A pending transaction

`/shop admin recovery` shows whether trading is locked. Keep the affected players from moving/trading their inventory while investigating.

1. Back up the entire plugin folder and affected Minecraft player data. Read `pending.yml`: `description`, inventory `evidence`, `before` and `after`.
2. Inspect the actual player inventory and, if applicable, Vault balance and external command effect. The player may need to join to inspect their saved inventory.
3. Decide which state is correct and reconcile the **external effects yourself**. For `commit`, make inventory/money/services match the recorded completed trade. For `abort`, restore the recorded pre-trade effects.
4. Run exactly one of:

```text
/shop admin recovery commit confirm
/shop admin recovery abort confirm
```

These commands only choose the ledger state and release the lock. They do not reissue console commands, refund Vault balances or automatically rewrite inventories. Full evidence is archived under `recovery/<transaction-id>.yml` before the decision is applied. Never simply delete the journal to resume trading.

## Economy modes

`INTERNAL` uses integer cents and enables wallets, payments and the player marketplace. `VAULT` requires both Vault and a registered economy provider; the plugin disables itself if these are unavailable. Provider declines do not deliver items. Exceptions or uncertain provider effects retain the journal and lock trading. Transactions use two decimal currency places, even if a provider supports more precision.

The chosen mode is saved in the ledger. Switching it through a reload is rejected, and changing it only in the config at restart is also rejected. For a planned migration:

1. Stop the server and back up the complete plugin folder and economy provider data.
2. Reclaim all marketplace listings and resolve any pending transaction before migrating away from internal mode. This may require temporarily restarting in the original mode.
3. Transfer balances explicitly with your provider's supported tooling. UniversalShop does not automate cross-provider imports.
4. With the server stopped, change both `config.yml`'s `economy` and `state.yml`'s `economy-mode` consistently.
5. Restart on a staging copy first, verify representative balances and trades, then open access.

Changing the starting balance affects UUIDs that do not yet have a persisted internal wallet; it does not rewrite existing wallets. Do not remove `wallets` or regenerate the ledger to reset unrelated configuration.

## Backups, scale and boundaries

- `/shop admin export` creates a ledger snapshot; it is **not** a coordinated backup of Minecraft player data, configuration or an external economy. Stop the server for a consistent full backup.
- Save a copy of `shops.yml` before manual edits. Admin add/remove/price commands preserve the prior catalog as `shops.yml.bak`.
- This implementation intentionally performs durable writes on the server thread. It is suitable for modest single-server economies; it is not a tested high-throughput database backend. Large ledgers and slow disks can affect tick time. No high-concurrency/TPS claim is made.
- Do not share the plugin folder across multiple server processes. There is no network synchronization or multi-process lock.
- Arbitrary services depend on the behavior of their command providers. Folia's region scheduler is not supported.
- Ordinary shutdown/restart, invalid input, capacity, metadata protection, transaction quarantine and market transfers are covered by the integration harness. Sudden power loss, third-party provider failures, massive player loads and every other plugin combination require deployment-specific validation.
- Never use `/reload` or a plugin hot-reloader to switch economy systems. Use `/shop admin reload` for validated catalog/settings changes and full restarts for binary upgrades.

## Developer integration

Listen for `fr.hash014.universalshop.ShopTradeEvent` to inspect a server-shop transaction and cancel it before preparation. It exposes player, product ID, quantity, buy/sell direction and total cents. It does not fire for player-market purchases or payments. Bulk sales fire one event per product before committing the batch. Do not mutate the inventory or recursively initiate trades from a listener; cancellation is the supported control surface.
