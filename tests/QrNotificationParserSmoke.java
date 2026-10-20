package my.warisan.pos;

/** No bank account is needed: synthetic notifications exercise the strict parser. */
public final class QrNotificationParserSmoke {
  private static void eq(int wanted,int got,String message){
    if(wanted!=got)throw new AssertionError(message+": expected "+wanted+" got "+got);
  }
  public static void main(String[] args){
    eq(1600,QrNotificationParser.uniqueIncomingCents(
      "DuitNow QR","RM 16.00 received"),"QR incoming");
    eq(1600,QrNotificationParser.uniqueIncomingCents(
      "Bayaran diterima","RM16.00"),"BM incoming");
    eq(120050,QrNotificationParser.uniqueIncomingCents(
      "Payment received","RM1,200.50 credited to your account"),"comma money");
    eq(-1,QrNotificationParser.uniqueIncomingCents(
      "Payment successful","You paid to merchant RM16.00"),"no outgoing");
    eq(-1,QrNotificationParser.uniqueIncomingCents(
      "RM16.00 debited","You received balance notification"),"mixed outgoing");
    eq(-1,QrNotificationParser.uniqueIncomingCents(
      "Received RM16.00","Balance RM50.00"),"no ambiguous amounts");
    eq(-1,QrNotificationParser.uniqueIncomingCents(
      "Money request","Payment RM16.00 pending"),"no pending payments");
    eq(1650,QrNotificationParser.uniqueIncomingCents(
      "Received payment","RM16.50"),"amount cents parse");
    eq(-1,QrNotificationParser.uniqueIncomingCents(
      "DuitNow","RM16.00"),"needs incoming keyword");
    eq(-1,QrNotificationParser.uniqueIncomingCents(
      "Payment received","RM16.00 refunded"),"no refund as payment");
    System.out.println("QR notification parser smoke: PASS");
  }
}
