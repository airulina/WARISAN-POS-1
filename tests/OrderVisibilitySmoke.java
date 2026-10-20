package my.warisan.pos;

/** Rules for Owner's ORDERS page. The Firebase transaction audit is retained. */
public final class OrderVisibilitySmoke {
  private static void check(String status,boolean expected){
    boolean actual=OrderVisibility.showInOwnerOrders(status);
    if(actual!=expected)
      throw new AssertionError("status="+status+" expected "+expected+" got "+actual);
  }
  public static void main(String[] args){
    check("PENDING",true);
    check("PAID",true);
    check("REFUNDED",true); // Refund stays in Owner's history.
    check("CANCELLED",false); // Cancelled before payment is never shown.
    check("cancelled",false);
    check(null,false);
    check("",false);
    check("UNKNOWN",false);
    System.out.println("PASS: cancelled orders hidden; refunds and paid orders shown");
  }
}
