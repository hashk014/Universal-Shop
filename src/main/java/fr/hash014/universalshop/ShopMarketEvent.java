package fr.hash014.universalshop;

import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.event.*;

/** Cancellable pre-prepare market event. Listeners may cancel but must not mutate inventory/ledger. */
public final class ShopMarketEvent extends Event implements Cancellable {
  private static final HandlerList HANDLERS = new HandlerList();
  private final Player player;
  private final String listingId, action;
  private final UUID seller;
  private final int quantity;
  private final long totalCents;
  private boolean cancelled;
  public ShopMarketEvent(Player player, String id, UUID seller, String action, int quantity, long cents) {
    this.player = player; this.listingId = id; this.seller = seller; this.action = action;
    this.quantity = quantity; this.totalCents = cents;
  }
  public Player getPlayer() { return player; }
  public String getListingId() { return listingId; }
  public UUID getSeller() { return seller; }
  public String getAction() { return action; }
  public int getQuantity() { return quantity; }
  public long getTotalCents() { return totalCents; }
  public boolean isCancelled() { return cancelled; }
  public void setCancelled(boolean value) { cancelled = value; }
  public HandlerList getHandlers() { return HANDLERS; }
  public static HandlerList getHandlerList() { return HANDLERS; }
}
