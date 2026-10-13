package fr.hash014.universalshop;

import java.util.*;

public final class Rotation {
  private Rotation() {}

  public static Set<String> pick(
      Collection<String> candidates, String seed, long epoch, int count) {
    List<String> list = new ArrayList<String>(candidates);
    Collections.sort(list);
    Collections.shuffle(list, new Random(((long) seed.hashCode() << 32) ^ epoch));
    return new LinkedHashSet<String>(list.subList(0, Math.min(Math.max(0, count), list.size())));
  }
}
