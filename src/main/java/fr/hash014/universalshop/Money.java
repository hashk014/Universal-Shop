package fr.hash014.universalshop;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Integer minor units throughout the ledger; no floating point balances. */
public final class Money {
  public static final long MAX = 9_000_000_000_000L;

  private Money() {}

  public static long parse(String text) {
    long value =
        new BigDecimal(text)
            .movePointRight(2)
            .setScale(0, RoundingMode.UNNECESSARY)
            .longValueExact();
    if (value < 0 || value > MAX)
      throw new IllegalArgumentException("Amount is outside the supported range.");
    return value;
  }

  public static long add(long a, long b) {
    long value = Math.addExact(a, b);
    if (value < 0 || value > MAX)
      throw new IllegalArgumentException("Balance or total is outside the supported range.");
    return value;
  }

  public static String format(long cents) {
    return BigDecimal.valueOf(cents, 2).toPlainString();
  }

  public static long fromProvider(double amount) {
    if (!Double.isFinite(amount) || amount < 0)
      throw new IllegalArgumentException("Economy provider returned an invalid balance.");
    BigDecimal cents =
        BigDecimal.valueOf(amount).movePointRight(2).setScale(0, RoundingMode.HALF_UP);
    return cents.compareTo(BigDecimal.valueOf(MAX)) > 0 ? MAX : cents.longValueExact();
  }

  public static long scaled(long cents, double factor, boolean roundUp) {
    if (!Double.isFinite(factor) || factor < 0)
      throw new IllegalArgumentException("Invalid price factor.");
    long n =
        BigDecimal.valueOf(cents)
            .multiply(BigDecimal.valueOf(factor))
            .setScale(0, roundUp ? RoundingMode.CEILING : RoundingMode.FLOOR)
            .longValueExact();
    if (n < 0 || n > MAX) throw new IllegalArgumentException("Price exceeds the supported range.");
    return n;
  }
}
