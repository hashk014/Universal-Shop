# Command switches and Bedrock support

## Disable commands and actions

In `plugins/UniversalShop/config.yml`:

```yaml
disabled-commands:
  - pay
  - sellall
  - market.list
  - admin.add
bedrock:
  enabled: true
```

Apply file edits with `/shop admin reload`. Alternatively, administrators can persist changes immediately:

```text
/shop admin command
/shop admin command disable pay
/shop admin command enable pay
/shop admin command disable market.list
```

These are UniversalShop commands, not a server-wide command blocker. Paths are case-insensitive. Valid roots are `shop`, `help`, `buy`, `sell`, `sellhand`, `sellall`, `balance`, `pay`, `search`, `category`, `deals`, `market`, and `admin`. Market children are `market.list`, `market.buy`, `market.cancel`. Admin children are `admin.add`, `admin.remove`, `admin.price`, `admin.stock`, `admin.balance`, `admin.history`, `admin.reload`, `admin.recovery`, `admin.export`, `admin.command`.

Disabling `market` disables every market subcommand. Disabling `shop` disables all player access, including signs and menus. A disabled parent must be enabled before an enabled child can be used. Unknown paths fail validation to catch configuration typos.

Operators and administrators playing in-game obey the same switches. The server console bypasses them, so it can always run `shop admin command enable shop` or enable `admin.command` again. Permission nodes still apply independently. Aliases and namespaced commands share the same checks; direct buy/sell, bulk sales and market transactions recheck the policy before changing money or items. Disabled actions are omitted from help/completion and relevant menu buttons. Existing inventory menus can retain a stale button until reopened, but execution checks still block its action.

`sell` blocks all sales, including held-item and bulk sales. `sellhand` only disables the convenience command, while ordinary sales remain available. `balance` hides the balance command/button; it does not conceal all monetary information in transaction messages.

## Bedrock installation

1. Install UniversalShop on a supported Java backend.
2. Install and configure [Geyser](https://geysermc.org/wiki/geyser/setup/) using the deployment supported by your server and Java runtime.
3. Install [Floodgate](https://geysermc.org/wiki/floodgate/setup/) when allowing Bedrock accounts without a Java account.
4. Leave `bedrock.enabled: true`. A detected Bedrock player opening `/shop` receives native forms. Java players retain the inventory interface.

On a proxy deployment, install Floodgate on the backend as described in its documentation, enable `send-floodgate-data` on the proxy and share the matching key securely. The backend must be able to identify the player through Floodgate's API. Geyser on the backend can also supply identification/forms directly. UniversalShop does not guess client type from usernames or change authentication settings.

Geyser's own [supported versions and runtime requirements](https://geysermc.org/wiki/geyser/supported-versions/) are separate from UniversalShop's Java compatibility matrix. Current Geyser-Spigot requires a recent backend; older Java servers can require a supported proxy/standalone deployment and protocol translation. The 55-version Java matrix is not a 55-version Geyser matrix.

Forms provide paginated browsing, categories, featured deals, separate Buy/Sell actions, quantity selection, bulk-sale confirmation and player-market purchase/reclaim confirmation. Creating marketplace listings still uses `/shop market list <amount> <total price>` with the item held. Search uses `/shop search <text>`. Custom products, permissions, stock, prices, economy rules and limits use the same service as Java players.

Callbacks return to the server thread. Responses have one-use tokens, quotes expire after 30 seconds, and reloads, disconnects and policy changes invalidate open form sessions. An unavailable/incompatible form API falls back to the inventory interface and logs a warning. Set `bedrock.enabled: false` to deliberately use inventory menus for all players.

## Validation scope

See [the Bedrock test report](bedrock-results.json). The automated fixture connects a real offline Bedrock protocol client through Geyser on Paper 26.2, receives forms over the network and submits actual button responses. It checks purchase/sale balances and inventory, duplicate submission, disabled purchase rejection and the hidden Buy button. Geyser forwards the offline test identity through Floodgate, exercising Floodgate's detection and form API without Microsoft authentication.

Microsoft/Xbox login, authenticated Floodgate account linking, proxy forwarding and rendered mobile/console UI were not exercised. The test-only authentication bypass is restricted to disposable loopback servers and must never be copied into deployment configuration.
