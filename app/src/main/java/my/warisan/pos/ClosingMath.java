package my.warisan.pos;

/** Cash-drawer reconciliation, in integer sen to avoid rounding errors. */
final class ClosingMath {
  private ClosingMath() {}

  static int expectedCash(int opening, int cashSales, int cashExpenses) {
    if (opening < 0 || cashSales < 0 || cashExpenses < 0)
      throw new IllegalArgumentException("Cash inputs must be nonnegative");
    long amount = (long) opening + cashSales - cashExpenses;
    if (amount < Integer.MIN_VALUE || amount > Integer.MAX_VALUE)
      throw new ArithmeticException("Cash total exceeds supported range");
    return (int) amount; // Losses are real: never clamp negative closings to zero.
  }
}
