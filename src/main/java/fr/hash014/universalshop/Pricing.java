package fr.hash014.universalshop;

/** Marginal prices: splitting a trade cannot improve its price. */
public final class Pricing {
  private Pricing() {}

  public static long quote(
      long buy,
      long sell,
      long demand,
      int count,
      boolean buying,
      double step,
      double min,
      double max,
      double discount,
      double tax) {
    if (count < 1
        || count > 2304
        || min <= 0
        || max < min
        || step < 0
        || !Double.isFinite(step)
        || !Double.isFinite(min)
        || !Double.isFinite(max)
        || !Double.isFinite(discount)
        || !Double.isFinite(tax)
        || discount < 0
        || discount > 1
        || tax < 0
        || tax > 1) throw new IllegalArgumentException("Invalid quote parameters.");
    if ((buying ? buy : sell) < 0)
      throw new IllegalArgumentException(
          buying ? "This product cannot be bought." : "This product cannot be sold.");
    long total = 0;
    for (int i = 0; i < count; i++) {
      // A buy followed by its inverse sale observes the same marginal factor.
      double level = buying ? (double) demand + i : (double) demand - i - 1;
      double factor = Math.max(min, Math.min(max, 1 + level * step));
      long unit;
      if (buying) {
        long floor = sell < 0 ? 0 : Money.scaled(sell, factor, true);
        unit = Math.max(floor, Money.scaled(buy, factor * (1 - discount), true));
        unit = Money.scaled(unit, 1 + tax, true);
      } else {
        unit = Money.scaled(sell, factor * (1 - tax), false);
      }
      total = Money.add(total, unit);
    }
    return total;
  }
}
