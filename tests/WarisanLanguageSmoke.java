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
    assertEqual("EN finance","DUIT · OVERALL",true,"FINANCES · OVERVIEW");
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
    assertEqual("BM receipt thanks","TERIMA KASIH!",false,"TERIMA KASIH!");
    assertEqual("EN receipt thanks","TERIMA KASIH!",true,"THANK YOU!");
    assertEqual("EN receipt footer","Sila datang lagi.",true,"Please visit us again.");
    assertEqual("BM customer history","HISTORY CUSTOMER",false,"SEJARAH PELANGGAN");
    assertEqual("EN closing","RESIT PENUTUPAN POS",true,"POS CLOSING RECEIPT");
    // Screenshot regression: never translate one word at a time.
    assertEqual("Stock screenshot title BM","Stok Mengikut Branch",false,
      "Stok Mengikut Cawangan");
    assertEqual("Stock screenshot title EN","Stok Mengikut Branch",true,
      "Stock by Branch");
    assertEqual("Stock screenshot subtitle BM",
      "Owner sahaja · Pilih branch untuk edit atau tambah stok.",false,
      "Pemilik sahaja · Pilih cawangan untuk mengubah atau menambah stok.");
    assertEqual("Stock screenshot subtitle EN",
      "Owner sahaja · Pilih branch untuk edit atau tambah stok.",true,
      "Owner only · Select a branch to edit or add stock.");
    assertEqual("Stock screenshot refresh BM","↻  Refresh Senarai Branch",false,
      "↻  Segar Semula Senarai Cawangan");
    assertEqual("Stock screenshot refresh EN","↻  Refresh Senarai Branch",true,
      "↻  Refresh Branch List");
    assertEqual("Stock screenshot count BM","JUMLAH BRANCH: 2",false,
      "JUMLAH CAWANGAN: 2");
    assertEqual("Stock screenshot count EN","JUMLAH BRANCH: 2",true,
      "TOTAL BRANCHES: 2");
    assertEqual("Stock screenshot active BM","Branch Utama · Branch aktif",false,
      "Cawangan Utama · Cawangan aktif");
    assertEqual("Stock screenshot active EN","Branch Utama · Branch aktif",true,
      "Main Branch · Active branch");
    assertEqual("Stock screenshot hint BM",
      "Masuk branch dahulu sebelum ubah stok. Branch lain tidak akan terjejas.",false,
      "Pilih cawangan dahulu sebelum mengubah stok. Cawangan lain tidak akan terjejas.");
    assertEqual("Stock screenshot hint EN",
      "Masuk branch dahulu sebelum ubah stok. Branch lain tidak akan terjejas.",true,
      "Select a branch before changing stock. Other branches will not be affected.");
    assertEqual("No partial word salad EN",
      "Masuk branch previously before change stock.",true,
      "Masuk branch previously before change stock.");
    assertEqual("BM cancelled hidden heading","PESANAN CAWANGAN LAIN · AWAN",false,
      "PESANAN CAWANGAN LAIN · AWAN");
    assertEqual("EN cancelled hidden heading","PESANAN CAWANGAN LAIN · AWAN",true,
      "ORDERS FROM OTHER BRANCHES · CLOUD");
    assertEqual("BM refunded status","DIBAYAR BALIK",false,"DIBAYAR BALIK");
    assertEqual("EN refunded status","DIBAYAR BALIK",true,"REFUNDED");
    assertEqual("EN paid status","SUDAH DIBAYAR",true,"PAID");
    assertEqual("EN cancelled message","Tiada pesanan aktif, berbayar atau bayaran balik daripada cawangan lain.",true,
      "There are no active, paid, or refunded orders from other branches.");
    assertEqual("Proper branch name", "Warisan Jalan Kebun",true,
      "Warisan Jalan Kebun");
    assertEqual("Email should remain unchanged",
      "cashier@example.com",true,"cashier@example.com");
    assertEqual("proper name","WARISAN POS",false,"WARISAN POS");
    assertEqual("uri","https://test.example/order/abc",false,"https://test.example/order/abc");
    assertEqual("dynamic RM","RM 1,300.50",true,"RM 1,300.50");
    System.out.println("PASS: Malay (Malaysia) and English labels, dialogs and receipts");
  }
}
