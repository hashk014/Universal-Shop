# Player experience and menu customization

The Java shop displays 36 products per page with separate navigation and personal controls. Left click reviews a purchase; right click reviews a sale; Shift click selects one stack. A middle click toggles a favorite when the `personal` module is enabled. Product previews keep the original template's lore, enchantments, damage and other item metadata.

Search is available from the menu without typing a command. Java players receive a private chat prompt; Bedrock players receive a native text input form. Enter `*` to clear a search. Prompts expire after two minutes and accept a single response. Type `cancel` to stop. Issuing another command cancels the pending chat input or native form callback. Replies are never dispatched as commands. Disconnect, plugin shutdown and view invalidation clear pending inputs.

Confirmations offer common quantities, a custom quantity and a maximum quantity. The maximum considers the current wallet, inventory space or matching items, available stock, daily player and server quotas, order rules and configured budgets. This is a fresh estimate: stock, prices, permissions and inventory are checked again when confirming. Command products remain limited to one purchase. Quotes expire after 30 seconds.

The shop returns to the same category, search, favorites or recent view and page after an ordinary trade or cancellation. With `management.personal.enabled: true`, favorites, recent products and catalog/name/price sorting are available directly from both interfaces. Buy and sell quota displays show remaining units for the current UTC day; paused product directions are marked in the catalog.

The Sell inventory control opens a full basket confirmation. It preserves metadata, shows each product's quantity and price, and rechecks the entire inventory basket before selling. Large baskets retain all selected product types even when only the first 27 previews fit on screen; the summary displays the total number of types and complete quoted price.

## Player market

The player market provides search by item name/material or seller, sorting by newest listing, price, item name or seller, a My listings view, and a dedicated Expired items view. Expired listings stay in escrow for their owner and are omitted from the public active listings. Java previews preserve the listed object's appearance; native Bedrock confirmations show its custom name and lore.

Hold the finished item and choose List held item. Enter a quantity and a total price, then review and confirm publication. The final step checks that the held template is still the item originally selected. The service checks available matching items and listing limits. Nothing is removed before publication is confirmed.

Recover items and earnings claims expired listings that fit in storage and any pending Vault seller earnings. Entire listings that cannot fit remain safely stored. Sale notifications survive disconnects and are shown from the market's notification control or `/shop market notices`. `/shop market claim` is also available. The `market.claim` and `market.notices` command switches apply to their menu controls.

## Themes and languages

Set `language: en` or `language: fr` in `config.yml`. All player controls, item annotations, native form labels and input prompts are editable in `plugins/UniversalShop/languages/en.yml` and `fr.yml`. Per-player choices use `/shop language en`, `/shop language fr` or `/shop language auto` when enabled. Keep brace placeholders such as `{price}` and `{quantity}`. For legacy inventory titles, keep translated titles within 32 characters.

Control icons and empty-slot backgrounds are configurable in `config.yml`:

```yaml
menu-background: 'GRAY_STAINED_GLASS_PANE|STAINED_GLASS_PANE'
menu-icons:
  search: 'SPYGLASS|COMPASS'
  category: CHEST
  buy: EMERALD
  sell: GOLD_INGOT
  market:
    claim: HOPPER
    notices: BOOK
  admin:
    editor: 'CRAFTING_TABLE|WORKBENCH'
```

Keys correspond to the action policy, including `buy`, `sell`, `search`, `category`, `deals`, `balance`, `favorites`, `recent`, `sort`, `market`, `market.list`, `market.claim`, `market.notices`, `admin.editor` and `admin.manage`. Nested YAML sections create dotted policy paths. Unsupported material aliases fall back to the default icon. An empty background disables the filler; fillers never execute actions. Product and market item templates retain their real appearance. Inventory slot layouts are fixed, and Bedrock forms use textual controls rather than Java material icons.

Apply changes with `/shop admin reload`.

## Validation limits

Unit validation covers bounded chat replies and control/formatting rejection. The Bedrock protocol fixture additionally exercises native search and custom quantities, duplicate input responses and return navigation. Server integration validates economic actions and inventories. These checks do not prove the visual rendering, tactile usability or controller experience on an actual mobile/console client; those still need a player session.
