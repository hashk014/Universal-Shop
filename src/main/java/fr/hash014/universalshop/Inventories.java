package fr.hash014.universalshop;

import java.util.*;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Restrict changes to the 36 storage slots; armor and offhand are never sold. */
public final class Inventories {
  private Inventories() {}

  public static ItemStack[] snapshot(Player p) {
    ItemStack[] items = new ItemStack[36];
    for (int i = 0; i < 36; i++) {
      ItemStack item = p.getInventory().getItem(i);
      items[i] = item == null ? null : item.clone();
    }
    return items;
  }

  public static void apply(Player p, ItemStack[] items) {
    for (int i = 0; i < 36; i++) p.getInventory().setItem(i, items[i]);
    p.updateInventory();
  }

  public static int count(ItemStack[] contents, ItemStack sample) {
    int count = 0;
    for (ItemStack item : contents)
      if (item != null && item.isSimilar(sample)) count += item.getAmount();
    return count;
  }

  public static int capacity(ItemStack[] contents, ItemStack sample) {
    int room = 0;
    for (ItemStack item : contents)
      if (item == null || item.getType() == Material.AIR) room += sample.getMaxStackSize();
      else if (item.isSimilar(sample)) room += Math.max(0, sample.getMaxStackSize() - item.getAmount());
    return room;
  }

  public static ItemStack[] change(ItemStack[] before, ItemStack sample, int delta) {
    ItemStack[] result = new ItemStack[before.length];
    for (int i = 0; i < before.length; i++)
      result[i] = before[i] == null ? null : before[i].clone();
    int left = Math.abs(delta);
    if (delta < 0) {
      for (int i = 0; i < result.length && left > 0; i++)
        if (result[i] != null && result[i].isSimilar(sample)) {
          int take = Math.min(left, result[i].getAmount());
          left -= take;
          if (take == result[i].getAmount()) result[i] = null;
          else result[i].setAmount(result[i].getAmount() - take);
        }
    } else {
      for (int pass = 0; pass < 2; pass++)
        for (int i = 0; i < result.length && left > 0; i++) {
          ItemStack item = result[i];
          if (pass == 0 && item != null && item.isSimilar(sample)) {
            int take = Math.min(left, Math.max(0, sample.getMaxStackSize() - item.getAmount()));
            item.setAmount(item.getAmount() + take);
            left -= take;
          } else if (pass == 1 && (item == null || item.getType() == Material.AIR)) {
            int take = Math.min(left, sample.getMaxStackSize());
            result[i] = sample.clone();
            result[i].setAmount(take);
            left -= take;
          }
        }
    }
    if (left > 0)
      throw new IllegalArgumentException(
          delta < 0
              ? "You do not have enough matching items."
              : "Your inventory does not have enough space.");
    return result;
  }

  public static String evidence(Player p, ItemStack[] before, ItemStack[] after) {
    YamlConfiguration y = new YamlConfiguration();
    y.set("player", p.getUniqueId().toString());
    y.set("before", Arrays.asList(before));
    y.set("after", Arrays.asList(after));
    return SafeYaml.write(y);
  }
}
