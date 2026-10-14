package fr.hash014.universalshop;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Private, one-use inputs. Chat callbacks always return to the server thread. */
final class PlayerInputs implements Listener {
  interface InputHandler {
    void accept(String value) throws Exception;
  }

  private static final class Pending {
    final InputHandler handler;
    final long expires;
    final int maxLength;
    final AtomicBoolean submitted = new AtomicBoolean();

    Pending(InputHandler handler, long expires, int maxLength) {
      this.handler = handler;
      this.expires = expires;
      this.maxLength = maxLength;
    }
  }

  private final UniversalShop plugin;
  private final ConcurrentMap<UUID, Pending> pending = new ConcurrentHashMap<UUID, Pending>();

  PlayerInputs(UniversalShop plugin) {
    this.plugin = plugin;
    Bukkit.getPluginManager().registerEvents(this, plugin);
  }

  void request(final Player player, String prompt, final InputHandler handler) {
    request(player, prompt, 128, handler);
  }

  void request(final Player player, String prompt, int maxLength, final InputHandler handler) {
    if (maxLength < 1 || maxLength > 4096) throw new IllegalArgumentException("Invalid input size limit.");
    cancel(player.getUniqueId());
    player.closeInventory();
    if (plugin.bedrock != null && plugin.bedrock.input(player, prompt, maxLength, handler)) return;
    final UUID id = player.getUniqueId();
    final Pending request = new Pending(handler, System.currentTimeMillis() + 120000, maxLength);
    pending.put(id, request);
    plugin.tell(player, prompt);
    plugin.tell(player, plugin.texts.format(player, "input.help",
        "&7Reply in chat within 2 minutes. Type cancel to stop. Your reply stays private."));
    Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
      public void run() {
        if (pending.remove(id, request) && player.isOnline())
          plugin.tell(player, plugin.texts.format(player, "input.timeout", "&eInput expired. Open the menu again."));
      }
    }, 2400L);
  }

  void cancel(UUID id) {
    pending.remove(id);
    if (plugin.bedrock != null) plugin.bedrock.sessions.close(id);
  }

  void clear() {
    pending.clear();
  }

  static String validate(String value) {
    return validate(value, 128);
  }

  static String validate(String value, int maxLength) {
    String text = value.trim();
    if (text.isEmpty() || text.length() > maxLength)
      throw new IllegalArgumentException("Enter between 1 and " + maxLength + " characters.");
    for (int i = 0; i < text.length(); i++)
      if (Character.isISOControl(text.charAt(i)) || text.charAt(i) == '\u00a7')
        throw new IllegalArgumentException("Formatting and control characters are not allowed.");
    return text;
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
  public void chat(AsyncPlayerChatEvent event) {
    final Player player = event.getPlayer();
    final Pending request = pending.get(player.getUniqueId());
    if (request == null) return;
    event.setCancelled(true);
    if (!request.submitted.compareAndSet(false, true)) return;
    final String reply = event.getMessage();
    if (!plugin.isEnabled()) return;
    Bukkit.getScheduler().runTask(plugin, new Runnable() {
      public void run() {
        if (!pending.remove(player.getUniqueId(), request) || !player.isOnline()) return;
        try {
          if (System.currentTimeMillis() > request.expires)
            throw new IllegalArgumentException("This input has expired. Open the menu again.");
          if (reply.trim().equalsIgnoreCase("cancel")) {
            plugin.tell(player, plugin.texts.format(player, "input.cancelled", "&eInput cancelled."));
            return;
          }
          request.handler.accept(validate(reply, request.maxLength));
        } catch (Exception error) {
          plugin.failure(player, error);
        }
      }
    });
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void command(PlayerCommandPreprocessEvent event) {
    // Never turn a chat reply into a command; commands leave the input flow normally.
    cancel(event.getPlayer().getUniqueId());
  }

  @EventHandler
  public void quit(PlayerQuitEvent event) {
    cancel(event.getPlayer().getUniqueId());
  }
}
