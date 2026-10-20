package my.warisan.pos;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strict, independent text parser. No app or bank notification is trusted by amount alone. */
final class QrNotificationParser {
  private QrNotificationParser(){}

  private static final Pattern MONEY=Pattern.compile(
    "(?i)(?<![a-z0-9])RM\\s*([0-9]{1,3}(?:,[0-9]{3})*(?:\\.[0-9]{2})|[0-9]+\\.[0-9]{2})(?![a-z0-9.])");
  private static final Pattern INCOMING=Pattern.compile(
    "(?i)\\b(received|receive|credited|credit\\s+to|terima|diterima|wang\\s+masuk|duit\\s+masuk|bayaran\\s+diterima|payment\\s+received|incoming\\s+transfer)\\b");
  private static final Pattern OUTGOING=Pattern.compile(
    "(?i)\\b(debited|sent|paid\\s+to|transfer\\s+to|transferred\\s+to|purchase|withdrawal|withdrawn|failed|unsuccessful|pending|dibayar|ditolak|keluar|gagal|refund|reversed|reversal|request\\s+money)\\b");

  static int uniqueIncomingCents(String title,String body){
    String merged=((title==null?"":title)+" "+(body==null?"":body)).trim();
    if(merged.length()==0||merged.length()>4096)return -1;
    if(!INCOMING.matcher(merged).find()||OUTGOING.matcher(merged).find())return -1;
    Matcher m=MONEY.matcher(merged);
    if(!m.find())return -1;
    String first=m.group(1);
    if(m.find())return -1; // Balance + payment amount / ambiguous notification.
    try{
      int cents=new BigDecimal(first.replace(",","")).movePointRight(2).intValueExact();
      return cents>0?cents:-1;
    }catch(Exception e){return -1;}
  }
}
