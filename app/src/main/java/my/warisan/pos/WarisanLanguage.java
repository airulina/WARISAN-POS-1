package my.warisan.pos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WARISAN POS Malaysia/English display translations.
 *
 * The stored POS values (payment status, Firestore fields, product prices,
 * branch IDs, printer addresses, receipts and JSON keys) are NEVER rewritten.
 * Translate only as values are rendered in UI, dialogs or printed reports.
 *
 * Proper nouns and technical standards, including DuitNow, QR, Firebase,
 * Bluetooth and WARISAN POS, are deliberately preserved.
 */
final class WarisanLanguage {
  private static final Locale MALAYSIA = new Locale("ms", "MY");
  private static final Map<String,String> EN = new HashMap<>();
  private static final Map<String,String> MS = new HashMap<>();
  private static Pattern toEnglish, toMalay;

  private WarisanLanguage(){}

  private static void pair(String malay, String english){
    EN.put(malay.toLowerCase(Locale.ROOT), english);
    MS.put(english.toLowerCase(Locale.ROOT), malay);
  }

  private static void alias(String original, String malay, String english){
    // Text may have originated as a mixed-language literal. Force each
    // display choice to use a single target language.
    EN.put(original.toLowerCase(Locale.ROOT), english);
    MS.put(original.toLowerCase(Locale.ROOT), malay);
  }

  private static Pattern matcher(Map<String,String> values){
    List<String> keys = new ArrayList<>(values.keySet());
    Collections.sort(keys, new Comparator<String>(){
      @Override public int compare(String a,String b){
        return Integer.compare(b.length(),a.length());
      }
    });
    StringBuilder regex=new StringBuilder();
    for(String key:keys){
      if(regex.length()>0)regex.append('|');
      regex.append(Pattern.quote(key));
    }
    return Pattern.compile("(?<![\\p{L}\\p{N}_])(?:"
      +regex+")(?![\\p{L}\\p{N}_])",
      Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE);
  }

  static String translate(String value, boolean english){
    if(value==null||value.isEmpty())return value;
    // Leave technical keys, URI/URLs and email addresses intact; they are
    // account data, not UI copy.
    if(value.startsWith("https://")||value.startsWith("http://")||
       value.startsWith("content://"))return value;
    Pattern pattern=english?toEnglish:toMalay;
    Map<String,String> dictionary=english?EN:MS;
    Matcher matcher=pattern.matcher(value);
    StringBuffer result=new StringBuffer();
    while(matcher.find()){
      String source=matcher.group();
      String destination=dictionary.get(source.toLowerCase(Locale.ROOT));
      if(destination==null)destination=source;
      // ALL-CAPS UI headings stay upper case. Sentence case stays readable.
      if(hasLetters(source)&&source.equals(source.toUpperCase(MALAYSIA))){
        destination=destination.toUpperCase(english?Locale.ENGLISH:MALAYSIA);
      }else if(!source.isEmpty()&&Character.isUpperCase(source.charAt(0))&&
          !destination.isEmpty()){
        destination=Character.toUpperCase(destination.charAt(0))
          +destination.substring(1);
      }
      matcher.appendReplacement(result,Matcher.quoteReplacement(destination));
    }
    matcher.appendTail(result);
    return result.toString();
  }

  private static boolean hasLetters(String s){
    for(int i=0;i<s.length();i++)if(Character.isLetter(s.charAt(i)))return true;
    return false;
  }

  static {
    pair("Bahasa Melayu (Malaysia)", "Malay (Malaysia)");
    pair("Bahasa Inggeris", "English");
    pair("Pilih bahasa aplikasi", "Choose application language");
    pair("Bahasa ditukar kepada Bahasa Melayu (Malaysia).", "Language changed to Malay (Malaysia).");
    pair("Bahasa ditukar kepada Bahasa Inggeris.", "Language changed to English.");
    pair("KIOS WARISAN · SISTEM JUALAN", "WARISAN KIOSK · SALES SYSTEM");
    pair("Sate Ayam", "Chicken Satay");
    pair("Sate Daging", "Beef Satay");
    pair("Sate Kambing", "Lamb Satay");
    pair("Nasi Impit", "Compressed Rice Cake");
    pair("Extra Kuah Kacang", "Extra Peanut Sauce");
    pair("Laksa Utara", "Northern Laksa");
    pair("Pilih menu", "Select items");
    pair("Tekan + untuk tambah pesanan", "Tap + to add items");
    pair("Pilih menu di atas", "Select an item above");
    pair("Belum ada pesanan", "No items selected");
    pair("Jumlah belian", "Purchase total");
    pair("Jumlah perlu dibayar", "Amount due");
    pair("Baki stok", "Remaining stock");
    pair("Stok tersedia", "Available stock");
    pair("Stok tanpa had", "Unlimited stock");
    pair("Stok habis", "Out of stock");
    pair("Baki stok langsung", "Live stock balance");
    pair("Tambah stok", "Add stock");
    pair("Kuantiti", "Quantity");
    pair("Kuantiti terjual", "Quantity sold");
    pair("Stok rosak", "Damaged stock");
    pair("Rekod stok rosak", "Damaged stock records");
    pair("Jumlah jualan", "Total sales");
    pair("Jualan hari ini", "Today's sales");
    pair("Jualan bulan ini", "This month's sales");
    pair("Jualan mengikut tarikh", "Sales by date");
    pair("Rekod jualan mengikut tarikh", "Sales records by date");
    pair("Jualan mengikut produk", "Sales by product");
    pair("Pecahan jualan", "Sales breakdown");
    pair("Jumlah jualan lama + langsung", "Total historic and live sales");
    pair("Jumlah transaksi", "Total transactions");
    pair("Untung bersih", "Net profit");
    pair("Untung perniagaan", "Business profit");
    pair("Untung sesi", "Session profit");
    pair("Untung tempatan", "Local profit");
    pair("Untung POS tempatan", "Local POS profit");
    pair("Untung bersih POS tempatan", "Local POS net profit");
    pair("Untung bersih jualan", "Net sales profit");
    pair("Kos produk", "Product cost");
    pair("Belanja operasi", "Operating expenses");
    pair("Kos operasi", "Operating costs");
    pair("Kos dan untung", "Costs and profits");
    pair("Kos produk dan operasi", "Product and operating costs");
    pair("Kos pembelian", "Purchase cost");
    pair("Kos tempatan", "Local costs");
    pair("Baki pembekal perlu bayar", "Outstanding supplier balance");
    pair("Bayaran pembekal", "Supplier payment");
    pair("Sejarah bayaran pembekal", "Supplier payment history");
    pair("Rekod bayaran pembekal", "Supplier payment records");
    pair("Resit bayaran pembekal", "Supplier payment receipt");
    pair("Jumlah asal", "Original amount");
    pair("Jumlah tolakan", "Deduction amount");
    pair("Jumlah dibayar", "Amount paid");
    pair("Telah dibayar", "Paid");
    pair("Belum dibayar", "Unpaid");
    pair("Belum bayar", "Pending payment");
    pair("Sudah bayar", "Paid");
    pair("Bayaran diterima", "Payment received");
    pair("Baki belum dibayar", "Outstanding balance");
    pair("Duit masuk", "Money in");
    pair("Duit keluar", "Money out");
    pair("Pengeluaran peribadi", "Personal withdrawals");
    pair("Baki tunai", "Cash balance");
    pair("Baki sesi POS", "POS session balance");
    pair("Sewa", "Rent");
    pair("Minyak kereta", "Vehicle fuel");
    pair("Laporan keseluruhan", "Overall report");
    pair("Laporan jualan", "Sales report");
    pair("Laporan harian", "Daily report");
    pair("Laporan bulanan", "Monthly report");
    pair("Laporan cawangan", "Branch report");
    pair("Laporan penutupan harian", "Daily closing report");
    pair("Laporan langsung", "Live report");
    pair("Graf jualan", "Sales chart");
    pair("Graf jualan harian", "Daily sales chart");
    pair("Pecahan mengikut cawangan", "Breakdown by branch");
    pair("Pecahan stok", "Stock breakdown");
    pair("Item terjual", "Items sold");
    pair("Kaedah bayaran", "Payment method");
    pair("Tidak dikelaskan", "Unclassified");
    pair("Belum dikenal pasti", "Unidentified");
    pair("Rekod tempatan", "Local records");
    pair("Rekod lama", "Historic records");
    pair("Semua cawangan", "All branches");
    pair("Cawangan aktif", "Active branch");
    pair("Cawangan pekerja", "Staff branch");
    pair("Cawangan Utama", "Main Branch");
    pair("Cawangan asal", "Original branch");
    pair("Cawangan destinasi", "Destination branch");
    pair("Senarai cawangan", "Branch list");
    pair("Tukar cawangan", "Switch branch");
    pair("Tambah cawangan", "Add branch");
    pair("Padam cawangan", "Delete branch");
    pair("Arkib cawangan", "Archive branch");
    pair("Pulihkan cawangan", "Restore branch");
    pair("Cawangan diarkibkan", "Archived branch");
    pair("Cawangan mempunyai rekod", "Branch contains records");
    pair("Padam kekal", "Delete permanently");
    pair("Cawangan kosong sahaja", "Empty branches only");
    pair("Urus cawangan", "Manage branches");
    pair("Status cawangan", "Branch status");
    pair("Buka semula cawangan", "Reopen branch");
    pair("Daftar pekerja", "Register employee");
    pair("Urus pekerja", "Manage employees");
    pair("Tukar cawangan pekerja", "Change employee branch");
    pair("Baiki akses pekerja", "Repair employee access");
    pair("Akses pekerja", "Employee access");
    pair("Pekerja aktif", "Active employee");
    pair("Pekerja disekat", "Blocked employee");
    pair("Sekat pekerja", "Block employee");
    pair("Buang pekerja", "Remove employee");
    pair("Buang akses", "Revoke access");
    pair("Tetapkan semula peranti", "Reset device");
    pair("Reset telefon pekerja", "Reset employee phone");
    pair("Akaun pekerja", "Employee account");
    pair("Pengesahan pekerja", "Employee verification");
    pair("Pendaftaran pekerja", "Employee registration");
    pair("Buka sesi", "Open session");
    pair("Tutup sesi", "Close session");
    pair("Buka sesi POS", "Open POS session");
    pair("Tutup sesi POS", "Close POS session");
    pair("Baki pembukaan", "Opening balance");
    pair("Resit penutupan POS", "POS closing receipt");
    pair("Pratonton penutupan", "Closing preview");
    pair("Sesi telah ditutup", "Session closed");
    pair("Sesi sedang dibuka", "Session open");
    pair("Sesi belum dibuka", "Session not open");
    pair("Sesi telah dibuka", "Session opened");
    pair("Pesanan", "Order");
    pair("Pesanan belum bayar", "Unpaid orders");
    pair("Pesanan masuk", "Incoming orders");
    pair("Pesanan tempatan", "Local orders");
    pair("Pesanan daripada cawangan lain", "Orders from other branches");
    pair("Sahkan pesanan", "Confirm order");
    pair("Batalkan pesanan", "Cancel order");
    pair("Nombor resit", "Receipt number");
    pair("Nombor pesanan", "Order number");
    pair("Resit pelanggan", "Customer receipt");
    pair("Resit pembekal", "Supplier receipt");
    pair("Tukar nama", "Change name");
    pair("Kemas kini logo", "Update logo");
    pair("Maklumat kedai", "Shop details");
    pair("Jenama dan kedai", "Brand and shop");
    pair("Nama pada resit", "Name on receipt");
    pair("Cetak resit", "Print receipt");
    pair("Pratonton resit", "Receipt preview");
    pair("Cetak laporan", "Print report");
    pair("Cetak semula", "Reprint");
    pair("Sejarah resit", "Receipt history");
    pair("Pilih pencetak", "Choose printer");
    pair("Pencetak dan resit", "Printer and receipts");
    pair("Uji cetakan", "Test print");
    pair("Uji resit", "Test receipt");
    pair("Pencetak Bluetooth", "Bluetooth printer");
    pair("Tetapan Bank", "Bank settings");
    pair("Tetapan Pembayaran", "Payment settings");
    pair("Kemas kini QR", "Update QR");
    pair("Muat naik QR", "Upload QR");
    pair("Tukar QR", "Change QR");
    pair("Kongsi QR", "Share QR");
    pair("QR rasmi", "Official QR");
    pair("Bayaran tunai", "Cash payment");
    pair("Bayaran QR", "QR payment");
    pair("Pengesahan manual", "Manual confirmation");
    pair("Pengesahan automatik", "Automatic confirmation");
    pair("Bayaran balik", "Refund");
    pair("Kelulusan bayaran balik", "Refund approval");
    pair("Permohonan bayaran balik", "Refund request");
    pair("Bayaran balik menunggu", "Refund pending");
    pair("Tetapan", "Settings");
    pair("Akaun dan sandaran", "Accounts and backup");
    pair("Salinan sandaran awan", "Cloud backup");
    pair("Sandaran telefon", "Phone backup");
    pair("Pulih daripada awan", "Restore from cloud");
    pair("Pulih daripada telefon", "Restore from phone");
    pair("Pulihkan salinan sebelum kemas kini", "Restore pre-update backup");
    pair("Log keluar", "Log out");
    pair("Log masuk", "Log in");
    pair("Kata laluan", "Password");
    pair("E-mel", "Email");
    pair("Alamat e-mel", "Email address");
    pair("Alamat", "Address");
    pair("Kedai", "Shop");
    pair("Syarikat", "Company");
    pair("Lesen", "License");
    pair("Maklumat", "Information");
    pair("Bahasa", "Language");
    pair("Pilih bahasa", "Select language");
    pair("Simpan", "Save");
    pair("Kemaskini", "Update");
    pair("Kemas kini", "Update");
    pair("Pilih", "Select");
    pair("Batal", "Cancel");
    pair("Tutup", "Close");
    pair("Buka", "Open");
    pair("Sahkan", "Confirm");
    pair("Teruskan", "Continue");
    pair("Kembali", "Back");
    pair("Lagi", "More");
    pair("Lihat", "View");
    pair("Tambah", "Add");
    pair("Tolak", "Deduct");
    pair("Edar", "Distribute");
    pair("Pulang", "Return");
    pair("Rosak", "Damaged");
    pair("Terjual", "Sold");
    pair("Baki", "Balance");
    pair("Jumlah", "Total");
    pair("Bilangan", "Count");
    pair("Awal", "Opening");
    pair("Har i ini", "Today");
    pair("Hari ini", "Today");
    pair("Harian", "Daily");
    pair("Mingguan", "Weekly");
    pair("Bulanan", "Monthly");
    pair("Tarikh", "Date");
    pair("Masa", "Time");
    pair("Bulan", "Month");
    pair("Tahun", "Year");
    pair("Semua", "All");
    pair("Semak", "Check");
    pair("Selesai", "Done");
    pair("Berjaya", "Successful");
    pair("Gagal", "Failed");
    pair("Ralat", "Error");
    pair("Tiada", "None");
    pair("Belum ada", "Not yet available");
    pair("Pengesahan", "Confirmation");
    pair("Kebenaran", "Permission");
    pair("Dilarang", "Not allowed");
    pair("Akses ditolak", "Access denied");
    pair("Pekerja", "Employee");
    pair("Pemilik", "Owner");
    pair("Cawangan", "Branch");
    pair("Sejarah", "History");
    pair("Laporan", "Report");
    pair("Pencetak", "Printer");
    pair("Stok", "Stock");
    pair("Duit", "Finances");
    pair("Tunai", "Cash");
    pair("Kos", "Cost");
    pair("Untung", "Profit");
    pair("Jualan", "Sales");
    pair("Harga", "Price");
    pair("Pembekal", "Supplier");
    pair("Peribadi", "Personal");
    pair("Menu", "Menu");
    pair("Akaun", "Account");
    pair("Baki pulangan", "Change due");
    pair("Baki tunai POS", "POS cash balance");
    pair("Pelanggan", "Customer");
    pair("Belian", "Purchase");
    pair("Beli", "Buy");
    pair("Sah", "Valid");
    pair("Aktif", "Active");
    pair("Tidak aktif", "Inactive");
    pair("Disekat", "Blocked");
    pair("Dibatalkan", "Cancelled");
    pair("Dihapus", "Deleted");
    pair("Dibuang", "Removed");
    pair("Diarkibkan", "Archived");
    pair("Dibuka", "Open");
    pair("Ditutup", "Closed");
    pair("Cuti", "Day off");
    pair("Hari cuti", "Day off");
    pair("Habis", "Out of stock");
    pair("Keterangan", "Description");
    pair("Nota", "Note");
    pair("Catatan", "Remark");
    pair("Peringatan", "Notice");
    pair("Rujukan", "Reference");
    pair("Pemantauan", "Monitoring");
    pair("Sambungan", "Connection");
    pair("Dalam talian", "Online");
    pair("Luar talian", "Offline");
    pair("Masa nyata", "Live");
    pair("Awan", "Cloud");
    pair("Segerak", "Sync");
    pair("Muat naik", "Upload");
    pair("Muat turun", "Download");
    pair("Eksport", "Export");
    pair("Import", "Import");
    pair("Pratonton", "Preview");
    pair("Uji", "Test");
    pair("Kongsi", "Share");
    pair("Cetak", "Print");
    pair("Cucuk", "Skewers");
    pair("Unit", "Units");
    pair("Mangkuk", "Bowls");
    pair("Pek", "Packs");
    pair("Ringgit", "Ringgit");
    pair("Senarai", "List");
    pair("Tempoh", "Period");
    pair("Sahaja", "Only");
    pair("Pilihan", "Options");
    pair("Terakhir", "Last");
    pair("Baru", "New");
    pair("Dahulu", "Previously");
    pair("Lama", "Old");
    pair("Semasa", "Current");
    pair("Terkini", "Latest");
    pair("Seterusnya", "Next");
    pair("Diterima", "Received");
    pair("Dihantar", "Sent");
    pair("Dibayar", "Paid");
    pair("Kekal", "Retained");
    pair("Selamat", "Safe");
    pair("Perlu", "Required");
    alias("Branch Utama", "Cawangan Utama", "Main Branch");
    alias("Branch", "Cawangan", "Branch");
    alias("Order", "Pesanan", "Order");
    alias("Orders", "Pesanan", "Orders");
    alias("Owner", "Pemilik", "Owner");
    alias("Boss", "Pemilik", "Owner");
    alias("Master", "Pemilik", "Owner");
    alias("Staff", "Pekerja", "Staff");
    alias("Employee", "Pekerja", "Employee");
    alias("Opening / Closing", "Buka / Tutup Sesi", "Opening / Closing");
    alias("Opening", "Buka Sesi", "Opening");
    alias("Closing", "Tutup Sesi", "Closing");
    alias("OFF DAY", "HARI CUTI", "DAY OFF");
    alias("STOCK OUT", "STOK HABIS", "OUT OF STOCK");
    alias("Restock", "Tambah Stok", "Restock");
    alias("Refund", "Bayaran Balik", "Refund");
    alias("Login", "Log Masuk", "Log In");
    alias("Log In", "Log Masuk", "Log In");
    alias("Sign Up", "Daftar Akaun", "Sign Up");
    alias("Password", "Kata Laluan", "Password");
    alias("History", "Sejarah", "History");
    alias("Report", "Laporan", "Report");
    alias("Reports", "Laporan", "Reports");
    alias("Overall", "Keseluruhan", "Overall");
    alias("Total Sale", "Jumlah Jualan", "Total Sales");
    alias("Total Sales", "Jumlah Jualan", "Total Sales");
    alias("Cashflow", "Aliran Tunai", "Cash Flow");
    alias("Cash", "Tunai", "Cash");
    alias("Stock", "Stok", "Stock");
    alias("Settings", "Tetapan", "Settings");
    alias("Setting", "Tetapan", "Settings");
    alias("Printer", "Pencetak", "Printer");
    alias("Receipt", "Resit", "Receipt");
    alias("Print", "Cetak", "Print");
    alias("Preview", "Pratonton", "Preview");
    alias("Test Print", "Uji Cetak", "Test Print");
    alias("Confirm Order", "Sahkan Pesanan", "Confirm Order");
    alias("Confirm", "Sahkan", "Confirm");
    alias("Cancel", "Batal", "Cancel");
    alias("SAVE", "SIMPAN", "SAVE");
    alias("Payment", "Pembayaran", "Payment");
    alias("Merchant", "Peniaga", "Merchant");
    alias("Cloud", "Awan", "Cloud");
    alias("Live", "Masa Nyata", "Live");
    alias("Sync", "Segerak", "Sync");
    alias("Dashboard", "Papan Pemuka", "Dashboard");
    alias("Upload", "Muat Naik", "Upload");
    alias("Update", "Kemas Kini", "Update");
    alias("Edit", "Ubah", "Edit");
    alias("Delete", "Padam", "Delete");
    alias("Backup", "Sandaran", "Backup");
    alias("Restore", "Pulihkan", "Restore");
    alias("Download", "Muat Turun", "Download");
    alias("Export", "Eksport", "Export");
    alias("Import", "Import", "Import");
    alias("Share", "Kongsi", "Share");
    alias("Refresh", "Segar Semula", "Refresh");
    alias("Email", "E-mel", "Email");
    alias("Bank Setting", "Tetapan Bank", "Bank Settings");
    alias("Api & Merchant", "API dan Peniaga", "API & Merchant");
    alias("Active", "Aktif", "Active");
    alias("Blocked", "Disekat", "Blocked");
    alias("Missing", "Tiada", "Missing");
    alias("Success", "Berjaya", "Success");
    alias("Failed", "Gagal", "Failed");
    alias("Pending", "Belum Selesai", "Pending");
    alias("Pending Order", "Pesanan Belum Selesai", "Pending Order");
    alias("Total", "Jumlah", "Total");
    alias("Price", "Harga", "Price");
    alias("Quantity", "Kuantiti", "Quantity");
    alias("Amount", "Jumlah", "Amount");
    alias("Store", "Kedai", "Store");
    alias("Shop", "Kedai", "Shop");
    alias("Date", "Tarikh", "Date");
    alias("Time", "Masa", "Time");
    alias("Today", "Hari Ini", "Today");
    alias("Daily", "Harian", "Daily");
    alias("Monthly", "Bulanan", "Monthly");
    alias("Weekly", "Mingguan", "Weekly");
    alias("Select", "Pilih", "Select");
    alias("Add", "Tambah", "Add");
    alias("Back", "Kembali", "Back");
    alias("Close", "Tutup", "Close");
    alias("Open", "Buka", "Open");
    alias("SAVE QR", "SIMPAN QR", "SAVE QR");
    alias("Test Receipt", "Uji Resit", "Test Receipt");
    alias("Status", "Status", "Status");
    alias("API", "API", "API");
    alias("POS", "POS", "POS");
    alias("DuitNow", "DuitNow", "DuitNow");
    alias("Firebase", "Firebase", "Firebase");
    alias("Bluetooth", "Bluetooth", "Bluetooth");
    alias("QR", "QR", "QR");
    alias("A4", "A4", "A4");
    alias("Facebook", "Facebook", "Facebook");
    alias("QR / DuitNow", "QR / DuitNow", "QR / DuitNow");
    alias("WARISAN POS", "WARISAN POS", "WARISAN POS");
    alias("KIOS WARISAN", "KIOS WARISAN", "WARISAN KIOSK");
    alias("Urus cawangan, status operasi dan pekerja.", "Urus cawangan, status operasi dan pekerja.", "Manage branches, operating status and employees.");
    alias("Keseluruhan jualan. Tekan TOTAL SALE untuk pilih branch.", "Keseluruhan jualan. Tekan TOTAL SALE untuk pilih branch.", "Overall sales. Tap TOTAL SALES to select a branch.");
    alias("Sesi Opening/Closing untuk branch Owner yang sedang aktif. Tidak mengubah rekod branch lain.", "Sesi Opening/Closing untuk branch Owner yang sedang aktif. Tidak mengubah rekod branch lain.", "Opening and closing sessions for the Owner's current branch. Other branches are unaffected.");
    alias("Tekan + untuk tambah pesanan", "Tekan + untuk tambah pesanan", "Tap + to add items");
    alias("Akses hanya dibuka apabila branch berstatus BUKA.", "Akses hanya dibuka apabila branch berstatus BUKA.", "Access is only available when the branch is open.");
    alias("Owner sahaja · Pilih branch untuk edit atau tambah stok.", "Owner sahaja · Pilih branch untuk edit atau tambah stok.", "Owner only · Select a branch to edit or add stock.");
    alias("Semua perubahan stok dibuat oleh Owner.", "Semua perubahan stok dibuat oleh Owner.", "All inventory changes must be made by the Owner.");
    alias("Pekerja tidak boleh restock, edit baki, rekod rosak atau bayar pembekal.", "Pekerja tidak boleh restock, edit baki, rekod rosak atau bayar pembekal.", "Employees cannot restock, change balances, record damage or pay suppliers.");
    alias("Jualan Live dari telefon pekerja dan kos branch lain belum semuanya termasuk.", "Jualan Live dari telefon pekerja dan kos branch lain belum semuanya termasuk.", "Live sales from employee devices and costs for other branches are not yet fully included.");
    alias("Jangan samakan dengan untung semua branch.", "Jangan samakan dengan untung semua branch.", "Do not treat this as net profit across all branches.");
    alias("Tiada order tempatan belum bayar.", "Tiada order tempatan belum bayar.", "There are no unpaid local orders.");
    alias("Order tempatan dan order Cloud branch lain, lengkap dengan nombor resit serta asal branch.", "Order tempatan dan order Cloud branch lain, lengkap dengan nombor resit serta asal branch.", "Local orders and Cloud orders from other branches, including receipt numbers and originating branches.");
    alias("Pesanan yang sudah disahkan tetapi belum dibayar.", "Pesanan yang sudah disahkan tetapi belum dibayar.", "Confirmed orders awaiting payment.");
    alias("Sesi pekerja · Opening / Closing dan jualan hari ini.", "Sesi pekerja · Opening / Closing dan jualan hari ini.", "Employee session · Opening / Closing and today's sales.");
    alias("Sesi Owner · Opening / Closing dan jualan Branch aktif.", "Sesi Owner · Opening / Closing dan jualan Branch aktif.", "Owner session · Opening / Closing and current branch sales.");
    alias("Semak bayaran diterima sebelum sahkan.", "Semak bayaran diterima sebelum sahkan.", "Verify payment is received before confirming.");
    alias("Branch dengan jualan, stok atau pekerja hanya boleh diarkibkan.", "Branch dengan jualan, stok atau pekerja hanya boleh diarkibkan.", "Branches with sales, stock or employees may only be archived.");
    alias("Rekod lama kekal dalam laporan semua branch.", "Rekod lama kekal dalam laporan semua branch.", "Historic records remain in all-branch reports.");
    alias("Tiada transaksi untuk tempoh ini.", "Tiada transaksi untuk tempoh ini.", "No transactions for this period.");
    alias("Tiada rekod stok.", "Tiada rekod stok.", "No stock records.");
    alias("Jualan, stok dan resit lama tidak dipadam.", "Jualan, stok dan resit lama tidak dipadam.", "Historic sales, stock and receipts are not deleted.");
    alias("Sila sambung internet dan cuba semula.", "Sila sambung internet dan cuba semula.", "Connect to the internet and try again.");
    alias("Buka halaman tetapan.", "Buka halaman tetapan.", "Open the settings page.");
    alias("Pengesahan bayaran dibuat secara manual.", "Pengesahan bayaran dibuat secara manual.", "Payments must be confirmed manually.");
    alias("Pengesahan manual diperlukan.", "Pengesahan manual diperlukan.", "Manual confirmation required.");

    toEnglish=matcher(EN);
    toMalay=matcher(MS);
  }
}
