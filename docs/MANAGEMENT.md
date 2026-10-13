# Optional shop management

UniversalShop 1.2 adds nine independent modules and catalog administration tools. **All nine modules default to OFF.** Existing shops keep their previous behavior until an administrator enables a module. Everything works with the same server-shop transaction service for Java and Bedrock players.

## Start with only what you need

Run `/shop admin panel` in Survival mode to open the management dashboard. Java players receive an inventory menu; detected Bedrock players receive native forms. Click a module to switch it on or off. The dashboard also provides maintenance controls, stock overview, statistics and CSV export. Results for reports appear in chat. Only administrators can open it or execute its actions.

For a small SMP, these three commands are enough to add favorites, sales reports and stock warnings:

```text
/shop admin manage enable personal
/shop admin manage enable analytics
/shop admin manage enable stock-alerts
```

Use `disable` instead of `enable` to turn any module off. `/shop admin manage` shows every module's current state. Switching modules persists the configuration and invalidates existing shop menus/quotes. Detailed settings are in `config.yml`; apply edits with `/shop admin reload`.

| Module | Optional capabilities |
| --- | --- |
| `access` | Maintenance, opening days/hours, named timezone, overnight windows, custom closure message |
| `product-rules` | Pause individual products/categories, independent buy/sell switches, minimum/maximum orders, per-player product cooldowns, server-wide daily unit quotas |
| `budgets` | Per-player daily spending and daily sale-earnings caps |
| `campaigns` | Multiple timed promotions targeting products/categories and optionally requiring a permission |
| `loyalty` | Discount tiers based on previously recorded spending |
| `sale-protection` | Block sales of named, lore-bearing, enchanted, damaged or selected material templates |
| `analytics` | Product/player totals, top ten products by revenue, personal statistics, CSV sales exports |
| `personal` | Persistent favorites, last 20 traded products, catalog/name/base-price sorting |
| `stock-alerts` | Low-stock console warnings with configurable threshold and repeat delay |

These policies cover **server-shop purchases, individual sales, bulk sales and command products**. They do not change player-market escrow or `/shop pay`. Use `disabled-commands` or `market.enabled` to control those separately. Operators obey transaction restrictions too. Console administration remains available during shop maintenance.

## Access and schedules

```text
/shop admin manage close
/shop admin manage open
```

These commands enable `access` and set/clear its maintenance flag. `open` does not override an enabled schedule. Browsing remains available while closed, but confirmations cannot trade.

Merge this example into your existing `management` section:

```yaml
management:
  access:
    enabled: true
    maintenance: false
    message: 'The market is closed. Visit us in the evening!'
    schedule-enabled: true
    timezone: Europe/Paris
    days: [FRIDAY, SATURDAY]
    opens: '18:00'
    closes: '02:00'
```

An overnight window belongs to its opening day: Saturday 01:00 is part of Friday's window. Start is inclusive and end exclusive. Equal opening/closing times mean all day on the selected days; an empty day list means every day. Named timezones follow their daylight-saving rules.

## Product rules and budgets

```text
/shop admin manage pause diamond
/shop admin manage resume diamond
/shop admin manage category-pause Resources
/shop admin manage category-resume Resources
```

Pause/resume commands enable `product-rules`. A category pause still applies after a product is resumed, and vice versa. Paused products remain visible for browsing. Transactions explain why trading is unavailable.

```yaml
management:
  product-rules:
    enabled: true
    paused-categories: []
    entries:
      diamond:
        paused: false
        buy-enabled: true
        sell-enabled: true
        min-order: 1
        max-order: 16
        cooldown-seconds: 60
        global-daily-buy: 128
        global-daily-sell: 256
  budgets:
    enabled: true
    daily-spend: 5000
    daily-earn: 2000
```

Daily caps reset at **midnight UTC**, independently of opening-hours timezone. Global quotas count units across every player on this backend; the existing product `daily-buy-limit`/`daily-sell-limit` still apply per player. Product cooldowns are per player and shared across buy/sell directions. Minimum/maximum amounts apply to each product line in bulk sales too; they do not change the displayed unit price.

Budgets count the final amount actually charged/paid after pricing, discounts and tax. A bulk sale exceeding any limit is rejected as a whole: inventory, wallet and counters remain unchanged. `0` means unlimited for daily quotas/budgets or no cooldown; it is not valid for minimum/maximum order size. Order sizes must be 1–2304 and still obey the existing `max-transaction-items`.

Counters are journaled with successful trades and survive restart. They only count while their module is enabled; enabling a module does not reconstruct earlier trades. Turning a module off retains its stored counters, but activity during that disabled period is not added.

## Campaigns and loyalty

```yaml
management:
  campaigns:
    enabled: true
    entries:
      winter-building:
        enabled: true
        starts: '2026-12-19T00:00:00Z'
        ends: '2026-12-21T00:00:00Z'
        discount-percent: 15
        products: [stone, glass]
        categories: []
        permission: ''
  loyalty:
    enabled: true
    tiers:
      - spent: 1000
        discount-percent: 2
      - spent: 10000
        discount-percent: 5
```

Campaign times are absolute ISO-8601 instants. Product/category targets are combined with OR; both empty means all products. A nonempty `permission` is an additional requirement. Multiple campaigns, loyalty, permission discounts, featured deals and the existing global promotion use the **largest eligible discount**, not additive stacking. The existing no-immediate-resale-profit floor still applies.

Loyalty uses completed purchase spending already recorded before the new quote; the threshold-crossing purchase does not retroactively discount itself. Either `analytics` or `loyalty` records these totals. Sales do not increase loyalty spending. Discounts apply to purchases only. A campaign starting/ending or a loyalty change can invalidate a previously quoted total; the player must review a fresh quote.

## Protect items from sales

```yaml
management:
  sale-protection:
    enabled: true
    named: true
    lore: true
    enchanted: true
    damaged: true
    materials: [DIAMOND, NETHER_STAR]
```

Material names must match the server's Material enum names. Damage protection only applies to damageable items, so legacy block color/data values are not treated as tool damage. Protection applies even if an administrator deliberately configured an exactly matching custom product. The existing exact metadata matching continues to protect unrelated custom items when this module is off.

Protected templates are omitted from bulk-sale previews; direct sales are rejected. Purchase delivery and player-market trading are unaffected.

## Reports, stock and catalog tools

| Command | Result |
| --- | --- |
| `/shop admin manage stats` | Top ten products by recorded purchase revenue |
| `/shop admin manage stats diamond` | Units bought/sold, money charged/paid and transaction-line count |
| `/shop stats` | The player's own recorded totals; requires `analytics` |
| `/shop admin manage report` | CSV in `plugins/UniversalShop/exports/`; requires `analytics` |
| `/shop admin manage stock` | Up to 20 finite-stock products with the lowest absolute remaining quantities |
| `/shop admin manage restock diamond` | Restore configured stock capacity now |
| `/shop admin manage restock all` | Restore every finite-stock product |
| `/shop admin manage reset-demand diamond` | Reset that product's supply/demand offset |
| `/shop admin manage reset-demand all` | Reset every product's demand offset |
| `/shop admin duplicate diamond rare_diamond` | Copy catalog settings under a new ID |
| `/shop admin rename rare_diamond Rare Diamond` | Change its displayed name |
| `/shop admin category rare_diamond Premium Resources` | Move it to a category |

Restocking/demand resets retain balances, player limits and configured base prices. They invalidate open quotes. Unlimited-stock products are skipped by restocking. Duplicate copies catalog settings, including nested dynamic pricing and custom item metadata, but starts with a fresh ID: runtime stock/demand, limits, statistics and ID-specific management rules are not copied. Catalog writes retain `shops.yml.bak` and validate before replacing the file.

Analytics are lifetime aggregates for periods when tracking was enabled. Each product line counts once, so one bulk sale can add several lines. CSV amounts are integer cents; `charged_cents` is money paid by players, and `paid_cents` is money paid to players. No bank balance, net accounting profit or market commission is inferred from these values. CSV rows use restricted product IDs and numeric values.

Stock alerts are console messages after a successful trade, only for finite stock. They are throttled per product, rearm when stock rises above the threshold, and reset on restart/reload. No background scans, mail, Discord or external service is used.

## Favorites, recents and sorting

```text
/shop favorite diamond
/shop favorites
/shop recent
/shop sort name
/shop sort price
/shop sort catalog
```

`favorite` toggles membership. The default limit is 24; set `management.personal.max-favorites` between 1 and 100. Java players can middle-click a product to toggle a favorite; Bedrock product forms have a Toggle favorite button and the shop has Favorites/Recently traded buttons. `/shop recent` shows the last 20 distinct successfully traded products, newest first. Preferences survive restart and disabled periods.

Favorites and recents still filter permissions, worlds and rotating-product availability. Sorting by price uses configured base buy prices, lowest first, with products that cannot be purchased last. It does not claim to sort live discounted/dynamic quotes. Recents always retain chronological order.

## Permissions, switches and upgrading

Administration requires `universalshop.admin`; normal shop permissions still apply to player tools and transactions. Disable `admin.panel`, `admin.manage`, `admin.duplicate`, `admin.rename`, `admin.category`, `favorite`, `favorites`, `recent`, `sort` or `stats` through the existing command policy when needed. `admin` disables all its children. Module switches supplement permissions; they do not grant permissions.

Stop the server and replace the old JAR with 1.2.0. Existing configuration, products and ledger are retained. Missing new settings use resource defaults without overwriting user values. No new module starts collecting data or changing trading rules until enabled. These modules are local to this backend and are not a cross-server database or a land-claim integration.
