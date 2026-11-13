package my.warisan.pos;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pure (Android-free) rules for adding menus and matching Live stock slots.
 * Existing stock balances and sales records are never changed here.
 */
final class MenuRules {
  private MenuRules(){}
  /** Firestore Rules accept at most 30 Live stock slots. */
  static final int MAX_MENU_SLOTS=30;

  /** "7", "7.5", "7,50", "RM 7.00" -> cents. Throws for invalid / >2 decimals. */
  static int parseMenuCents(String raw){
    String value=raw==null?"":raw.trim().replace(',','.')
      .replaceFirst("(?i)^rm","").trim();
    if(value.isEmpty())throw new NumberFormatException("empty");
    return new java.math.BigDecimal(value).movePointRight(2).intValueExact();
  }
  static String normalName(String title){
    return title==null?"":title.trim().replaceAll("\\s+"," ");
  }
  /** Null when the name can be added; otherwise a Malay error message. */
  static String menuNameError(String title,String[] names,boolean[] hidden,int slots){
    String wanted=normalName(title);
    if(wanted.isEmpty())return "Nama menu kosong.";
    if(wanted.length()>35)return "Nama menu maksimum 35 huruf.";
    if(slots>=MAX_MENU_SLOTS)
      return "Slot menu sudah penuh (maksimum "+MAX_MENU_SLOTS+", termasuk menu yang telah dipadam).";
    String key=wanted.toLowerCase(Locale.ROOT);
    for(int i=0;i<names.length;i++){
      if(hidden!=null&&i<hidden.length&&hidden[i])continue; // deleted menus may be re-added
      if(names[i]!=null&&normalName(names[i]).toLowerCase(Locale.ROOT).equals(key))
        return "Menu \""+names[i]+"\" sudah ada.";
    }
    return null;
  }
  /** Append ZERO slots up to {@code slots}; existing balances stay unchanged. */
  static ArrayList<Long> padStockSlots(List<Long> values,int slots){
    if(slots>MAX_MENU_SLOTS)throw new IllegalStateException("Maksimum "+MAX_MENU_SLOTS+" slot menu Live");
    ArrayList<Long> out=new ArrayList<>(values);
    while(out.size()<slots)out.add(0L);
    return out;
  }
  /**
   * Order quantities must have exactly {@code cloudSlots} entries (Rules).
   * Fewer: pad with zero (legacy hidden slots). More: drop trailing ZERO
   * entries for a new menu that has no Cloud slot yet; a non-zero quantity
   * for a missing slot is refused.
   */
  static ArrayList<Long> fitOrderSlots(List<Long> quantities,int cloudSlots){
    ArrayList<Long> out=new ArrayList<>(quantities);
    while(out.size()>cloudSlots){
      Long last=out.get(out.size()-1);
      if(last!=null&&last.longValue()!=0L)
        throw new IllegalStateException("Menu baru belum ada stok Cloud. Minta Owner restock menu ini dahulu.");
      out.remove(out.size()-1);
    }
    while(out.size()<cloudSlots)out.add(0L);
    return out;
  }
}
