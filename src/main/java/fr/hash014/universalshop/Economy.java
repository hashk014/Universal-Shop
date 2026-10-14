package fr.hash014.universalshop;

import java.lang.reflect.*;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

/** Optional Vault binding, with no dependency needed for the internal wallet. */
public final class Economy {
  private final Object provider;
  private final Class<?> api;

  public Economy(String mode) throws Exception {
    if (mode.equalsIgnoreCase("INTERNAL")) {
      provider = null;
      api = null;
    } else if (mode.equalsIgnoreCase("VAULT")) {
      api = Class.forName("net.milkbowl.vault.economy.Economy");
      RegisteredServiceProvider<?> registration = Bukkit.getServicesManager().getRegistration(api);
      if (registration == null)
        throw new IllegalStateException("VAULT selected but no economy provider is registered.");
      provider = registration.getProvider();
    } else throw new IllegalArgumentException("economy must be INTERNAL or VAULT.");
  }

  public boolean external() {
    return provider != null;
  }

  public String providerName() throws Exception {
    return external() ? String.valueOf(api.getMethod("getName").invoke(provider)) : "INTERNAL";
  }

  public long balance(Player player) throws Exception {
    double amount =
        ((Number) api.getMethod("getBalance", OfflinePlayer.class).invoke(provider, player))
            .doubleValue();
    return Money.fromProvider(amount);
  }

  public boolean change(Player player, long amount, boolean deposit) throws Exception {
    Object response =
        api.getMethod(
                deposit ? "depositPlayer" : "withdrawPlayer", OfflinePlayer.class, double.class)
            .invoke(provider, player, amount / 100.0);
    return (Boolean) response.getClass().getMethod("transactionSuccess").invoke(response);
  }
}
