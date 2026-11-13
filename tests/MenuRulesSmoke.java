package my.warisan.pos;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** v3.46.7: add-menu validation and Live stock slot matching. */
public final class MenuRulesSmoke {
  private static void eq(String label,Object expected,Object actual){
    if(expected==null?actual!=null:!expected.equals(actual))
      throw new AssertionError(label+": expected "+expected+" got "+actual);
  }
  private static void throwsIt(String label,Runnable r){
    try{r.run();}catch(RuntimeException expected){return;}
    throw new AssertionError(label+": expected an exception");
  }
  private static List<Long> longs(long... v){ArrayList<Long> out=new ArrayList<>();for(long n:v)out.add(n);return out;}
  public static void main(String[] args){
    // Prices: comma decimal and "RM" prefix accepted; >2 decimals / empty refused.
    eq("7",700,MenuRules.parseMenuCents("7"));
    eq("7.50",750,MenuRules.parseMenuCents("7.50"));
    eq("7,50",750,MenuRules.parseMenuCents("7,50"));
    eq("RM 1.6",160,MenuRules.parseMenuCents("RM 1.6"));
    throwsIt("empty",()->MenuRules.parseMenuCents(" "));
    throwsIt("3 decimals",()->MenuRules.parseMenuCents("1.555"));
    throwsIt("text",()->MenuRules.parseMenuCents("abc"));

    String[] names={"Sate Ayam","Sate Daging","Sate Kambing","Nasi Impit","Extra Kuah Kacang","Laksa Utara","Kuih Siput","Air Tebu"};
    boolean[] hidden=new boolean[names.length];hidden[5]=true; // Laksa deleted
    eq("new name ok",null,MenuRules.menuNameError("Teh O Ais",names,hidden,names.length));
    if(MenuRules.menuNameError("  sate   AYAM ",names,hidden,names.length)==null)
      throw new AssertionError("duplicate active menu must be refused");
    eq("deleted menu can be re-added",null,MenuRules.menuNameError("Laksa Utara",names,hidden,names.length));
    if(MenuRules.menuNameError("   ",names,hidden,names.length)==null)throw new AssertionError("empty name");
    if(MenuRules.menuNameError("x".repeat(36),names,hidden,names.length)==null)throw new AssertionError("long name");
    if(MenuRules.menuNameError("Menu 31",names,hidden,30)==null)throw new AssertionError("30-slot Rules limit");

    // Live stock: new menu gets a ZERO slot; existing balances never change.
    eq("pad",longs(5,0,9,0),MenuRules.padStockSlots(longs(5,0,9),4));
    eq("no shrink",longs(5,0,9),MenuRules.padStockSlots(longs(5,0,9),2));
    throwsIt("31 slots",()->MenuRules.padStockSlots(longs(1),31));

    // Orders: legacy shorter qty padded; trailing zero for new menu dropped.
    eq("pad order",longs(1,0,0),MenuRules.fitOrderSlots(longs(1),3));
    eq("drop zero",longs(1,2,0),MenuRules.fitOrderSlots(longs(1,2,0,0),3));
    throwsIt("new menu without cloud slot",()->MenuRules.fitOrderSlots(longs(1,2,0,4),3));
    System.out.println("PASS: add-menu validation, duplicate names, price parsing and Live stock slots for new menus");
  }
}
