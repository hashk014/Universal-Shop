package fr.hash014.universalshop;

import java.io.File;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.*;

/** Validated chest geometry. Slots and their actions always move together. */
public final class MenuLayouts {
  private static final Map<String, Spec> SPECS = new LinkedHashMap<String, Spec>();
  static {
    spec("browse", 6, range(0, 35), 37,38,39,41,43,45,46,47,48,49,50,51,52,53);
    spec("categories", 6, range(0, 44), 45,49,53);
    spec("sort", 3, new int[0], 10,13,16,22);
    spec("confirm", 4, new int[0], 11,13,15,18,19,20,21,23,24,26);
    spec("sellall", 6, range(18, 44), 11,13,15);
    spec("market", 6, range(0, 35), 36,37,38,39,40,41,42,43,45,49,52,53);
    spec("market-create", 3, new int[0], 11,13,15);
    spec("market-confirm", 3, new int[0], 11,13,15);
    spec("admin", 6, new int[0], 4,10,11,12,13,14,15,16,17,18,45,46,47,49,51,53);
    spec("editor-list", 6, range(0, 44), 45,46,48,50,52,53);
    spec("editor-product", 6, new int[0], 4,10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34,35,45,47,49,53);
    spec("editor-confirm", 3, new int[0], 11,13,15);
    spec("editor-preview", 3, new int[0], 13,22);
  }
  private static int[] range(int first, int last) {
    int[] result = new int[last - first + 1];
    for (int i = 0; i < result.length; i++) result[i] = first + i;
    return result;
  }
  private static void spec(String id, int rows, int[] content, int... controls) {
    SPECS.put(id, new Spec(rows, content, controls));
  }
  private static final class Spec {
    final int rows; final int[] content, controls;
    Spec(int rows, int[] content, int[] controls) { this.rows = rows; this.content = content; this.controls = controls; }
  }
  static final class Decoration {
    final Material material; final String name; final String[] lore; final List<Integer> slots;
    Decoration(Material material, String name, List<String> lore, List<Integer> slots) {
      this.material = material; this.name = name; this.lore = lore.toArray(new String[0]); this.slots = slots;
    }
  }
  public static final class Layout {
    final int size; final String title, background;
    final Map<Integer, Integer> positions = new LinkedHashMap<Integer, Integer>();
    final List<Integer> content = new ArrayList<Integer>();
    final List<Decoration> decorations = new ArrayList<Decoration>();
    final Set<Integer> reserved = new HashSet<Integer>();
    Layout(int size, String title, String background) { this.size = size; this.title = title; this.background = background; }
    public int capacity() { return content.size(); }
    int target(int source) { Integer slot = positions.get(source); return slot == null ? -1 : slot; }
  }
  private final Map<String, Layout> layouts = new LinkedHashMap<String, Layout>();
  public MenuLayouts(YamlConfiguration yaml) {
    for (String key : yaml.getKeys(false)) if (!SPECS.containsKey(key)) fail(key, "unknown menu");
    for (Map.Entry<String, Spec> entry : SPECS.entrySet()) {
      String id = entry.getKey(); Spec spec = entry.getValue();
      if (yaml.contains(id) && !yaml.isConfigurationSection(id)) fail(id, "expected a section");
      ConfigurationSection section = yaml.getConfigurationSection(id);
      int rows = section == null ? spec.rows : integer(section.get("rows", spec.rows), id + ".rows");
      if (rows < 1 || rows > 6) fail(id, "rows must be 1 through 6");
      Layout layout = new Layout(rows * 9, section == null ? "" : section.getString("title", ""),
          section == null ? null : section.getString("background", null));
      if (section != null) for (String key : section.getKeys(false))
        if (!Arrays.asList("rows", "title", "background", "content-slots", "slots", "decorations").contains(key)) fail(id, "unknown setting " + key);
      if (layout.background != null && !layout.background.isEmpty()) material(layout.background, id + ".background");
      List<Integer> content = new ArrayList<Integer>();
      if (section != null && section.contains("content-slots")) content = slots(section.get("content-slots"), id + ".content-slots");
      else for (int slot : spec.content) content.add(slot);
      if (content.size() > spec.content.length || (spec.content.length > 0 && content.isEmpty()))
        fail(id, "content-slots must contain 1 through " + spec.content.length + " slots");
      if (spec.content.length == 0 && !content.isEmpty()) fail(id, "this menu has no paginated content");
      for (int i = 0; i < content.size(); i++) {
        reserve(layout, content.get(i), id); layout.positions.put(spec.content[i], content.get(i)); layout.content.add(content.get(i));
      }
      ConfigurationSection mapping = section == null ? null : section.getConfigurationSection("slots");
      if (section != null && section.contains("slots") && mapping == null) fail(id, "slots must be a section with quoted slot keys");
      Set<String> allowed = new HashSet<String>();
      for (int source : spec.controls) {
        String key = String.valueOf(source); allowed.add(key);
        int target = mapping == null ? source : integer(mapping.get(key, source), id + ".slots." + key);
        reserve(layout, target, id); layout.positions.put(source, target);
      }
      if (mapping != null) for (String key : mapping.getKeys(false)) if (!allowed.contains(key)) fail(id, "unknown control slot " + key);
      if (section != null && section.contains("decorations")) {
        Object raw = section.get("decorations");
        if (!(raw instanceof List)) fail(id, "decorations must be a list");
        for (Object value : (List<?>) raw) {
          if (!(value instanceof Map)) fail(id, "each decoration must be a map");
          Map<?, ?> deco = (Map<?, ?>) value;
          for (Object key : deco.keySet()) if (!Arrays.asList("material", "name", "lore", "slots").contains(key)) fail(id, "unknown decoration setting " + key);
          List<Integer> targets = slots(deco.get("slots"), id + ".decorations.slots");
          for (int target : targets) reserve(layout, target, id);
          Material type = material(String.valueOf(deco.get("material")), id + ".decorations.material");
          List<String> lore = new ArrayList<String>();
          if (deco.containsKey("lore")) {
            if (!(deco.get("lore") instanceof List)) fail(id, "decoration lore must be a list");
            for (Object line : (List<?>) deco.get("lore")) lore.add(String.valueOf(line));
          }
          layout.decorations.add(new Decoration(type, deco.containsKey("name") ? String.valueOf(deco.get("name")) : " ", lore, targets));
        }
      }
      layouts.put(id, layout);
    }
  }
  static MenuLayouts load(File file) throws Exception {
    YamlConfiguration yaml = new YamlConfiguration(); yaml.load(file); return new MenuLayouts(yaml);
  }
  public Layout get(String id) {
    Layout result = layouts.get(id); if (result == null) throw new IllegalArgumentException("Unknown chest menu: " + id); return result;
  }
  private static int integer(Object value, String path) {
    if (!(value instanceof Number) || ((Number) value).doubleValue() != ((Number) value).intValue()) fail(path, "expected an integer");
    return ((Number) value).intValue();
  }
  private static List<Integer> slots(Object value, String path) {
    if (!(value instanceof List)) fail(path, "expected a list of slot numbers");
    List<Integer> result = new ArrayList<Integer>();
    for (Object slot : (List<?>) value) result.add(integer(slot, path)); return result;
  }
  private static void reserve(Layout layout, int slot, String id) {
    if (slot < 0 || slot >= layout.size) fail(id, "slot " + slot + " outside the selected chest size");
    if (!layout.reserved.add(slot)) fail(id, "slot " + slot + " is used more than once");
  }
  private static Material material(String value, String path) {
    Material result = Product.material(value);
    if (result == null || result == Material.AIR) fail(path, "unknown material; use legacy fallback aliases if needed"); return result;
  }
  private static void fail(String id, String message) { throw new IllegalArgumentException("menus.yml " + id + ": " + message); }
  Inventory apply(String id, Inventory source, InventoryHolder holder, Map<Integer, String> actions,
      int page, String defaultTitle, String defaultBackground) {
    Layout layout = get(id);
    String title = layout.title.isEmpty() ? defaultTitle : layout.title.replace("{page}", String.valueOf(page + 1));
    Inventory target = Bukkit.createInventory(holder, layout.size, ShopMenus.menuTitle(title));
    Map<Integer, String> moved = new HashMap<Integer, String>();
    for (Map.Entry<Integer, Integer> slot : layout.positions.entrySet()) {
      ItemStack item = source.getItem(slot.getKey());
      if (item != null) target.setItem(slot.getValue(), item);
      String action = actions.get(slot.getKey()); if (action != null) moved.put(slot.getValue(), action);
    }
    actions.clear(); actions.putAll(moved);
    String filler = layout.background == null ? defaultBackground : layout.background;
    Material background = Product.material(filler == null ? "" : filler);
    if (background != null && background != Material.AIR) for (int slot = 0; slot < target.getSize(); slot++)
      if (target.getItem(slot) == null) target.setItem(slot, ShopMenus.icon(background, " "));
    for (Decoration deco : layout.decorations) for (int slot : deco.slots)
      target.setItem(slot, ShopMenus.icon(deco.material, deco.name, deco.lore));
    return target;
  }
}
