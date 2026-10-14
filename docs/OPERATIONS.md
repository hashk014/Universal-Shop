# Operations and recovery

## Persistence design

UniversalShop is a **single-server** plugin. `plugins/UniversalShop/state.yml` is a readable ledger **checkpoint**, containing wallet balances, stock, demand, counters, marketplace escrow and the latest 200 transaction descriptions. Immutable records in `audit/` contain every subsequent committed state change and the durable full transaction history. Both files are required for a complete backup. Catalog edits remain in `shops.yml`.

A trade validates permissions, world, quantity, price, balance, stock, limits and inventory capacity before beginning. It then:

1. Writes `pending.yml` with a transaction ID, sequence, sparse before/after ledger changes, and before/after player inventory evidence. Legacy journals with full snapshots remain readable during an upgrade.
2. Applies external economy effects if Vault is enabled, changes the inventory, and executes any configured delivery commands.
3. Calls Bukkit's player-data save.
4. Atomically publishes one checksummed audit record containing both the commit decision and ledger changes, applies the already-durable decision in memory, then removes the pending journal. Publishing this record is the ledger commit point.

Every 100 final operations, on a normal plugin shutdown, and before a plugin backup, the ledger is consolidated into an atomic `state.yml` checkpoint. Startup replays only records after that checkpoint, checks sequence continuity and prior values, and never replays inventory operations, delivery commands or Vault calls. A crash after record publication but before journal cleanup therefore completes only ledger recovery/cleanup. Aborted operations and operator recovery decisions also receive durable records.

Writes use a unique flushed temporary file and an atomic filesystem move. If the filesystem does not support that operation, the plugin does not fall back to truncating live data. `state.yml` keeps ordinary YAML syntax and appends a SHA-256 comment footer over the **exact UTF-8 payload bytes**, with `storage.checkpoint-format: 1` in the payload. Payload and footer are published in the same atomic file. This detects damage even to wallets untouched by subsequent transactions; a missing/truncated footer on a marked checkpoint fails closed. Plain legacy YAML is accepted once and immediately upgraded. Do not manually edit a protected checkpoint or normalize its line endings. Parse errors, checkpoint/audit checksum failures, sequence gaps, a missing checkpoint boundary record and mismatched prior ledger values fail closed. Unpublished `.tmp` files are ignored; a corrupt published record must be repaired from a matching backup, never deleted to resume trading. File contents are forced before publication, and directory entries are forced on Unix where supported; Windows Java does not expose the same directory fsync barrier. These mechanisms do not promise survival of every storage-controller or OS power-loss failure. Checksums detect accidental corruption; they are not authentication against an administrator who can deliberately rewrite data and checksums.

Minecraft player saves, Vault providers, command plugins and this ledger do not share a database transaction. A hard process/OS/storage failure can therefore leave an ambiguous prepared transaction. This plugin does **not** promise automatic exactly-once effects across those systems or power-loss-safe Minecraft player data. It keeps evidence and stops further trades for manual reconciliation instead of guessing or replaying deliveries.

`Store.save()` explicitly marks operations that change only this ledger. An interrupted operation with that flag can be safely completed at startup, without any external replay. A description or evidence string saying "ledger-only" cannot set that flag. Every inventory/provider preparation remains quarantined until a durable decision or explicit operator reconciliation.

## Durable history and diagnostics

`/shop admin history` reads durable history, newest first. The 200-entry list in `state.yml` is only a compatibility cache. Records retain the transaction ID, timestamps, description, inventory/provider evidence, prior/next ledger values and one of `COMMITTED`, `ABORTED`, `RECOVERED_COMMIT` or `RECOVERED_ABORT`.

Filters can be combined. Dates are UTC; a date-only `to:` includes that entire day. ISO UTC timestamps also work. `player:`/`uuid:` match participant IDs in descriptions, evidence or changed paths, `product:` matches a product/text identifier, `status:` matches the exact outcome, and other words are case-insensitive text filters.

```text
/shop admin history player:<uuid> product:stone from:2026-10-01 to:2026-10-14 50
/shop admin history status:RECOVERED_ABORT 25
/shop admin history csv player:<uuid> from:2026-10-01
/shop admin diagnose
/shop admin backup
```

CSV exports contain full evidence and ledger changes and escape spreadsheet formula prefixes. Store them as administrative data, not public downloads. The first upgrade imports the legacy history still present in `state.yml` exactly once as `LEGACY_COMMITTED`; records already discarded by older versions cannot be reconstructed. Audit records are not automatically pruned. Monitor disk space and retain matching backups when applying a retention policy.

## A pending transaction

`/shop admin recovery` shows whether trading is locked. `/shop admin diagnose` also reports the storage sequence, replay window, transaction timestamp and evidence location. Keep the affected players from moving/trading their inventory while investigating.

1. Run `/shop admin backup` and separately back up affected Minecraft player data and external economy data. Read `pending.yml`: `description`, inventory `evidence`, and the `changes` list with `path`, presence flags and before/after values. An older journal instead contains complete `before`/`after` snapshots.
2. Inspect the actual player inventory and, if applicable, Vault balance and external command effect. The player may need to join to inspect their saved inventory.
3. Decide which state is correct and reconcile the **external effects yourself**. For `commit`, make inventory/money/services match the recorded completed trade. For `abort`, restore the recorded pre-trade effects.
4. Run exactly one of:

```text
/shop admin recovery commit confirm
/shop admin recovery abort confirm
```

These commands only choose the ledger state and release the lock. They do not reissue console commands, refund Vault balances or automatically rewrite inventories. Full evidence is archived under `recovery/<transaction-id>.yml` before the decision is applied. Never simply delete the journal to resume trading.

## Economy modes

`INTERNAL` uses integer cents and enables wallets, payments and the player marketplace. `VAULT` requires both Vault and a registered economy provider; the plugin disables itself if these are unavailable. The marketplace can operate with Vault when `market.allow-vault: true`: buyers are debited through the provider, while seller proceeds become durable `market-credits` in the ledger. Sellers collect these credits through `/shop market claim`. This avoids guessing whether a second external payment to an offline seller succeeded. A credit collection still has the ordinary provider reconciliation boundary. Provider declines do not deliver items; exceptions or uncertain provider effects retain the journal and lock trading. Transactions use two decimal currency places, even if a provider supports more precision.

The chosen mode is saved in the ledger. Switching it through a reload is rejected, and changing it only in the config at restart is also rejected. For a planned migration:

1. Stop the server and back up the complete plugin folder and economy provider data.
2. Reclaim all marketplace listings, collect all Vault marketplace credits and resolve every pending transaction before migrating. This may require temporarily restarting in the original mode. Do not abandon seller escrow or credits.
3. Transfer balances explicitly with your provider's supported tooling. UniversalShop does not automate cross-provider imports.
4. After a **normal shutdown has flushed the checkpoint**, use `tools/migrate_economy.py` to change the economy/provider binding and reseal the checkpoint. Preserve `storage.checkpoint-format`, `storage.sequence`, `storage.last-audit-id` and the complete `audit/` directory. A text editor change alone fails checksum verification; removing the integrity marker/footer is not a supported migration. If the preceding shutdown crashed, restore/recover and checkpoint in the original mode first.
5. Restart on a staging copy first, verify representative balances and trades, then open access.

Changing the starting balance affects UUIDs that do not yet have a persisted internal wallet; it does not rewrite existing wallets. Do not remove `wallets` or regenerate the ledger to reset unrelated configuration.

### Offline migration tool

The install ZIP includes `tools/migrate_economy.py`. It requires Python 3.10 or later and no packages. The server must remain stopped. The tool takes an OS byte-range lock that overlaps the plugin's Java NIO lease, verifies the protected checkpoint, refuses a pending transaction or uncheckpointed audit deltas, and requires all marketplace escrow and seller credits to have been recovered. Unknown/ambiguous YAML structures are refused conservatively. Serialized Bukkit items, wallet values and audit records retain their original bytes.

First review a plan; replace the example data-folder path and provider name with the actual deployment values:

```text
python tools/migrate_economy.py --data-folder "server/plugins/UniversalShop" --from INTERNAL --to VAULT --provider "Essentials Economy" --dry-run
```

After transferring balances with the external provider's own supported tooling and backing up its data, apply the reviewed binding change:

```text
python tools/migrate_economy.py --data-folder "server/plugins/UniversalShop" --from INTERNAL --to VAULT --provider "Essentials Economy" --confirm BALANCES_TRANSFERRED
python tools/migrate_economy.py --data-folder "server/plugins/UniversalShop" --from VAULT --to INTERNAL --confirm BALANCES_TRANSFERRED
```

`--provider` is the exact registered Vault economy provider's `getName()`. Install/select that target provider before the staged restart; this offline tool cannot inspect or configure a running provider. A `VAULT` to `VAULT` change with a different `--provider` is supported with the same recovery/transfer requirements. `--confirm BALANCES_TRANSFERRED` explicitly acknowledges that the separate balance transfer is complete. **The tool does not transfer balances, alter wallet amounts, or make provider network calls.** Existing internal wallets are deliberately retained; an internal-economy balance import must be completed separately when returning to `INTERNAL`.

Before changing either file, it creates a coordinated plugin ZIP under `backups/` and an immutable receipt under `migrations/`. The receipt records old/new economy/provider values, before/after file and payload checksums, the backup path and the balance-transfer acknowledgement. Existing backups, temporary files and `ledger.lock` are excluded from the ZIP. Provider/Minecraft data still require separate backups.

The tool first publishes temporary `economy: MIGRATION_PENDING` in the config, then the protected target ledger, then the final config. The plugin rejects this temporary mode. An interruption between files therefore fails closed even during a provider-only change. A receipt proves completion only when **both** current `state.yml` and `config.yml` file hashes match its `after` hashes. A receipt existing by itself does not prove completion.

If migration is interrupted or fails, keep the server stopped and restore the **complete** pre-migration plugin ZIP into an empty data directory; restore/reconcile the external provider data separately. Do not remove the guard by hand or copy only a checkpoint from another snapshot. Staged validation remains necessary before opening player access.

The tool tests can be run with `python -m unittest discover -s tools -p test_migrate_economy.py -v`. Set `UNIVERSALSHOP_TEST_JAVA` to a Java 11+ executable to also test overlap with a real Java NIO whole-file lock; the ordinary tests need Python only.

## Backups, scale and boundaries

- `/shop admin backup` creates an atomic ZIP containing a coordinated plugin checkpoint, catalog/configuration, pending evidence, recovery archives and audit records. Existing `backups/`, temporary files and the process lease `ledger.lock` are excluded. It does **not** back up Minecraft player data or an external economy. Stop the server for a consistent full server/economy backup. Restore the entire ZIP to an empty plugin data directory with the server stopped; do not mix its checkpoint with a different audit directory.
- `/shop admin export` creates a standalone current ledger snapshot for inspection. It is not a replacement for the full plugin backup and is not a restore bundle.
- Save a copy of `shops.yml` before manual edits. Admin add/remove/price commands preserve the prior catalog as `shops.yml.bak`.
- Sparse journals and commit records remove full YAML serialize/parse and full-ledger disk writes from every trade. Ledger copy/diff and durable filesystem calls still run on the server thread; they remain proportional to ledger size, and checkpoints have a full-ledger cost. Slow disks and large records can affect tick time. There is no tested high-concurrency/TPS claim.
- A reproducible local synthetic measurement uses 20,000 wallets, 500 products and 12 sequential operations after warmup. See [storage-benchmark.json](storage-benchmark.json) for JVM, OS, timings and bytes written. It compares the previous full-snapshot storage algorithm with copy + prepare + durable delta commit. It excludes Minecraft player saves, rendering, network traffic, third-party providers and gameplay load. Run it explicitly with `mvn -Dtest=StoreBenchmarkTest -Duniversalshop.storage-benchmark=true test`; it is skipped by default.
- Do not share the plugin folder across multiple server processes. A process lease prevents simultaneous live plugin ownership; it provides no network synchronization or distributed economy.
- Arbitrary services depend on the behavior of their command providers. Folia's region scheduler is not supported.
- Unit tests simulate restarts at publication/cleanup/checkpoint boundaries, filesystem write faults, malformed/checksum-corrupt data, missing records, stale copies, legacy migration and ZIP restore. Ordinary shutdown/restart, invalid input, capacity, metadata protection, transaction quarantine and market transfers are covered by the integration harness. These deterministic tests do not simulate a physical power cut or establish third-party provider safety under every failure.
- Never use `/reload` or a plugin hot-reloader to switch economy systems. Use `/shop admin reload` for validated catalog/settings changes and full restarts for binary upgrades.

## Developer integration

Listen for `fr.hash014.universalshop.ShopTradeEvent` to inspect a server-shop transaction and cancel it before preparation. It exposes player, product ID, quantity, buy/sell direction and total cents. It does not fire for player-market purchases or payments. Bulk sales fire one event per product before committing the batch. Do not mutate the inventory or recursively initiate trades from a listener; cancellation is the supported control surface.

`ShopMarketEvent` exposes cancellable listing, purchase and cancellation validation. `ShopTransactionEvent` observes an already-durable transaction; observer errors cannot undo it and must never trigger re-delivery. Keep callbacks short and use the immutable audit transaction ID when correlating events.
