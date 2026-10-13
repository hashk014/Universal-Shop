package fr.hash014.universalshop;

import org.bukkit.entity.Player;
import org.bukkit.event.*;

/** Fired before preparing a server-shop transaction. Listeners may cancel, not change its price. */
public final class ShopTradeEvent extends Event implements Cancellable {
  private static final HandlerList HANDLERS = new HandlerList();
  private final Player player;
  private final String productId;
  private final int quantity;
  private final boolean buying;
  private final long totalCents;
  private boolean cancelled;

  public ShopTradeEvent(Player p, String id, int n, boolean buying, long cents) {
    this.player = p;
    this.productId = id;
    this.quantity = n;
    this.buying = buying;
    this.totalCents = cents;
  }

  public Player getPlayer() {
    return player;
  }

  public String getProductId() {
    return productId;
  }

  public int getQuantity() {
    return quantity;
  }

  public boolean isBuying() {
    return buying;
  }

  public long getTotalCents() {
    return totalCents;
  }

  public boolean isCancelled() {
    return cancelled;
  }

  public void setCancelled(boolean cancelled) {
    this.cancelled = cancelled;
  }

  public HandlerList getHandlers() {
    return HANDLERS;
  }

  public static HandlerList getHandlerList() {
    return HANDLERS;
  }
}
