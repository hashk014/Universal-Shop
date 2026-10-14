package fr.hash014.universalshop;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/** Shared permission mapping for commands, inventory menus and native forms. */
public final class AdminPermissions {
  public static final List<String> SCOPES =
      Arrays.asList("catalog", "commands", "stock", "balance", "history", "recovery", "manage", "backup", "reload");

  private AdminPermissions() {}

  public static String scope(String action) {
    String value = action.toLowerCase(Locale.ROOT).replace(':', '.');
    if (value.startsWith("admin.")) value = value.substring(6);
    if (Arrays.asList("add", "remove", "price", "duplicate", "rename", "category", "editor", "diagnose", "sign").contains(value))
      return "catalog";
    if (value.equals("export")) return "backup";
    if (value.equals("command")) return "manage";
    if (value.startsWith("manage.")) {
      String operation = value.substring(7);
      if (Arrays.asList("stock", "restock", "reset-demand").contains(operation)) return "stock";
      if (Arrays.asList("stats", "report").contains(operation)) return "history";
      return "manage";
    }
    return value;
  }

  public static boolean allowed(Predicate<String> permission, String action) {
    // Bukkit's legacy node defaults to op; an explicit negative attachment still takes precedence.
    if (permission.test("universalshop.admin")) return true;
    String scope = scope(action);
    if (scope.equals("admin") || scope.equals("panel")) {
      for (String candidate : SCOPES)
        if (permission.test("universalshop.admin." + candidate)) return true;
      return false;
    }
    return SCOPES.contains(scope) && permission.test("universalshop.admin." + scope);
  }
}
