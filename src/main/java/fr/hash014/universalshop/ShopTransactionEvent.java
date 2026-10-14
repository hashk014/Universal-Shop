package fr.hash014.universalshop;

import java.util.UUID;
import org.bukkit.event.*;

/** Informational event after durable commit; never cancellable and never a delivery retry signal. */
public final class ShopTransactionEvent extends Event {
  private static final HandlerList HANDLERS = new HandlerList();
  private final UUID player;
  private final String transactionId, description;
  private final long cents;
  private final boolean credit;
  public ShopTransactionEvent(UUID player, String id, String description, long cents, boolean credit) {
    this.player = player; this.transactionId = id; this.description = description; this.cents = cents; this.credit = credit;
  }
  public UUID getPlayerId() { return player; }
  public String getTransactionId() { return transactionId; }
  public String getDescription() { return description; }
  public long getTotalCents() { return cents; }
  public boolean isCredit() { return credit; }
  public HandlerList getHandlers() { return HANDLERS; }
  public static HandlerList getHandlerList() { return HANDLERS; }
}
