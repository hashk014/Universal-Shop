package fr.hash014.universalshop;

import java.util.*;

/** Shared action policy for commands, aliases, inventory buttons and Bedrock forms. */
public final class CommandPolicy {
  private static final Set<String> KNOWN =
      new HashSet<String>(
          Arrays.asList(
              "shop",
              "help",
              "buy",
              "sell",
              "sellhand",
              "sellall",
              "balance",
              "pay",
              "search",
              "category",
              "deals",
              "language",
              "market",
              "market.list",
              "market.buy",
              "market.cancel",
              "market.claim",
              "market.notices",
              "favorite",
              "favorites",
              "recent",
              "sort",
              "stats",
              "admin",
              "admin.add",
              "admin.remove",
              "admin.price",
              "admin.stock",
              "admin.balance",
              "admin.history",
              "admin.reload",
              "admin.recovery",
              "admin.export",
              "admin.command",
              "admin.manage",
              "admin.panel",
              "admin.duplicate",
              "admin.rename",
              "admin.category",
              "admin.editor",
              "admin.diagnose",
              "admin.backup"));
  private final Set<String> disabled = new HashSet<String>();

  public CommandPolicy(Collection<String> values) {
    for (String value : values) {
      String normalized = normalize(value);
      if (!KNOWN.contains(normalized))
        throw new IllegalArgumentException("Unknown disabled command: " + value);
      disabled.add(normalized);
    }
  }

  public static String normalize(String value) {
    return value.trim().toLowerCase(Locale.ROOT).replace(' ', '.');
  }

  public boolean enabled(String action) {
    String path = normalize(action);
    if (disabled.contains("shop")) return false;
    if (disabled.contains(path)) return false;
    for (int dot = path.indexOf('.'); dot >= 0; dot = path.indexOf('.', dot + 1))
      if (disabled.contains(path.substring(0, dot))) return false;
    return true;
  }

  public static String path(String[] args) {
    if (args.length == 0) return "shop";
    String root = normalize(args[0]);
    if ((root.equals("admin") || root.equals("market"))
        && args.length > 1
        && !args[1].matches("[0-9]+")) return root + "." + normalize(args[1]);
    return KNOWN.contains(root) ? root : "search";
  }
}
