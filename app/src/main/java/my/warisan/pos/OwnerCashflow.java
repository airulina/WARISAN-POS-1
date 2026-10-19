package my.warisan.pos;

import android.content.SharedPreferences;
import android.widget.LinearLayout;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldPath;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import com.google.firebase.firestore.QuerySnapshot;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.HashSet;
import java.util.Set;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Owner-only consolidated gross sales: cloud DAILY reports per branch, with
 * local historical receipts used ONLY on dates without a cloud DAILY report.
 *
 * Never add a local sale to a cloud sale on the same date: Owner's phone may
 * have mirrored that cloud order, which would double count revenue.
 * Older device-only receipts on OTHER phones cannot be inferred here.
 */
final class OwnerCashflow {
  private final MainActivity host;
  private final FirebaseFirestore db;
  private final BranchAccounts accounts;
  private String subscriptionKey = "", selectedBranchId = "";
  private boolean listLoaded = false;
  private String loadError = "";
  private ListenerRegistration branchListener;
  private final ArrayList<ListenerRegistration> dayListeners = new ArrayList<>();
  private final ArrayList<BranchRow> rows = new ArrayList<>();

  static final class Day {
    long sales, cash, qr, transactions, unknown;
    boolean cloud,hasLocalSupplement;
    Day(){}
    Day(long s,long c,long q,long t,boolean isCloud){
      sales=s;cash=c;qr=q;transactions=t;cloud=isCloud;
      unknown=Math.max(0,s-c-q);
    }
    void add(Day other){
      sales+=other.sales;cash+=other.cash;qr+=other.qr;
      transactions+=other.transactions;unknown+=other.unknown;
    }
  }
  static final class BranchRow {
    final String id,name,status;
    final TreeMap<String,Day> remote = new TreeMap<>();
    TreeMap<String,Day> local = new TreeMap<>();
    TreeMap<String,Day> legacyOnly = new TreeMap<>();
    boolean seen=false,cache=false;
    BranchRow(String id,String name,String status){
      this.id=id;this.name=name;this.status=status;
    }
    TreeMap<String,Day> combined(){
      // The old version replaced a whole local day with the cloud day.
      // When some pre-LIVE QR receipts shared that date, their QR revenue
      // disappeared. Keep only the pre-LIVE receipts without a liveOrderId
      // alongside cloud totals; cloud-synced orders remain counted ONCE.
      TreeMap<String,Day> merged=new TreeMap<>(local);
      for(Map.Entry<String,Day> entry:remote.entrySet()){
        Day src=entry.getValue();
        Day day=new Day(src.sales,src.cash,src.qr,src.transactions,true);
        Day legacy=legacyOnly.get(entry.getKey());
        if(legacy!=null){
          day.add(legacy);
          day.hasLocalSupplement=true;
        }
        merged.put(entry.getKey(),day);
      }
      return merged;
    }
    Day totals(){
      Day total=new Day();
      for(Day d:combined().values())total.add(d);
      return total;
    }
    boolean hasHistoricalFallback(){
      for(String day:local.keySet())if(!remote.containsKey(day))return true;
      for(String day:legacyOnly.keySet())if(remote.containsKey(day))return true;
      return false;
    }
  }
  OwnerCashflow(MainActivity activity){
    host=activity;db=activity.firestoreForStaff();accounts=activity.branchAccounts;
  }
  void stop(){
    if(branchListener!=null){branchListener.remove();branchListener=null;}
    for(ListenerRegistration l:dayListeners)l.remove();
    dayListeners.clear();rows.clear();subscriptionKey="";
    listLoaded=false;loadError="";selectedBranchId="";
  }
  String start(){
    return host.cashDailyMode?host.selectedCashDay:host.selectedMonth+"-01";
  }
  String end(){
    return host.cashDailyMode?host.selectedCashDay:host.selectedMonth+"-31";
  }
  String period(){
    return host.cashDailyMode?host.selectedCashDay:host.monthLabel(host.selectedMonth);
  }
  static String rm(long cents){
    return String.format(Locale.US,"RM %,.2f",cents/100.0);
  }
  long value(DocumentSnapshot d,String field){
    Long v=d.getLong(field);return v==null?0:v;
  }
  void refresh(){
    String current=selectedBranchId;
    stop();selectedBranchId=current;
    ensureLoaded();
  }
  void ensureLoaded(){
    if(!accounts.owner())return;
    final String uid=accounts.ownerUid();
    final String from=start(),to=end(),scope=uid+":"+from+":"+to;
    if(scope.equals(subscriptionKey)&&branchListener!=null)return;
    String previousSelection=selectedBranchId;
    stop();selectedBranchId=previousSelection;subscriptionKey=scope;
    branchListener=db.collection("workspaces").document(uid).collection("branches")
      .addSnapshotListener((results,error)->{
        if(!scope.equals(subscriptionKey)||!accounts.owner()||
          !uid.equals(accounts.ownerUid()))return;
        if(error!=null){
          loadError="Gagal baca semua branch: "+error.getMessage();
          listLoaded=true;redrawIfVisible();return;
        }
        if(results==null)return;
        for(ListenerRegistration l:dayListeners)l.remove();
        dayListeners.clear();rows.clear();
        listLoaded=true;loadError="";
        for(QueryDocumentSnapshot branch:results){
          String name=branch.getString("name");
          if(name==null||name.trim().isEmpty())name="Branch "+branch.getId();
          String status=branch.getString("operatingStatus");
          BranchRow row=new BranchRow(branch.getId(),name,
            Boolean.TRUE.equals(branch.getBoolean("archived"))?"ARKIB · REKOD LAMA":
              BranchAccounts.operatingLabel(status));
          row.local=readLocalDays(row.id,from,to,false);
          row.legacyOnly=readLocalDays(row.id,from,to,true);
          rows.add(row);
          ListenerRegistration listener=branch.getReference().collection("daily")
            .whereGreaterThanOrEqualTo(FieldPath.documentId(),from)
            .whereLessThanOrEqualTo(FieldPath.documentId(),to)
            .addSnapshotListener((days,e)->{
              if(!scope.equals(subscriptionKey)||!rows.contains(row))return;
              if(e!=null){
                // Do NOT pretend missing cloud data equals zero sales.
                loadError="Gagal baca jualan "+row.name+": "+e.getMessage();
                row.seen=false;redrawIfVisible();return;
              }
              if(days==null)return;
              row.remote.clear();
              row.cache=days.getMetadata().isFromCache();
              for(DocumentSnapshot d:days.getDocuments()){
                row.remote.put(d.getId(),new Day(value(d,"sales"),value(d,"cash"),
                  value(d,"qr"),value(d,"transactions"),true));
              }
              row.seen=true;
              redrawIfVisible();
            });
          dayListeners.add(listener);
        }
        redrawIfVisible();
      });
  }
  TreeMap<String,Day> readLocalDays(String branchId,String from,String to,
      boolean legacyOnly){
    TreeMap<String,Day> result=new TreeMap<>();
    SharedPreferences p=BranchAccounts.LEGACY_BRANCH.equals(branchId)?
      host.getPreferences(0):
      host.getSharedPreferences("warisan_branch_"+branchId,0);
    try{
      JSONArray sales=new JSONArray(p.getString("stock_sales","[]"));
      for(int i=0;i<sales.length();i++){
        JSONObject receipt=sales.optJSONObject(i);
        if(receipt==null||receipt.optBoolean("refunded",false))continue;
        // Only pre-LIVE receipts may supplement a day with cloud totals.
        if(legacyOnly&&!receipt.optString("liveOrderId","").isEmpty())continue;
        String day=host.entryDay(receipt);
        if(day.compareTo(from)<0||day.compareTo(to)>0)continue;
        long value=host.saleTotal(receipt);
        Day d=result.get(day);
        if(d==null){d=new Day();result.put(day,d);}
        d.sales+=value;d.transactions++;
        int kind=host.paymentKind(receipt); // QR/Bank/TnG/Touch/Online as in old POS
        if(kind==1)d.cash+=value;
        else if(kind==2)d.qr+=value;
        else d.unknown+=value;
      }
    }catch(Exception ignored){
      // Never erase or modify the old POS preferences on a read failure.
    }
    // Very old Warisan POS saved only day totals, not per-sale receipts.
    // Those totals cannot safely be supplemented into an existing cloud day
    // because the underlying order IDs/methods are unavailable.
    if(!legacyOnly)for(String k:p.getAll().keySet()){
      if(!k.startsWith("sales_")||k.length()!=16)continue;
      String day=k.substring(6);
      if(day.compareTo(from)<0||day.compareTo(to)>0)continue;
      if(!result.containsKey(day))
        result.put(day,new Day(p.getInt(k,0),0,0,0,false));
    }
    return result;
  }
  boolean ready(){
    if(!listLoaded||!loadError.isEmpty())return false;
    for(BranchRow row:rows)if(!row.seen)return false;
    return true;
  }
  void redrawIfVisible(){
    if(host.activePage==3||host.activePage==16||host.activePage==17||host.activePage==19||host.activePage==21)
      host.draw();
  }
  Day overall(){
    Day all=new Day();
    for(BranchRow row:rows){
      Day one=row.totals();
      all.add(one);
    }
    return all;
  }
  void modeSelector(){
    LinearLayout mode=host.row();
    android.widget.TextView daily=host.chip("HARIAN",
      host.cashDailyMode?host.gold:0xff294563,host.cashDailyMode?0xff17202c:android.graphics.Color.WHITE);
    android.widget.TextView monthly=host.chip("BULANAN",
      host.cashDailyMode?0xff294563:host.gold,host.cashDailyMode?android.graphics.Color.WHITE:0xff17202c);
    daily.setGravity(android.view.Gravity.CENTER);monthly.setGravity(android.view.Gravity.CENTER);
    mode.addView(daily,new LinearLayout.LayoutParams(0,host.dp(42),1));
    LinearLayout.LayoutParams m=new LinearLayout.LayoutParams(0,host.dp(42),1);
    m.leftMargin=host.dp(8);mode.addView(monthly,m);
    host.add(host.body,mode,-1,-2);host.gap(host.body,8);
    daily.setOnClickListener(v->{host.cashDailyMode=true;host.draw();});
    monthly.setOnClickListener(v->{host.cashDailyMode=false;host.draw();});
    if(host.cashDailyMode)
      host.action("Tarikh: "+host.selectedCashDay+"  ▼",()->host.chooseCashDay());
    else host.action("Bulan: "+host.monthLabel(host.selectedMonth)+"  ▼",()->host.chooseMonth());
  }
  void dataMessage(){
    if(!loadError.isEmpty())
      host.add(host.body,host.text(loadError,12,0xffff6b6b,true),-1,-2);
    else if(!ready())
      host.add(host.body,host.text("Memuatkan laporan semua branch dari Firebase...",12,host.muted,false),-1,-2);
    else {
      boolean cached=false,local=false;
      for(BranchRow row:rows){cached|=row.cache;local|=row.hasHistoricalFallback();}
      host.add(host.body,host.text(
        (cached?"Sebahagian angka daripada cache. Sambung internet untuk sahkan semula. ":"")+
        (local?"Termasuk rekod lama daripada telefon Owner pada tarikh yang belum ada data Cloud. ":"")+
        "Rekod lama sebelum Live dipaparkan bersama transaksi Cloud tanpa mengira resit Live dua kali. "+
        "Rekod yang hanya ada pada telefon lain mungkin belum diselaraskan. "+
        "Bayaran tanpa kaedah jelas ditunjukkan sebagai BELUM DIKELASKAN, bukan QR atau Tunai. "+
        "Untung bersih memerlukan kos setiap branch; angka ini jualan kasar.",
        11,host.muted,false),-1,-2);
    }
  }
  void drawOverview(){
    if(!accounts.owner()){host.heading("Akses Owner","Cashflow semua branch hanya untuk Owner.");return;}
    host.cashDailyMode=false;
    host.heading("DUIT · OVERALL",
      "Keseluruhan jualan. Tekan TOTAL SALE untuk pilih branch.");
    host.action("🔓  OPENING / CLOSING · "+accounts.branchName(),()->{
      host.activePage=22;host.draw();
    });
    host.add(host.body,host.text("Sesi Opening/Closing untuk branch Owner yang sedang aktif. "+
      "Tidak mengubah rekod branch lain.",11,host.muted,false),-1,-2);
    host.gap(host.body,8);
    ensureLoaded();
    host.add(host.body,host.text("SEMUA BRANCH · "+period(),12,host.gold,true),-1,-2);
    host.gap(host.body,7);
    Day total=overall();
    String label="TOTAL SALE · SEMUA BRANCH  ›";
    LinearLayout big=host.row();
    host.metric(big,label,ready()?rm(total.sales):"—",host.blue,()->{
      host.activePage=16;host.draw();
    });
    host.add(host.body,big,-1,-2);host.gap(host.body,9);
    LinearLayout pair=host.row();
    host.metric(pair,"TUNAI",ready()?rm(total.cash):"—",host.ink,null);
    host.metric(pair,"QR / DUITNOW  ›",ready()?rm(total.qr):"—",host.gold,
      ()->showQrHistory(null));
    host.add(host.body,pair,-1,-2);host.gap(host.body,9);
    if(ready()&&total.unknown>0){
      LinearLayout misc=host.row();
      host.metric(misc,"KAEDAH TIDAK DIKELASKAN",rm(total.unknown),
        host.gold,null);
      host.add(host.body,misc,-1,-2);host.gap(host.body,8);
    }
    LinearLayout tx=host.row();
    host.metric(tx,"TRANSAKSI SEMUA BRANCH",ready()?String.valueOf(total.transactions):"—",
      host.blue,()->{host.activePage=16;host.draw();});
    host.add(host.body,tx,-1,-2);host.gap(host.body,9);
    // This Owner device knows its own product cost and operating expenses.
    // Never subtract those costs from combined Cloud sales, which also
    // contain branches whose expenditure has not been synchronized.
    host.add(host.body,host.text("UNTUNG BERSIH · "+accounts.branchName().toUpperCase(Locale.ROOT),
      13,host.gold,true),-1,-2);
    host.gap(host.body,6);
    String monthStart=host.selectedMonth+"-01",monthEnd=host.selectedMonth+"-31";
    int localSales=host.saleTotalBetween(monthStart,monthEnd);
    int localCost=host.productCostBetween(monthStart,monthEnd);
    int localOperations=host.operatingExpenseTotal(host.selectedMonth,false);
    int localProfit=host.businessProfit(host.selectedMonth,false);
    LinearLayout localSummary=host.row();
    host.metric(localSummary,"UNTUNG BERSIH POS TEMPATAN",rm(localProfit),
      localProfit>=0?host.gold:0xffff6b6b,()->{
        selectedBranchId=accounts.branchId();host.activePage=19;host.draw();
      });
    host.add(host.body,localSummary,-1,-2);host.gap(host.body,8);
    LinearLayout localBreakdown=host.row();
    host.metric(localBreakdown,"KOS PRODUK",rm(localCost),host.ink,null);
    host.metric(localBreakdown,"BELANJA OPERASI",rm(localOperations),host.ink,null);
    host.add(host.body,localBreakdown,-1,-2);host.gap(host.body,6);
    host.add(host.body,host.text("Jualan POS tempatan: "+rm(localSales)+
      ". Untung ini dikira daripada rekod tempatan "+accounts.branchName()+
      " sahaja. Jualan Live dari telefon pekerja dan kos branch lain belum "+
      "semuanya termasuk. Jangan samakan dengan untung semua branch.",
      11,host.muted,false),-1,-2);
    host.gap(host.body,11);
    host.action("📊  LAPORAN KESELURUHAN · SEMUA BRANCH",()->{
      host.activePage=21;host.draw();
    });
    host.action("🏪  PILIH LAPORAN SATU BRANCH",()->{
      host.activePage=16;host.draw();
    });
    host.gap(host.body,8);
    host.add(host.body,host.text("PEMBEKAL · BRANCH AKTIF: "+
      accounts.branchName(),12,host.gold,true),-1,-2);
    host.gap(host.body,5);
    host.action("BAYARAN PEMBEKAL · "+host.money(host.supplierDue()),()->host.supplierHub());
    host.action("HISTORY BAYARAN PEMBEKAL",()->host.supplierAllHistory());
    host.add(host.body,host.text("Bayaran pembekal hanya rekod pada telefon Owner bagi branch "+
      "aktif, bukan hutang gabungan semua branch. Kos pembelian tidak ditolak dua kali.",
      11,host.muted,false),-1,-2);
    host.gap(host.body,8);
    dataMessage();
  }
  TreeMap<String,Day> overallDays(){
    TreeMap<String,Day> byDay=new TreeMap<>();
    for(BranchRow branch:rows){
      for(Map.Entry<String,Day> record:branch.combined().entrySet()){
        Day day=byDay.get(record.getKey());
        if(day==null){day=new Day();byDay.put(record.getKey(),day);}
        day.add(record.getValue());
      }
    }
    return byDay;
  }
  void drawOverallReport(){
    if(!accounts.owner()){
      host.heading("Akses Ditolak","Laporan gabungan hanya untuk Owner.");return;
    }
    host.cashDailyMode=false;
    host.heading("LAPORAN KESELURUHAN · SEMUA BRANCH",
      "Jumlah jualan gabungan dan pecahan setiap branch, tanpa menggandakan resit Live.");
    host.action("‹  KEMBALI KE DUIT",()->{host.activePage=3;host.draw();});
    host.action("Bulan: "+host.monthLabel(host.selectedMonth)+"  ▼",()->host.chooseMonth());
    ensureLoaded();
    if(!ready()){
      dataMessage();
      host.action("↻  CUBA SEMULA",()->refresh());
      return;
    }
    Day total=overall();
    host.add(host.body,host.text("SEMUA BRANCH · "+period(),12,host.gold,true),-1,-2);
    host.gap(host.body,7);
    LinearLayout summary=host.row();
    host.metric(summary,"JUMLAH JUALAN",rm(total.sales),host.blue,null);
    host.metric(summary,"TRANSAKSI",String.valueOf(total.transactions),host.gold,null);
    host.add(host.body,summary,-1,-2);host.gap(host.body,8);
    LinearLayout methods=host.row();
    host.metric(methods,"TUNAI",rm(total.cash),host.ink,null);
    host.metric(methods,"QR / DUITNOW",rm(total.qr),host.blue,null);
    host.add(host.body,methods,-1,-2);host.gap(host.body,10);
    if(total.unknown>0)
      host.add(host.body,host.text("BAYARAN TIDAK DIKELASKAN: "+rm(total.unknown),
        12,host.gold,true),-1,-2);
    host.add(host.body,host.text("GRAF JUALAN · SEMUA BRANCH",13,host.gold,true),-1,-2);
    host.gap(host.body,7);
    host.add(host.body,new OwnerSalesChart(host,overallDays(),host.selectedMonth),
      -1,host.dp(218));
    host.gap(host.body,11);
    host.action("📄  EXPORT PDF A4 · LAPORAN SEMUA BRANCH",()->{
      OwnerOverallReportPdf.print(host,host.selectedMonth,rows,overallDays());
    });
    host.add(host.body,host.text("PECAHAN IKUT BRANCH",13,host.gold,true),-1,-2);
    host.gap(host.body,7);
    for(BranchRow branch:rows){
      Day figure=branch.totals();
      host.action("🏪  "+branch.name+"  ·  "+rm(figure.sales)+
        "  ·  "+figure.transactions+" transaksi",()->{
        selectedBranchId=branch.id;
        host.activePage=19;host.draw();
      });
    }
    host.gap(host.body,7);
    host.add(host.body,host.text("Untung bersih dan kos keseluruhan belum dipaparkan: "+
      "kos pembekal, stok rosak dan operasi setiap branch belum semuanya "+
      "diselaraskan ke Cloud. Jangan anggap jualan kasar sebagai untung.",
      12,host.muted,false),-1,-2);
    host.gap(host.body,8);
    host.add(host.body,host.text("JUALAN MENGIKUT TARIKH · SEMUA BRANCH",13,host.gold,true),-1,-2);
    host.gap(host.body,6);
    for(Map.Entry<String,Day> entry:overallDays().descendingMap().entrySet()){
      Day d=entry.getValue();
      host.add(host.body,host.text(entry.getKey()+"  ·  "+rm(d.sales)+
        "  ·  Tunai "+rm(d.cash)+"  ·  QR "+rm(d.qr),12,host.ink,false),-1,-2);
      host.gap(host.body,4);
    }
    dataMessage();
  }
  void drawBranchList(){
    if(!accounts.owner()){host.heading("Akses ditolak","Owner sahaja.");return;}
    ensureLoaded();
    host.heading("Pilih Branch",period()+" · Tekan nama branch untuk melihat TOTAL SALE masing-masing.");
    host.action("‹  Kembali ke OVERALL",()->{host.activePage=3;host.draw();});
    if(!loadError.isEmpty()){dataMessage();host.action("↻  Cuba Lagi",()->refresh());return;}
    if(!ready()){dataMessage();return;}
    if(rows.isEmpty()){
      host.add(host.body,host.text("Tiada branch berdaftar untuk Owner.",13,host.muted,false),-1,-2);
      return;
    }
    host.add(host.body,host.text("JUMLAH SEMUA BRANCH: "+rm(overall().sales),16,host.gold,true),-1,-2);
    host.gap(host.body,12);
    for(BranchRow row:rows){
      Day total=row.totals();
      host.action("🏪  "+row.name+"\n      "+rm(total.sales)+
        "  ·  "+total.transactions+" transaksi"+
        ("BUKA".equals(row.status)?"":"  ·  "+row.status),()->{
          selectedBranchId=row.id;host.activePage=17;host.draw();
        });
    }
    host.gap(host.body,10);
    dataMessage();
  }
  void drawBranchDetail(){
    if(!accounts.owner()){host.heading("Akses ditolak","Owner sahaja.");return;}
    ensureLoaded();
    BranchRow branch=null;
    for(BranchRow row:rows)if(row.id.equals(selectedBranchId)){branch=row;break;}
    if(branch==null){
      host.heading("Branch tidak dijumpai","Pilih branch semula.");
      host.action("‹  Senarai Branch",()->{host.activePage=16;host.draw();});
      return;
    }
    final BranchRow chosen=branch;
    host.heading(chosen.name,"Jualan "+period()+" · "+chosen.status);
    host.action("‹  Kembali ke Jualan Semua Branch",()->{host.activePage=16;host.draw();});
    if(!branch.seen){dataMessage();return;}
    Day total=branch.totals();
    LinearLayout main=host.row();
    host.metric(main,"TOTAL SALE · "+chosen.name+"  ›",rm(total.sales),host.blue,
      ()->{host.activePage=19;host.draw();});
    host.add(host.body,main,-1,-2);host.gap(host.body,9);
    LinearLayout pay=host.row();
    host.metric(pay,"TUNAI",rm(total.cash),host.gold,null);
    host.metric(pay,"QR / DUITNOW  ›",rm(total.qr),host.blue,
      ()->showQrHistory(chosen));
    host.add(host.body,pay,-1,-2);host.gap(host.body,9);
    if(total.unknown>0)
      host.add(host.body,host.text("Bayaran belum dikelaskan: "+rm(total.unknown)+
        " · Semak rekod lama yang tidak menyimpan kaedah pembayaran.",12,host.gold,true),-1,-2);
    LinearLayout count=host.row();
    host.metric(count,"BILANGAN TRANSAKSI",String.valueOf(total.transactions),host.ink,null);
    host.add(host.body,count,-1,-2);host.gap(host.body,10);
    if(chosen.id.equals(accounts.branchId())){
      int branchLocalProfit=host.businessProfit(host.selectedMonth,false);
      LinearLayout profitBox=host.row();
      host.metric(profitBox,"UNTUNG BERSIH POS TEMPATAN",rm(branchLocalProfit),
        branchLocalProfit>=0?host.gold:0xffff6b6b,null);
      host.add(host.body,profitBox,-1,-2);host.gap(host.body,6);
      host.add(host.body,host.text("Kos dan keuntungan dari rekod pada telefon branch aktif sahaja. "+
        "Jualan Cloud yang belum direkodkan tempatan tidak dikira dalam untung ini.",
        11,host.muted,false),-1,-2);
      host.gap(host.body,8);
    }else{
      host.add(host.body,host.text("Untung branch ini belum tersedia kerana "+
        "kos perniagaan belum diselaraskan daripada telefon branch tersebut.",
        11,host.muted,false),-1,-2);
      host.gap(host.body,8);
    }
    host.add(host.body,host.text("Tekan TOTAL SALE di atas untuk melihat semua laporan jualan, "+
      "QR, kos dan sejarah tempatan branch ini.",12,host.muted,false),-1,-2);
    host.gap(host.body,8);
    dataMessage();
  }
  BranchRow selectedBranch(){
    for(BranchRow row:rows)if(row.id.equals(selectedBranchId))return row;
    return null;
  }
  void drawUnifiedReport(){
    if(!accounts.owner()){
      host.heading("Akses Ditolak","Laporan branch hanya boleh dilihat oleh Owner.");
      return;
    }
    ensureLoaded();
    BranchRow branch=selectedBranch();
    if(branch==null){
      host.heading("Laporan","Pilih branch dahulu untuk lihat rekod jualan.");
      host.action("‹  KEMBALI KE SENARAI BRANCH",()->{
        host.activePage=16;host.draw();
      });
      return;
    }
    final BranchRow chosen=branch;
    host.cashDailyMode=false;
    host.heading("LAPORAN · "+chosen.name,
      "Semua rekod jualan lama dan Live, tunai, QR, serta kos tempatan dalam satu halaman.");
    host.action("‹  KEMBALI KE TOTAL SALE "+chosen.name,()->{host.activePage=17;host.draw();});
    host.action("Bulan: "+host.monthLabel(host.selectedMonth)+"  ▼",()->host.chooseMonth());
    if(!loadError.isEmpty()||!ready()){
      dataMessage();
      host.action("↻  SEMAK SEMULA",()->refresh());
      return;
    }
    Day total=chosen.totals();
    host.add(host.body,host.text("RINGKASAN · "+period(),12,host.gold,true),-1,-2);
    host.gap(host.body,8);
    LinearLayout gross=host.row();
    host.metric(gross,"JUMLAH JUALAN LAMA + LIVE",rm(total.sales),host.blue,null);
    host.add(host.body,gross,-1,-2);host.gap(host.body,8);
    LinearLayout payment=host.row();
    host.metric(payment,"TUNAI",rm(total.cash),host.ink,null);
    host.metric(payment,"QR / DUITNOW",rm(total.qr),host.gold,
      ()->showQrHistory(chosen));
    host.add(host.body,payment,-1,-2);host.gap(host.body,8);
    LinearLayout counts=host.row();
    host.metric(counts,"JUMLAH TRANSAKSI",String.valueOf(total.transactions),
      host.blue,null);
    host.add(host.body,counts,-1,-2);
    if(total.unknown>0){
      host.gap(host.body,6);
      host.add(host.body,host.text("Kaedah bayaran tidak dikelaskan: "+rm(total.unknown),
        12,host.muted,false),-1,-2);
    }
    host.gap(host.body,12);
    host.add(host.body,host.text("GRAF JUALAN · "+chosen.name,13,host.gold,true),-1,-2);
    host.gap(host.body,7);
    host.add(host.body,new OwnerSalesChart(host,chosen.combined(),host.selectedMonth),
      -1,host.dp(218));
    host.gap(host.body,9);
    host.action("📊  EXPORT PDF A4 · GRAF & REKOD JUALAN",()->{
      OwnerReportPdf.print(host,chosen,period(),chosen.id.equals(accounts.branchId()));
    });
    host.action("🧾  REKOD RESIT QR / TNG / DUITNOW",()->showQrHistory(chosen));
    host.gap(host.body,12);
    host.add(host.body,host.text("KOS & UNTUNG · REKOD TEMPATAN",13,host.gold,true),-1,-2);
    host.gap(host.body,7);
    if(chosen.id.equals(accounts.branchId())){
      // Only this branch's locally stored receipts/costs are visible.
      // Cloud sales from other phones must never be labelled local profit.
      String first=host.selectedMonth+"-01",last=host.selectedMonth+"-31";
      int localSales=host.saleTotalBetween(first,last);
      int cost=host.productCostBetween(first,last);
      int operations=host.operatingExpenseTotal(host.selectedMonth,false);
      int profit=host.businessProfit(host.selectedMonth,false);
      int personal=host.personalTotal(host.selectedMonth,false);
      LinearLayout local=host.row();
      host.metric(local,"JUALAN POS TEMPATAN",rm(localSales),host.blue,null);
      host.metric(local,"KOS PRODUK",rm(cost),host.ink,null);
      host.add(host.body,local,-1,-2);host.gap(host.body,8);
      LinearLayout expenses=host.row();
      host.metric(expenses,"BELANJA OPERASI",rm(operations),host.ink,null);
      host.metric(expenses,"UNTUNG POS TEMPATAN",rm(profit),
        profit>=0?host.gold:0xffff6b6b,null);
      host.add(host.body,expenses,-1,-2);host.gap(host.body,8);
      host.add(host.body,host.text("Pengeluaran peribadi: "+rm(personal)+
        "  ·  Baki pembekal perlu bayar: "+rm(host.supplierDue()),12,host.muted,false),-1,-2);
      host.gap(host.body,6);
      host.add(host.body,host.text("Kos dan untung di sini dikira daripada rekod POS "+
        "tempatan branch aktif sahaja. Jangan samakan dengan untung keseluruhan Cloud.",11,host.muted,false),-1,-2);
    }else{
      host.add(host.body,host.text("Jualan Cloud branch ini tersedia. Kos dan "+
        "untung tempatan belum diselaraskan daripada telefon branch ini; "+
        "angka kos tidak ditunjukkan supaya tidak tersalah sebagai RM0.",12,host.muted,false),-1,-2);
    }
    if(chosen.id.equals(accounts.branchId())){
      host.gap(host.body,10);
      host.add(host.body,host.text("ITEM TERJUAL · REKOD TEMPATAN",12,host.gold,true),-1,-2);
      String localFrom=host.selectedMonth+"-01",localTo=host.selectedMonth+"-31";
      int[] sold=host.soldNetBetween(localFrom,localTo);
      boolean anyProduct=false;
      for(int i=0;i<Math.min(sold.length,host.names.length);i++){
        if(sold[i]<=0)continue;
        anyProduct=true;
        host.add(host.body,host.text(host.names[i]+"  ·  "+sold[i]+
          (i<3?" cucuk":" unit"),12,host.ink,false),-1,-2);
        host.gap(host.body,4);
      }
      if(!anyProduct)host.add(host.body,host.text("Tiada butiran produk tempatan.",
        12,host.muted,false),-1,-2);
      host.gap(host.body,11);
      host.add(host.body,host.text("PECAHAN KOS / PENGELUARAN TEMPATAN",
        12,host.gold,true),-1,-2);
      java.util.TreeMap<String,Long> costs=new java.util.TreeMap<>();
      JSONArray spent=host.entries("cash_entries");
      for(int i=0;i<spent.length();i++){
        JSONObject entry=spent.optJSONObject(i);
        if(entry==null||host.legacySupplierRestock(entry)||entry.optInt("amount",0)>=0)continue;
        String d=host.entryDay(entry);
        if(d.compareTo(localFrom)<0||d.compareTo(localTo)>0)continue;
        String category=entry.optString("category","Lain-lain");
        if(category.trim().isEmpty())category="Lain-lain";
        costs.put(category,costs.getOrDefault(category,0L)-
          entry.optLong("amount",0));
      }
      if(costs.isEmpty()){
        host.add(host.body,host.text("Tiada rekod kos tunai untuk bulan ini.",
          12,host.muted,false),-1,-2);
      }else for(Map.Entry<String,Long> item:costs.entrySet()){
        host.add(host.body,host.text(item.getKey()+"  ·  "+rm(item.getValue()),
          12,host.ink,false),-1,-2);
        host.gap(host.body,4);
      }
      host.add(host.body,host.text(
        "Ini sejarah duit keluar (termasuk pembekal/peribadi). "+
        "Ia bukan jumlah tambahan untuk ditolak sekali lagi daripada untung POS.",
        11,host.muted,false),-1,-2);
    }
    host.gap(host.body,13);
    host.add(host.body,host.text("REKOD JUALAN MENGIKUT TARIKH",13,host.gold,true),-1,-2);
    host.gap(host.body,7);
    TreeMap<String,Day> days=chosen.combined();
    if(days.isEmpty()){
      host.add(host.body,host.text("Tiada rekod jualan untuk tarikh dipilih.",
        12,host.muted,false),-1,-2);
    }else{
      for(Map.Entry<String,Day> item:days.descendingMap().entrySet()){
        final String dayDate=item.getKey();
        final Day figures=item.getValue();
        String kind=figures.cloud?
          (figures.hasLocalSupplement?"Live + Rekod Lama":"Live"):"Rekod Lama";
        android.widget.TextView itemRow=host.text(
          dayDate+"     "+rm(figures.sales)+"\n"+kind+
          "   ·   Tunai "+rm(figures.cash)+"   ·   QR "+rm(figures.qr),
          12,host.ink,false);
        itemRow.setPadding(host.dp(12),host.dp(11),host.dp(10),host.dp(11));
        itemRow.setBackground(host.shape(host.surface,10));
        host.add(host.body,itemRow,-1,-2);host.gap(host.body,5);
        itemRow.setOnClickListener(v->showDay(dayDate,figures,chosen.name));
      }
    }
    host.gap(host.body,12);
    dataMessage();
  }
  String reportText(BranchRow row){
    Day total=row.totals();
    StringBuilder b=new StringBuilder();
    b.append("WARISAN POS · LAPORAN JUALAN\n")
      .append(row.name).append("\n")
      .append(period()).append("\n")
      .append("-------------------------------\n")
      .append("Jumlah Jualan: ").append(rm(total.sales)).append("\n")
      .append("Tunai: ").append(rm(total.cash)).append("\n")
      .append("QR/DuitNow: ").append(rm(total.qr)).append("\n")
      .append("Belum dikelaskan: ").append(rm(total.unknown)).append("\n")
      .append("Transaksi: ").append(total.transactions).append("\n")
      .append("-------------------------------\n");
    for(Map.Entry<String,Day> item:row.combined().descendingMap().entrySet()){
      Day d=item.getValue();
      b.append(item.getKey()).append(" : ").append(rm(d.sales)).append("\n")
        .append("  Tunai ").append(rm(d.cash))
        .append("  QR ").append(rm(d.qr))
        .append("  TX ").append(d.transactions)
        .append("  ")
        .append(d.cloud?"Live":"Lama")
        .append(d.hasLocalSupplement?"+Lama":"")
        .append("\n");
    }
    if(row.id.equals(accounts.branchId())){
      String begin=host.selectedMonth+"-01",finish=host.selectedMonth+"-31";
      b.append("-------------------------------\n")
        .append("KOS & UNTUNG POS TEMPATAN SAHAJA\n")
        .append("Jualan tempatan: ").append(rm(host.saleTotalBetween(begin,finish))).append("\n")
        .append("Kos produk: ").append(rm(host.productCostBetween(begin,finish))).append("\n")
        .append("Kos operasi: ").append(rm(host.operatingExpenseTotal(host.selectedMonth,false))).append("\n")
        .append("Untung tempatan: ").append(rm(host.businessProfit(host.selectedMonth,false))).append("\n");
    }else{
      b.append("Kos branch lain belum diselaraskan.\n");
    }
    b.append("-------------------------------\n")
      .append("Data lama pada telefon lain mungkin belum sync.\n")
      .append("Untung POS tempatan tidak termasuk semua jualan Cloud.\n");
    return b.toString();
  }
  static final class QrReceipt {
    final String date,branch,method,status,reference,source,clock;
    final long amount;
    QrReceipt(String date,String branch,String method,String status,
              String reference,String source,String clock,long amount){
      this.date=date;this.branch=branch;this.method=method;this.status=status;
      this.reference=reference;this.source=source;this.clock=clock;this.amount=amount;
    }
    String shortLabel(){
      return date+"  "+branch+"\n"+rm(amount)+" · "+method+
        ("REFUNDED".equals(status)?" · REFUND":"");
    }
  }
  static boolean qrMethod(String method){
    if(method==null)return false;
    String value=method.toLowerCase(Locale.ROOT);
    return value.contains("qr")||value.contains("duitnow")||
      value.contains("tng")||value.contains("touch")||
      value.contains("bank")||value.contains("online");
  }
  void showQrHistory(BranchRow selected){
    if(!accounts.owner())return;
    ensureLoaded();
    final String from=start(),to=end(),ownerId=accounts.ownerUid();
    final ArrayList<BranchRow> chosen=new ArrayList<>();
    for(BranchRow r:rows)if(selected==null||selected.id.equals(r.id))chosen.add(r);
    if(chosen.isEmpty()){
      host.message("Tiada branch yang boleh diperiksa.");return;
    }
    // Read the liveOrders receipts only on demand to limit Spark free reads.
    // Each branch is a separate query. We do not edit or delete any receipt.
    final ArrayList<Task<QuerySnapshot>> calls=new ArrayList<>();
    for(BranchRow r:chosen)
      calls.add(db.collection("workspaces").document(ownerId)
        .collection("branches").document(r.id)
        .collection("liveOrders")
        .whereGreaterThanOrEqualTo("day",from)
        .whereLessThanOrEqualTo("day",to)
        .get(com.google.firebase.firestore.Source.SERVER));
    host.message("Mendapatkan rekod bayaran QR daripada Cloud dan telefon Owner...");
    Tasks.whenAllSuccess(calls).addOnSuccessListener(results->{
      if(!accounts.owner()||!ownerId.equals(accounts.ownerUid()))return;
      final ArrayList<QrReceipt> receipts=new ArrayList<>();
      final Set<String> cloudIds=new HashSet<>();
      long paidSum=0;
      for(int index=0;index<results.size();index++){
        QuerySnapshot response=(QuerySnapshot)results.get(index);
        BranchRow branch=chosen.get(index);
        for(DocumentSnapshot r:response.getDocuments()){
          // Keep Cloud IDs for all payment methods to avoid duplicates when
          // local records already mirror the same cloud transaction.
          cloudIds.add(branch.id+"/"+r.getId());
          String method=r.getString("method");
          String status=r.getString("status");
          if(!qrMethod(method)||(!"PAID".equals(status)&&!"REFUNDED".equals(status)))continue;
          Long amount=r.getLong("total");
          if(amount==null)continue;
          String clock="";
          com.google.firebase.Timestamp stamp=r.getTimestamp("paidAt");
          if(stamp!=null)clock=new java.text.SimpleDateFormat(
            "HH:mm",Locale.US).format(stamp.toDate());
          receipts.add(new QrReceipt(r.getString("day"),branch.name,method,status,
            r.getId(),"CLOUD LIVE",clock,amount));
          if("PAID".equals(status))paidSum+=amount;
        }
      }
      for(BranchRow branch:chosen){
        SharedPreferences pref=BranchAccounts.LEGACY_BRANCH.equals(branch.id)?
          host.getPreferences(0):
          host.getSharedPreferences("warisan_branch_"+branch.id,0);
        JSONArray local;
        try{local=new JSONArray(pref.getString("stock_sales","[]"));}
        catch(Exception e){continue;}
        for(int i=0;i<local.length();i++){
          JSONObject receipt=local.optJSONObject(i);
          if(receipt==null||host.paymentKind(receipt)!=2)continue;
          String day=host.entryDay(receipt);
          if(day.compareTo(from)<0||day.compareTo(to)>0)continue;
          String cloudId=receipt.optString("liveOrderId","");
          if(!cloudId.isEmpty()&&cloudIds.contains(branch.id+"/"+cloudId))continue;
          String method=receipt.optString("method",
            receipt.optString("paymentMethod","QR"));
          String clock=receipt.optString("time","");
          if(clock.length()>19)clock=clock.substring(clock.length()-8);
          boolean refund=receipt.optBoolean("refunded",false);
          long amount=host.saleTotal(receipt);
          receipts.add(new QrReceipt(day,branch.name,method,refund?"REFUNDED":"PAID",
            receipt.optString("order",""+(i+1)),
            "REKOD LAMA TELEFON",clock,amount));
          if(!refund)paidSum+=amount;
        }
      }
      receipts.sort((a,b)->{
        int day=b.date.compareTo(a.date);
        if(day!=0)return day;
        return b.clock.compareTo(a.clock);
      });
      if(receipts.isEmpty()){
        host.darkBuilder().setTitle("REKOD QR · "+period())
          .setMessage("Tiada resit QR/DuitNow/TnG dalam rekod Cloud atau telefon Owner bagi tarikh dipilih."+
            "\nJika transaksi lama dibuat di telefon lain tetapi belum diselaraskan, ia belum boleh dipaparkan.")
          .setPositiveButton("TUTUP",null).show();
        return;
      }
      // Both refunds and paid orders appear. The headline sums only PAID.
      String[] labels=new String[Math.min(receipts.size(),250)];
      for(int i=0;i<labels.length;i++)labels[i]=receipts.get(i).shortLabel();
      final long shownTotal=paidSum;
      host.darkBuilder().setTitle("REKOD QR · "+receipts.size()+
        " resit · "+rm(shownTotal))
        .setItems(labels,(dialog,i)->{
          QrReceipt q=receipts.get(i);
          host.darkBuilder().setTitle("RESIT QR · "+q.branch)
            .setMessage("Tarikh: "+q.date+(q.clock.isEmpty()?"":" · "+q.clock)+
              "\nJumlah: "+rm(q.amount)+"\nKaedah: "+q.method+
              "\nStatus: "+q.status+"\nRujukan: "+q.reference+
              "\nSumber: "+q.source+
              "\n\nRekod ini untuk semakan sahaja. Tiada jualan atau bayaran diubah.")
            .setPositiveButton("OK",null).show();
        })
        .setNegativeButton("TUTUP",null).show();
      if(receipts.size()>labels.length)
        host.message("Memaparkan 250 resit terbaru. Pilih tarikh lebih pendek untuk resit lain.");
    }).addOnFailureListener(error->
      host.darkBuilder().setTitle("Rekod QR tidak dapat dimuatkan")
        .setMessage("Cloud perlu dapat dihubungi untuk memastikan resit QR tidak digandakan."+
          "\nTiada data diubah.\n\n"+error.getMessage())
        .setPositiveButton("OK",null).show());
  }
  void showDay(String day,Day value,String branchName){
    host.darkBuilder().setTitle("Jualan "+branchName+" · "+day)
      .setMessage("Jumlah jualan: "+rm(value.sales)+
        "\nTunai: "+rm(value.cash)+"\nQR: "+rm(value.qr)+
        "\nTransaksi: "+value.transactions+
        "\nBelum dikelaskan: "+rm(value.unknown)+
        "\nSumber: "+(value.cloud?"Firebase Live":"Rekod lama telefon Owner")+
        (value.hasLocalSupplement?" + jualan sebelum Live":"")+
        "\n\nRekod lama mungkin tidak mempunyai pecahan Tunai/QR lengkap.")
      .setPositiveButton("TUTUP",null).show();
  }
}
