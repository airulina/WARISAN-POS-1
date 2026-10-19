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
      "EMPLOYEE BRANCH: MAIN BRANCH");
    assertEqual("BM branch message","Order tempatan dan order Cloud branch lain, lengkap dengan nombor resit serta asal branch.",false,
      "Pesanan tempatan dan pesanan awan daripada cawangan lain, lengkap dengan nombor resit serta cawangan asal.");
    assertEqual("EN branch message","Order tempatan dan order Cloud branch lain, lengkap dengan nombor resit serta asal branch.",true,
      "Local orders and Cloud orders from other branches, including receipt numbers and originating branches.");
    assertEqual("BM branch label","PEMBEKAL · BRANCH AKTIF",false,"PEMBEKAL · CAWANGAN AKTIF");
    assertEqual("EN branch label","PEMBEKAL · BRANCH AKTIF",true,"SUPPLIERS · ACTIVE BRANCH");
    assertEqual("BM sentence case","Pilih menu",false,"Pilih menu");
    assertEqual("EN sentence case","Pilih menu",true,"Select items");
    assertEqual("proper name","WARISAN POS",false,"WARISAN POS");
    assertEqual("uri","https://test.example/order/abc",false,"https://test.example/order/abc");
    assertEqual("dynamic RM","RM 1,300.50",true,"RM 1,300.50");
    System.out.println("PASS: Malay (Malaysia) and English labels, dialogs and receipts");
  }
}
