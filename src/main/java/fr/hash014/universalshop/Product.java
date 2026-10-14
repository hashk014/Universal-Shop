package fr.hash014.universalshop;

import java.util.*;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

public final class Product {
  public final String id, name, category, permission;
  public final ItemStack item;
  public final long buy, sell, stock, restockHours, dailyBuy, dailySell, decayHours;
  public final double step, min, max;
  public final boolean rotating, rotationOnly;
  public final List<String> commands, worlds;

  public Product(String id, ConfigurationSection s) {
    if (!id.matches("[a-z0-9_-]{1,48}"))
      throw new IllegalArgumentException("Invalid product ID: " + id);
    this.id = id;
    name = s.getString("name", id);
    category = s.getString("category", "General");
    permission = s.getString("permission", "");
    commands = Collections.unmodifiableList(s.getStringList("commands"));
    worlds = Collections.unmodifiableList(s.getStringList("worlds"));
    ItemStack configured = s.getItemStack("item");
    if (configured != null) item = configured.clone();
    else {
      Material material = material(s.getString("material", "STONE"));
      if (material == null)
        throw new UnsupportedOperationException("Unknown material: " + s.getString("material"));
      item = new ItemStack(material, 1, (short) s.getInt("data", 0));
    }
    if (item.getType() == Material.AIR || item.getMaxStackSize() < 1)
      throw new IllegalArgumentException("Invalid item: " + id);
    item.setAmount(1);
    buy = price(s, "buy");
    sell = price(s, "sell");
    if (buy >= 0 && sell > buy)
      throw new IllegalArgumentException(id + ": sell price must not exceed buy price.");
    if (!commands.isEmpty() && sell >= 0)
      throw new IllegalArgumentException(id + ": command products cannot be sold.");
    for (String command : commands)
      if (command.trim().isEmpty() || command.contains("\n") || command.contains("\r"))
        throw new IllegalArgumentException("Invalid delivery command.");
    stock = s.getLong("stock", -1);
    restockHours = s.getLong("restock-hours", 0);
    dailyBuy = s.getLong("daily-buy-limit", 0);
    dailySell = s.getLong("daily-sell-limit", 0);
    if (stock < -1
        || stock > 1000000000L
        || restockHours < 0
        || restockHours > 876000
        || dailyBuy < 0
        || dailySell < 0) throw new IllegalArgumentException("Invalid stock/limit for " + id);
    boolean dynamic = s.getBoolean("dynamic.enabled", false);
    decayHours = dynamic ? s.getLong("dynamic.decay-hours", 0) : 0;
    if (decayHours < 0 || decayHours > 876000)
      throw new IllegalArgumentException("Invalid dynamic decay hours for " + id);
    step = dynamic ? s.getDouble("dynamic.step", .005) : 0;
    min = dynamic ? s.getDouble("dynamic.min", .5) : 1;
    max = dynamic ? s.getDouble("dynamic.max", 2) : 1;
    if (!Double.isFinite(step)
        || !Double.isFinite(min)
        || !Double.isFinite(max)
        || step < 0
        || min <= 0
        || max < min
        || max > 1000) throw new IllegalArgumentException("Invalid dynamic pricing for " + id);
    rotating = s.getBoolean("rotating", false);
    rotationOnly = s.getBoolean("rotation-only", false);
    if (rotationOnly && !rotating)
      throw new IllegalArgumentException("rotation-only requires rotating: true.");
  }

  private static long price(ConfigurationSection s, String key) {
    String v = String.valueOf(s.get(key, -1));
    return v.equals("-1") || v.equals("-1.0") ? -1 : Money.parse(v);
  }

  public static Material material(String aliases) {
    List<String> names = new ArrayList<String>(Arrays.asList(aliases.split("\\|")));
    try {
      Enum.valueOf(Material.class, "OAK_PLANKS");
    } catch (IllegalArgumentException oldServer) {
      Collections.reverse(names);
    }
    for (String alias : names) {
      try {
        return Enum.valueOf(Material.class, alias.trim().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException ignored) {
      }
    }
    return null;
  }
}
