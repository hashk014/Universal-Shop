package fr.hash014.universalshop;

/** Optional idle-demand half-life in complete intervals. No fractional rounding on active trades. */
public final class Demand {
  private Demand() {}

  public static long decayed(long value, long updated, long now, long halfLifeHours) {
    if (halfLifeHours <= 0 || now <= updated || value == 0) return value;
    long periods = (long) Math.floor(((double) now - updated) / (halfLifeHours * 3600000.0));
    // Integer division is symmetric toward zero and cannot overflow, even for MIN_VALUE.
    for (long i = 0; i < Math.min(periods, 64) && value != 0; i++) value /= 2;
    return value;
  }
}
