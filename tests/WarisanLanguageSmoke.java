package my.warisan.pos;

/** Stand-alone assertions: can run with plain JDK; no Android/Firebase required. */
public final class WarisanLanguageSmoke {
  private static void assertEqual(String target,String source,boolean english,String expected){
    String actual=WarisanLanguage.translate(source,english);
    if(!expected.equals(actual)){
      throw new AssertionError(target+" | "+source+" -> ["+actual+"] expected ["+expected+"]");
    }
  }
  public static void main(String[] args){
    assertEqual("BM main branch","BRANCH UTAMA",false,"CAWANGAN UTAMA");
    assertEqual("EN main branch","BRANCH UTAMA",true,"MAIN BRANCH");
    assertEqual("BM menu","ORDER · SEMUA BRANCH",false,"PESANAN · SEMUA CAWANGAN");
    assertEqual("BM opening","OPENING / CLOSING",false,"BUKA / TUTUP SESI");
    assertEqual("BM heading","UNTUNG BERSIH POS TEMPATAN",false,"UNTUNG BERSIH POS TEMPATAN");
    assertEqual("EN heading","UNTUNG BERSIH POS TEMPATAN",true,"LOCAL POS NET PROFIT");
    assertEqual("EN app title","KIOS WARISAN · SISTEM JUALAN",true,"WARISAN KIOSK · SALES SYSTEM");
    assertEqual("EN finance","DUIT · OVERALL",true,"FINANCES · OVERALL");
    assertEqual("BM finance","DUIT · OVERALL",false,"DUIT · KESELURUHAN");
    assertEqual("BM confirm","Confirm Order",false,"Sahkan Pesanan");
    assertEqual("EN receipt","RESIT BAYARAN PEMBEKAL",true,"SUPPLIER PAYMENT RECEIPT");
    assertEqual("BM settings","Settings",false,"Tetapan");
    assertEqual("EN paid","TELAH DIBAYAR",true,"PAID");
    assertEqual("BM identity","BRANCH PEKERJA: BRANCH UTAMA",false,
      "CAWANGAN PEKERJA: CAWANGAN UTAMA");
    assertEqual("EN identity","BRANCH PEKERJA: BRANCH UTAMA",true,
      "BRANCH EMPLOYEE: MAIN BRANCH");
    assertEqual("proper name","WARISAN POS",false,"WARISAN POS");
    assertEqual("uri","https://test.example/order/abc",false,"https://test.example/order/abc");
    assertEqual("dynamic RM","RM 1,300.50",true,"RM 1,300.50");
    System.out.println("PASS: Malay (Malaysia) and English labels, dialogs and receipts");
  }
}
