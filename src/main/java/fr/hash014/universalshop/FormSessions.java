package fr.hash014.universalshop;

import java.util.*;

/** Main-thread session tokens reject duplicate, replaced, expired and reloaded form responses. */
final class FormSessions {
  private final Map<UUID, UUID> active = new HashMap<UUID, UUID>();

  UUID open(UUID player) {
    UUID token = UUID.randomUUID();
    active.put(player, token);
    return token;
  }

  boolean consume(UUID player, UUID token, long expiry, long now) {
    if (!token.equals(active.get(player))) return false;
    active.remove(player);
    return now <= expiry;
  }

  void close(UUID player) {
    active.remove(player);
  }

  void clear() {
    active.clear();
  }
}
