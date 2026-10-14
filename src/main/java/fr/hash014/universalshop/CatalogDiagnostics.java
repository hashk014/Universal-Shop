package fr.hash014.universalshop;

import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.*;

/** Advisory economy checks. Custom recipes, farms and external plugins still need server balancing. */
public final class CatalogDiagnostics {
  private CatalogDiagnostics() {}

  public static List<String> inspect(UniversalShop plugin) {
    List<String> result = new ArrayList<String>();
    List<Product> items = new ArrayList<Product>();
    for (Product p : plugin.products.values()) {
      if (p.commands.isEmpty()) items.add(p);
      if (p.buy == 0) result.add("FREE PURCHASE: " + p.id + " is free; verify that this is intended.");
      if (p.step > 0 && p.decayHours == 0)
        result.add("STICKY DEMAND: " + p.id + " has no automatic demand recovery (dynamic.decay-hours).");
    }
    for (int i = 0; i < items.size(); i++)
      for (int j = i + 1; j < items.size(); j++) {
        Product a = items.get(i), b = items.get(j);
        if (!a.item.isSimilar(b.item) || !worldsOverlap(a.worlds, b.worlds)) continue;
        result.add("DUPLICATE ITEM: " + a.id + " and " + b.id + " share an exact item template; sales choose the better initial unit payout.");
        duplicate(result, a, b);
        duplicate(result, b, a);
      }
    Map<String, Long> costs = new HashMap<String, Long>();
    for (Product p : items)
      if (p.buy >= 0 && !p.item.hasItemMeta()) {
        String key = key(p.item);
        Long previous = costs.get(key);
        if (previous == null || p.buy < previous) costs.put(key, p.buy);
      }
    Iterator<Recipe> recipes = Bukkit.recipeIterator();
    while (recipes.hasNext()) {
      Recipe recipe = recipes.next();
      ItemStack output = recipe.getResult();
      if (output == null || output.getAmount() < 1 || output.hasItemMeta()) continue;
      List<ItemStack> ingredients = ingredients(recipe);
      if (ingredients == null || ingredients.isEmpty()) continue;
      long cost = 0;
      boolean known = true;
      for (ItemStack ingredient : ingredients) {
        if (ingredient == null || ingredient.getType() == Material.AIR) continue;
        Long unit = ingredientCost(costs, ingredient);
        if (unit == null) { known = false; break; }
        try { cost = Math.addExact(cost, Math.multiplyExact(unit, Math.max(1, ingredient.getAmount()))); }
        catch (ArithmeticException huge) { known = false; break; }
      }
      if (!known) continue;
      for (Product p : items)
        if (p.sell >= 0 && p.item.isSimilar(output)) {
          try {
            long proceeds = Math.multiplyExact(p.sell, output.getAmount());
            if (proceeds > cost)
              result.add("CRAFTING REVIEW: " + p.id + " x" + output.getAmount() + " sells at base "
                  + Money.format(proceeds) + " versus configured ingredient buy cost " + Money.format(cost)
                  + "; inspect permissions, tax, promotions and recipe availability.");
          } catch (ArithmeticException ignored) { }
        }
    }
    if (result.isEmpty()) result.add("No duplicate/base-price recipe issue found in the currently available catalog and recipes.");
    result.add("Advisory only: discounts, dynamic extremes, farms, custom command effects and external recipes are not an economic proof.");
    return result;
  }

  static boolean worldsOverlap(List<String> a, List<String> b) {
    return a.isEmpty() || b.isEmpty() || !Collections.disjoint(a, b);
  }

  private static void duplicate(List<String> result, Product buy, Product sell) {
    if (buy.buy < 0 || sell.sell < 0) return;
    if (sell.sell > buy.buy)
      result.add("BASE RESALE LOOP: buy " + buy.id + " for " + Money.format(buy.buy)
          + ", sell as " + sell.id + " for " + Money.format(sell.sell)
          + "; check shared permission/world access.");
    else try {
      if (Money.scaled(sell.sell, sell.max, false) > Math.max(
          Money.scaled(buy.buy, buy.min, true), buy.sell < 0 ? 0 : Money.scaled(buy.sell, buy.min, true)))
        result.add("DYNAMIC RESALE REVIEW: " + buy.id + " -> " + sell.id + " can overlap at independent demand extremes.");
    } catch (ArithmeticException | IllegalArgumentException extreme) {
      result.add("PRICE RANGE REVIEW: " + buy.id + " / " + sell.id + " has a configured dynamic extreme above the supported money range.");
    }
  }

  private static String key(ItemStack item) { return item.getType().name() + ":" + item.getDurability(); }

  private static Long ingredientCost(Map<String, Long> costs, ItemStack item) {
    if (item.getDurability() != Short.MAX_VALUE && item.getDurability() != -1) return costs.get(key(item));
    Long best = null;
    for (Map.Entry<String, Long> entry : costs.entrySet())
      if (entry.getKey().startsWith(item.getType().name() + ":") && (best == null || entry.getValue() < best)) best = entry.getValue();
    return best;
  }

  private static List<ItemStack> ingredients(Recipe recipe) {
    if (recipe instanceof ShapelessRecipe) return ((ShapelessRecipe) recipe).getIngredientList();
    if (recipe instanceof ShapedRecipe) {
      ShapedRecipe shaped = (ShapedRecipe) recipe;
      Map<Character, ItemStack> map = shaped.getIngredientMap();
      List<ItemStack> ingredients = new ArrayList<ItemStack>();
      for (String row : shaped.getShape()) for (char c : row.toCharArray()) {
        ItemStack item = map.get(c); if (item != null) ingredients.add(item);
      }
      return ingredients;
    }
    // Furnace fuel is deliberately not guessed; omit smelting diagnostics.
    return null;
  }
}
