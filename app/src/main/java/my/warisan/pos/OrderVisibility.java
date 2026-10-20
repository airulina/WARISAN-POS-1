package my.warisan.pos;

/**
 * What the Owner sees in ordinary branch-order lists.
 * Cancelled before payment = no sale / no active order; keep backend document
 * only as a stock-reversal audit trail. Refunded after payment MUST remain
 * visible so that money returned is traceable.
 */
final class OrderVisibility {
  private OrderVisibility(){}
  static boolean showInOwnerOrders(String status){
    return "PENDING".equals(status)||"PAID".equals(status)||
      "REFUNDED".equals(status);
  }
}
