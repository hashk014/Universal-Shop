# Customize chest menus

UniversalShop 2.1 creates `plugins/UniversalShop/menus.yml`. Edit this file, then run
`/shop admin reload`. Existing installations receive the file automatically. The
default layout preserves the familiar menus. Native Bedrock forms retain their
own layout; chest coordinates apply to Java inventory menus.

Slots start at **0**, at the top left. Each row contains nine slots. Configure:

- `rows`: 1 through 6; all configured slots must fit.
- `title`: optional title override with `&` colors and `{page}`. An empty title
  retains the player's translated title. Legacy titles are safely limited to 32 characters.
- `content-slots`: ordered positions for products, categories, listings or basket
  previews. This list sets the page size and may be reduced or reordered. Maximums
  are 36 for browse/market, 45 for categories/editor-list, and 27 for sellall.
- `slots`: **original control slot -> destination slot**. Quote the original
  numbers as YAML keys. Both the icon and its action move together. Omitted
  control mappings retain their default destination.
- `background`: Material name or fallback aliases, e.g. `GRAY_STAINED_GLASS_PANE|GLASS`.
  Omit to inherit `menu-background` from config.yml; an empty string disables it
  for this menu.
- `decorations`: passive items with `slots`, `material`, `name` and optional `lore`.
  They cannot share a slot with content, controls or other decorations.

All reserved slots are validated, even if permissions or the current page hide a
button. Unknown settings, collisions, invalid sizes/materials and empty content
lists reject the entire reload, preserving the last working layout. Valid reloads
close existing chest menus so players open fresh layouts. Reloading also discards
catalog editor drafts, as in previous releases; publish drafts before reloading.
An invalid layout on startup prevents the plugin from enabling until corrected.

## Compact category menu

Replace the `categories` section with:

```yaml
categories:
  rows: 3
  title: '&8Categories | {page}'
  content-slots: [10, 11, 12, 13, 14, 15, 16]
  slots:
    '45': 18 # Previous page
    '49': 22 # All products
    '53': 26 # Next page
  background: 'GRAY_STAINED_GLASS_PANE|GLASS'
  decorations:
    - slots: [0, 8]
      material: 'GOLD_BLOCK'
      name: '&6UniversalShop'
      lore: ['&7Choose a category']
```

Categories paginate seven at a time; previous/next remain reachable. In `confirm`,
swap the controls without changing their behavior:

```yaml
confirm:
  slots:
    '11': 15 # Confirm now appears on the right
    '15': 11 # Cancel now appears on the left
```

## Default controls

The generated file contains every control mapping. The main menus use:

| Menu | Original slots and meaning |
| --- | --- |
| browse | 37 favorites, 38 recent, 39 sorting, 41 all products, 43 editor, 45 previous, 46 categories, 47 deals, 48 search, 49 balance, 50 market, 51 sell inventory, 52 close, 53 next |
| categories | 45 previous, 49 all products, 53 next |
| sort | 10 catalog order, 13 name, 16 price, 22 back |
| confirm | 11 confirm, 13 product preview, 15 cancel, 18–21 quantity presets, 23 custom quantity, 24 maximum, 26 buy/sell switch |
| sellall | 11 confirm sale, 13 summary, 15 cancel |
| market | 36 own listings, 37 expired, 38 search, 39 sort, 40 all listings, 41 create, 42 claim, 43 notices, 45 previous, 49 server shop, 52 close, 53 next |
| market-create / market-confirm | 11 publish/buy/reclaim, 13 preview, 15 cancel |
| admin | 4 catalog editor, 10–18 management modules, 45 stock, 46 statistics, 47 CSV, 49 open, 51 close shop, 53 close menu |
| editor-list | 45 previous, 46 search, 48 create, 50 diagnostics, 52 dashboard, 53 next |
| editor-product | 4 template; field/action slots as listed in menus.yml; 45 back, 47 delete, 49 review/save, 53 close |
| editor-confirm | 11 first confirmation, 13 final confirmation, 15 cancel; only one confirmation is displayed per step |
| editor-preview | 13 product preview, 22 return |

The same trade, permission, quote-expiration and duplicate-click checks apply to
custom layouts. Layouts cannot add arbitrary commands or bypass transaction checks.
