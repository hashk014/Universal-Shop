# UniversalShop 1.2.0

Manage a small shop with a few switches, or enable advanced survival-economy controls as needed. This release adds nine independent management modules, all OFF by default, with a Java inventory dashboard and native Bedrock administrator forms. All plugin content remains in English.

## Optional modules

- Access: maintenance, opening days/hours, named timezones, overnight windows and a custom closure message.
- Product rules: pause products/categories, switch buying/selling separately, set order bounds, per-player product cooldowns and server-wide daily quotas.
- Budgets: daily player spending and sale-earnings caps, including atomic bulk-sale enforcement.
- Campaigns: multiple timed, targeted promotions with optional permissions.
- Loyalty: configurable discount tiers based on completed purchase spending.
- Sale protection: named, lore-bearing, enchanted, damaged or selected material templates.
- Analytics: product/player totals, top products by revenue and CSV exports.
- Personal tools: persistent favorites, recent trades and catalog/name/base-price sorting.
- Stock alerts: configurable low-stock console warnings with throttling.

Additional admin tools duplicate, rename and recategorize products, restore stock and reset supply/demand offsets. New rules run through the existing transaction journal and respect command switches, permissions and the resale price floor. They cover the server shop; player-market escrow and payments remain independently configurable.

## Quick start

```text
/shop admin panel
/shop admin manage enable personal
/shop admin manage enable analytics
/shop admin manage stats
```

Open the panel in Survival mode with `universalshop.admin`. Enable only the modules you need. The [management guide](https://github.com/hashk014/Universal-Shop/blob/codex/universal-shop/docs/MANAGEMENT.md) contains all commands, examples, defaults and exact rule semantics.

## Validation

- 43 JUnit tests passed, including schedules, quotas, cooldowns, budgets, campaigns, loyalty, analytics and upgrade defaults.
- 55 Paper versions from 1.7.10 through 26.3 passed with two connected Java protocol clients and a full restart. Each case includes optional-module scenarios and a server-driven dashboard click, plus statistics/favorites persistence.
- Vault 1.7.3 with EssentialsX 2.22.0 passed on Paper 1.21.11 and 26.3, including budget rejection before debit and managed-transaction accounting.
- A real offline Bedrock protocol client on Paper 26.2 with Geyser/Floodgate received 12 native forms and passed 23 checks, including favorites and an administrator module toggle.
- Every integration report records the exact release JAR SHA-256. Full logs and unit reports are included in the evidence ZIP.

No rendered Minecraft/mobile/console UI, Microsoft authentication or high-concurrency load test is claimed. The Java matrix is not a Geyser backend compatibility matrix. This remains a Java backend plugin, not a native Bedrock Dedicated Server or land-claim plugin. See compatibility and operations documentation for platform boundaries.

## Upgrade and assets

Stop the server, back up its plugin data, replace the previous UniversalShop JAR with 1.2.0 and restart. Configuration, catalog and ledger are preserved. Missing management settings use defaults without overwriting user settings. No new module changes trading or collects activity until enabled.

- `UniversalShop-1.2.0.jar`: install in `plugins/`.
- `UniversalShop-1.2.0-Install.zip`: JAR, example configuration and documentation.
- `UniversalShop-1.2.0-sources.jar`: source archive.
- `UniversalShop-1.2.0-Evidence.zip`: integration logs and unit reports.
- `SHA256SUMS.txt` and `manifest.json`: checksums and release metadata.
