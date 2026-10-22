package my.warisan.pos;

import android.content.SharedPreferences;
import android.view.Gravity;
import android.widget.TextView;
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
    final boolean archived;
    final TreeMap<String,Day> remote = new TreeMap<>();
    TreeMap<String,Day> local = new TreeMap<>();
    TreeMap<String,Day> legacyOnly = new TreeMap<>();
    boolean seen=false,cache=false;
    int copiedLegacySales=0;
    boolean hasCopiedLocalData=false;
    BranchRow(String id,String name,String status,boolean archived){
      this.id=id;this.name=name;this.status=status;this.archived=archived;
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
  void openArchivedBranchReport(String id){
    if(!accounts.owner())return;
    selectedBranchId=id;
    host.cashDailyMode=false;
    host.activePage=19;
    host.draw();
  }
  /** Closing expenses are synced as audit records, not assumed to be complete profit. */
  void showCloudClosingHistory(String id){
    if(!accounts.owner())return;
    final String from=start(),to=end();
    db.collection("workspaces").document(accounts.ownerUid())
      .collection("branches").document(id).collection("liveSessions")
      .whereGreaterThanOrEqualTo(FieldPath.documentId(),from)
      .whereLessThanOrEqualTo(FieldPath.documentId(),to)
      .get(com.google.firebase.firestore.Source.SERVER)
      .addOnSuccessListener(snapshot->{
        StringBuilder report=new StringBuilder();int records=0;long allExpenses=0;
        for(DocumentSnapshot doc:snapshot.getDocuments()){
          if(Boolean.TRUE.equals(doc.getBoolean("open")))continue;
          records++;
          Long closing=doc.getLong("closing"),spent=doc.getLong("expenseTotal");
          long expense=spent==null?0:spent;allExpenses+=expense;
          report.append(doc.getId()).append("\n")
            .append("Baki tunai: ").append(closing==null?"—":rm(closing)).append("\n")
            .append("Duit keluar: ").append(spent==null?"Tiada rekod lama":rm(expense)).append("\n");
          Object lines=doc.get("expenses");
          if(lines instanceof List)for(Object line:(List<?>)lines){
            if(!(line instanceof Map))continue;
            Map<?,?> item=(Map<?,?>)line;
            Object amount=item.get("amount"),category=item.get("category");
            if(amount instanceof Number)
              report.append("  ").append(category==null?"Belanja":category)
                .append(": ").append(rm(((Number)amount).longValue())).append("\n");
          }
          String note=doc.getString("closingRemark");
          if(note!=null&&!note.isEmpty())report.append("Remark: ").append(note).append("\n");
          report.append("Ditutup oleh: ")
            .append(doc.getString("closedByEmail")==null?"Owner":doc.getString("closedByEmail"))
            .append("\n\n");
        }
        if(records==0)report.append("Tiada sesi Closing Cloud untuk tempoh ini.");
        else report.append("JUMLAH DUIT KELUAR DIREKOD: ").append(rm(allExpenses));
        host.darkBuilder().setTitle("REKOD CLOSING · "+records)
          .setMessage(report.toString()).setPositiveButton("TUTUP",null).show();
      }).addOnFailureListener(e->host.message("Gagal semak Closing Cloud: "+e.getMessage()));
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
        boolean originalMainExists=false;
        for(QueryDocumentSnapshot branch:results){
          if(BranchAccounts.LEGACY_BRANCH.equals(branch.getId()))
            originalMainExists=true;
        }
        for(QueryDocumentSnapshot branch:results){
          String name=branch.getString("name");
          if(name==null||name.trim().isEmpty())name="Branch "+branch.getId();
          String status=branch.getString("operatingStatus");
          boolean archived=Boolean.TRUE.equals(branch.getBoolean("archived"));
          BranchRow row=new BranchRow(branch.getId(),name,
            archived?"ARKIB · REKOD LAMA":BranchAccounts.operatingLabel(status),archived);
          if(!BranchAccounts.LEGACY_BRANCH.equals(row.id)){
            BranchDataAudit.Result audit=BranchDataAudit.compare(
              host.getPreferences(0),
              host.getSharedPreferences("warisan_branch_"+row.id,0));
            row.hasCopiedLocalData=audit.proven;
            row.copiedLegacySales=audit.copiedSales;
          }
          row.local=readLocalDays(row.id,from,to,false,originalMainExists);
          row.legacyOnly=readLocalDays(row.id,from,to,true,originalMainExists);
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
      boolean legacyOnly,boolean originalMainIncluded){
    TreeMap<String,Day> result=new TreeMap<>();
    boolean isOriginal=BranchAccounts.LEGACY_BRANCH.equals(branchId);
    SharedPreferences p=isOriginal?host.getPreferences(0):
      host.getSharedPreferences("warisan_branch_"+branchId,0);
    SharedPreferences source=host.getPreferences(0);
    // Only suppress duplicates when the original Main Branch is ALSO
    // represented in this overall report. Never silently drop historical
    // sales when its source branch is absent.
    boolean ignoreExactCopies=false;
    Map<String,Integer> remainingCopies=java.util.Collections.emptyMap();
    if(originalMainIncluded&&!isOriginal){
      BranchDataAudit.Result audit=BranchDataAudit.compare(source,p);
      if(audit.proven){
        ignoreExactCopies=true;
        remainingCopies=BranchDataAudit.frequencies(
          BranchDataAudit.ledger(source,"stock_sales"));
      }
    }
    try{
      JSONArray sales=new JSONArray(p.getString("stock_sales","[]"));
      for(int i=0;i<sales.length();i++){
        JSONObject receipt=sales.optJSONObject(i);
        if(receipt==null||receipt.optBoolean("refunded",false))continue;
        if(ignoreExactCopies){
          String sign=BranchDataAudit.signature(receipt);
          int copies=remainingCopies.containsKey(sign)?
            remainingCopies.get(sign):0;
          if(copies>0){
            remainingCopies.put(sign,copies-1);
            continue; // Proven historical copy; its source remains counted.
          }
        }
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
      // The former signup migration also copied old sales_YYYY-MM-DD
      // summary keys. Do not count them twice when source+clone match.
      if(ignoreExactCopies&&source.contains(k)&&
        source.getInt(k,0)==p.getInt(k,0))continue;
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
  int copiedLegacySaleCount(){
    int copies=0;
    for(BranchRow row:rows)copies+=row.copiedLegacySales;
    return copies;
  }
  boolean hasCopiedLocalBranchData(){
    for(BranchRow row:rows)if(row.hasCopiedLocalData)return true;
    return false;
  }
  void duplicatedDataWarning(){
    if(!hasCopiedLocalBranchData())return;
    boolean hasOriginal=false;
    for(BranchRow row:rows)
      if(BranchAccounts.LEGACY_BRANCH.equals(row.id))hasOriginal=true;
    host.add(host.body,host.text(
      hasOriginal?
        "AMARAN: Salinan rekod lama Branch Utama dikesan dalam cawangan tambahan. "+
        "Resit tempatan yang sama telah dikecualikan daripada pengiraan berganda. "+
        "Rekod Cloud dan stok masih perlu diaudit.":
        "AMARAN: Data tempatan yang sepadan dengan Branch Utama dikesan, "+
        "tetapi dokumen cawangan asal tiada dalam senarai. "+
        "Jumlah jualan belum boleh dianggap tepat; jangan padam atau pulihkan backup.",
      12,host.gold,true),-1,-2);
    host.gap(host.body,8);
    host.action("🔎  AUDIT DATA CAWANGAN (BACA SAHAJA)",
      ()->BranchDataAudit.show(host));
  }
  Day overall(){
    Day all=new Day();
    for(BranchRow row:rows){
      if(row.archived)continue; // Active dashboard excludes permanently closed branches.
      Day one=row.totals();
      all.add(one);
    }
    return all;
  }
  Day overallOn(String selectedDay){
    Day all=new Day();
    for(BranchRow row:rows){
      if(row.archived)continue;
      Day one=row.combined().get(selectedDay);
      if(one!=null)all.add(one);
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
    if(!accounts.owner()){
      host.heading("Akses Owner","Laporan semua cawangan hanya untuk Owner.");return;
    }
    // Subscribe once to the chosen month; show both that month's sales and
    // the selected day's sales without extra Firestore daily listeners.
    host.cashDailyMode=false;
    host.selectedMonth=host.selectedCashDay.substring(0,7);
    final String day=host.selectedCashDay;
    final boolean today=day.equals(host.date());
    final boolean english=host.english();
    host.heading("SALE · OWNER",
      english?"Daily sales and local profit. Choose a date to review history.":
        "Jualan dan keuntungan harian. Pilih tarikh untuk semak sejarah.");
    host.action((english?"📅  SALES DATE: ":"📅  TARIKH JUALAN: ")+day+"  ▼",()->
      host.pickDay(english?"Select sales date":"Pilih tarikh jualan",day,value->{
        host.selectedCashDay=value;
        host.selectedMonth=value.substring(0,7);
        host.draw();
      }));
    if(!today)host.action(english?"↻  BACK TO TODAY":"↻  KEMBALI KE HARI INI",()->{
      host.selectedCashDay=host.date();
      host.selectedMonth=host.selectedCashDay.substring(0,7);
      host.draw();
    });
    host.gap(host.body,7);
    ensureLoaded();
    if(ready())duplicatedDataWarning();
    Day daily=overallOn(day);
    int localSales=host.saleTotalBetween(day,day);
    int localTx=host.transactionCountBetween(day,day);
    int activeBranches=0;BranchRow singleBranch=null;
    for(BranchRow b:rows)if(!b.archived){activeBranches++;singleBranch=b;}
    final BranchRow verifiedSingle=singleBranch;
    boolean overallProfitVerified=ready()&&activeBranches==1&&singleBranch!=null&&
      singleBranch.id.equals(accounts.branchId())&&daily.sales==localSales&&
      daily.transactions==localTx&&!singleBranch.cache;
    LinearLayout overallCard=host.col();
    overallCard.setPadding(host.dp(12),host.dp(14),host.dp(12),host.dp(14));
    overallCard.setBackground(host.borderedCard(true,18));
    host.add(overallCard,host.text(english?"OVERALL · ALL BRANCHES":
      "KESELURUHAN · SEMUA CAWANGAN",15,host.ink,true),-1,-2);
    host.gap(overallCard,5);
    host.add(overallCard,host.text(
      (english?(today?"TODAY":"SELECTED DAY"):(today?"HARI INI":"TARIKH DIPILIH"))+
      " · "+day,11,host.muted,false),-1,-2);
    host.gap(overallCard,10);
    LinearLayout sales=host.row();
    host.metric(sales,english?"TOTAL SALES · ALL BRANCHES  ›":
      "JUMLAH JUALAN · SEMUA CAWANGAN  ›",ready()?rm(daily.sales):"—",host.gold,()->{
        host.cashDailyMode=true;host.activePage=16;host.draw();
      });
    int verifiedProfit=overallProfitVerified?host.businessProfit(day,true):0;
    host.metric(sales,english?"OVERALL NET PROFIT":"UNTUNG BERSIH OVERALL",
      overallProfitVerified?rm(verifiedProfit):"—",
      overallProfitVerified?host.profitColor(verifiedProfit):host.muted,()->{
        if(overallProfitVerified){host.activePage=17;selectedBranchId=verifiedSingle.id;host.draw();}
        else host.message("Untung overall belum boleh disahkan: kos cawangan lain belum lengkap di Cloud.");
      });
    host.add(overallCard,sales,-1,-2);
    host.add(host.body,overallCard,-1,-2);host.gap(host.body,20);
    if(ready()){
      host.add(host.body,host.text(english?"ACTIVE BRANCHES":"CAWANGAN AKTIF",21,host.ink,true),-1,-2);
      host.gap(host.body,11);
      for(BranchRow b:rows){
        if(b.archived)continue;
        Day bd=b.combined().get(day);if(bd==null)bd=new Day();
        final BranchRow chosen=b;
        boolean canProfit=b.id.equals(accounts.branchId())&&bd.sales==localSales&&
          bd.transactions==localTx&&!b.cache;
        LinearLayout branchCard=host.col();
        branchCard.setPadding(host.dp(12),host.dp(13),host.dp(12),host.dp(12));
        branchCard.setBackground(host.borderedCard(canProfit,18));
        LinearLayout branchTitle=host.row();
        TextView name=host.text("🏪  "+b.name,18,host.ink,true);
        branchTitle.addView(name,new LinearLayout.LayoutParams(0,-2,1));
        TextView status=host.text(b.status,10,host.gold,true);
        status.setGravity(Gravity.CENTER);
        status.setPadding(host.dp(9),host.dp(5),host.dp(9),host.dp(5));
        status.setBackground(host.shape(host.surfaceAlt,9));
        host.add(branchTitle,status,-2,-2);
        host.add(branchCard,branchTitle,-1,-2);
        host.gap(branchCard,12);
        LinearLayout branchCards=host.row();
        host.metric(branchCards,english?"BRANCH SALES  ›":"JUALAN CAWANGAN  ›",
          rm(bd.sales),host.gold,()->{
            selectedBranchId=chosen.id;host.cashDailyMode=true;
            host.activePage=17;host.draw();
          });
        int branchProfit=canProfit?host.businessProfit(day,true):0;
        host.metric(branchCards,english?"NET PROFIT  ›":"UNTUNG BERSIH  ›",
          canProfit?rm(branchProfit):"—",
          canProfit?host.profitColor(branchProfit):host.muted,()->{
            selectedBranchId=chosen.id;host.cashDailyMode=true;
            host.activePage=17;host.draw();
          });
        host.add(branchCard,branchCards,-1,-2);
        host.gap(branchCard,6);
        // Existing cloud DAILY snapshot already includes CASH and QR. Showing
        // these figures avoids guessing un-synced costs or outstanding debt.
        LinearLayout methods=host.row();
        host.metric(methods,english?"CASH":"TUNAI",rm(bd.cash),host.ink,null);
        host.metric(methods,"QR / DUITNOW",rm(bd.qr),host.gold,null);
        host.add(branchCard,methods,-1,-2);
        host.gap(branchCard,8);
        host.add(branchCard,new OwnerSalesChart(host,b.combined(),host.selectedMonth),-1,154);
        host.gap(branchCard,9);
        TextView details=host.text(english?"View Details  ›":"Lihat Butiran  ›",
          14,host.gold,true);
        details.setGravity(Gravity.CENTER);
        details.setMinHeight(host.dp(42));
        details.setBackground(host.borderedCard(false,11));
        host.add(branchCard,details,-1,-2);
        details.setOnClickListener(v->{
          selectedBranchId=chosen.id;host.cashDailyMode=true;
          host.activePage=17;host.draw();
        });
        host.add(host.body,branchCard,-1,-2);host.gap(host.body,11);
      }
      if(activeBranches==0)host.add(host.body,host.text("Tiada cawangan aktif.",12,host.muted,false),-1,-2);
      if(!overallProfitVerified)host.add(host.body,host.text(english?
        "Sales are shown live; net profit stays blank until all branch expenses and product costs are verified.":
        "Jualan dipaparkan secara Live. Untung bersih ditanda — jika kos produk dan belanja semua cawangan belum disahkan.",11,host.muted,false),-1,-2);
      host.gap(host.body,9);
    }
    LinearLayout payment=host.row();
    host.metric(payment,english?"CASH":"TUNAI",ready()?rm(daily.cash):"—",host.ink,null);
    host.metric(payment,"QR / DUITNOW  ›",ready()?rm(daily.qr):"—",host.gold,
      ()->showQrHistory(null,day,day));
    host.add(host.body,payment,-1,-2);host.gap(host.body,8);
    if(ready()&&daily.unknown>0){
      LinearLayout uncategorized=host.row();
      host.metric(uncategorized,english?"UNCATEGORIZED PAYMENTS":"BAYARAN BELUM DIKELASKAN",
        rm(daily.unknown),host.gold,null);
      host.add(host.body,uncategorized,-1,-2);host.gap(host.body,8);
    }
    LinearLayout transactions=host.row();
    host.metric(transactions,english?"DAILY TRANSACTIONS":"TRANSAKSI HARIAN",
      ready()?String.valueOf(daily.transactions):"—",host.blue,()->{
        host.cashDailyMode=true;host.activePage=16;host.draw();
      });
    host.add(host.body,transactions,-1,-2);host.gap(host.body,10);

    // Profit uses ONLY active-branch local sales and costs. Cloud sales from
    // staff phones and costs of other branches have not been consolidated.
    int localCost=host.productCostBetween(day,day);
    int localOperations=host.operatingExpenseTotal(day,true);
    int localProfit=host.businessProfit(day,true);
    host.add(host.body,host.text(
      (english?"DAILY LOCAL POS PROFIT · ":"UNTUNG HARIAN POS TEMPATAN · ")+
        accounts.branchName().toUpperCase(Locale.ROOT),13,host.gold,true),-1,-2);
    host.gap(host.body,6);
    LinearLayout profit=host.row();
    host.metric(profit,english?"LOCAL NET PROFIT":"UNTUNG BERSIH TEMPATAN",rm(localProfit),
      host.profitColor(localProfit),null);
    host.add(host.body,profit,-1,-2);host.gap(host.body,7);
    LinearLayout costs=host.row();
    host.metric(costs,english?"PRODUCT COST":"KOS PRODUK",rm(localCost),host.ink,null);
    host.metric(costs,english?"OPERATING COST":"KOS OPERASI",rm(localOperations),host.ink,null);
    host.add(host.body,costs,-1,-2);host.gap(host.body,6);
    host.add(host.body,host.text(english?
      "Local POS sales: "+rm(localSales)+". Profit includes only sales and costs stored on " +
      "this device for "+accounts.branchName()+"; it is not the net profit of all branches.":
      "Jualan POS tempatan: "+rm(localSales)+". Keuntungan ini hanya melibatkan " +
      "jualan dan kos yang direkod pada telefon ini untuk "+accounts.branchName()+
      ", bukan untung bersih semua cawangan.",11,host.muted,false),-1,-2);
    host.gap(host.body,10);
    host.drawProductSalesSummary(day);
    host.action((english?"🔓  OPENING / CLOSING · ":"🔓  BUKA / TUTUP SESI · ")+
      accounts.branchName(),()->{host.activePage=22;host.draw();});
    host.action(english?"🏪  VIEW BRANCHES BY DATE":"🏪  LIHAT CAWANGAN IKUT TARIKH",()->{
      host.cashDailyMode=true;host.activePage=16;host.draw();
    });
    host.gap(host.body,8);
    host.add(host.body,host.text((english?"SUPPLIER · ACTIVE BRANCH: ":
      "PEMBEKAL · CAWANGAN AKTIF: ")+accounts.branchName(),12,host.gold,true),-1,-2);
    host.gap(host.body,5);
    host.action((english?"SUPPLIER PAYMENT · ":"BAYARAN PEMBEKAL · ")+
      host.money(host.supplierDue()),()->host.supplierHub());
    host.action(english?"SUPPLIER PAYMENT HISTORY":"SEJARAH BAYARAN PEMBEKAL",
      ()->host.supplierAllHistory());
    host.gap(host.body,9);
    dataMessage();

    // Monthly totals remain at the bottom; the daily view is always primary.
    host.gap(host.body,12);
    host.add(host.body,host.text((english?"MONTHLY SUMMARY · ":
      "RINGKASAN BULANAN · ")+host.monthLabel(host.selectedMonth),
      13,host.gold,true),-1,-2);
    host.gap(host.body,7);
    Day monthly=overall();
    LinearLayout monthCards=host.row();
    host.metric(monthCards,english?"MONTHLY SALES · ALL BRANCHES":
      "JUALAN BULANAN · SEMUA CAWANGAN",ready()?rm(monthly.sales):"—",
      host.blue,null);
    int monthlyLocalProfit=host.businessProfit(host.selectedMonth,false);
    host.metric(monthCards,english?"MONTHLY LOCAL POS PROFIT":
      "UNTUNG BULANAN POS TEMPATAN",rm(monthlyLocalProfit),
      host.profitColor(monthlyLocalProfit),null);
    host.add(host.body,monthCards,-1,-2);host.gap(host.body,7);
    host.action(english?"📊  VIEW FULL MONTHLY REPORT":
      "📊  LIHAT LAPORAN BULANAN PENUH",()->{
        host.cashDailyMode=false;host.activePage=21;host.draw();
      });
  }
  TreeMap<String,Day> overallDays(){return overallDays(false);}
  TreeMap<String,Day> overallDays(boolean includeArchived){
    TreeMap<String,Day> byDay=new TreeMap<>();
    for(BranchRow branch:rows){
      if(!includeArchived&&branch.archived)continue;
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
    duplicatedDataWarning();
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
      // Downloadable monthly history includes archived branches, even though
      // active in-app charts exclude them.
      OwnerOverallReportPdf.print(host,host.selectedMonth,rows,overallDays(true));
    });
    host.add(host.body,host.text("PECAHAN IKUT BRANCH",13,host.gold,true),-1,-2);
    host.gap(host.body,7);
    for(BranchRow branch:rows){
      if(branch.archived)continue;
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
      if(row.archived)continue;
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
      host.add(host.body,host.text(
        host.english()?"Uncategorized payments: "+rm(total.unknown)+
          " · Review historical records that did not save a payment method.":
          "Bayaran belum dikelaskan: "+rm(total.unknown)+
          " · Semak rekod lama yang tidak menyimpan kaedah pembayaran.",
        12,host.gold,true),-1,-2);
    LinearLayout count=host.row();
    host.metric(count,"BILANGAN TRANSAKSI",String.valueOf(total.transactions),host.ink,null);
    host.add(host.body,count,-1,-2);host.gap(host.body,10);
    host.action("📒  REKOD CLOSING & DUIT KELUAR (CLOUD)",
      ()->showCloudClosingHistory(chosen.id));
    if(chosen.id.equals(accounts.branchId())){
      int branchLocalProfit=host.cashDailyMode?
        host.businessProfit(host.selectedCashDay,true):
        host.businessProfit(host.selectedMonth,false);
      LinearLayout profitBox=host.row();
      host.metric(profitBox,host.cashDailyMode?
          "UNTUNG HARIAN POS TEMPATAN":"UNTUNG BULANAN POS TEMPATAN",
        rm(branchLocalProfit),host.profitColor(branchLocalProfit),null);
      host.add(host.body,profitBox,-1,-2);host.gap(host.body,6);
      host.add(host.body,host.text(host.english()?
          "Costs and profits come only from records on the active branch's phone. "+
          "Cloud sales not saved locally are excluded from this profit.":
          "Kos dan keuntungan hanya berdasarkan rekod pada telefon cawangan aktif. "+
          "Jualan awan yang belum disimpan secara tempatan tidak termasuk dalam keuntungan ini.",
        11,host.muted,false),-1,-2);
      host.gap(host.body,8);
    }else{
      host.add(host.body,host.text(host.english()?
          "Profit for this branch is unavailable because its business costs "+
          "have not been synchronized from the branch's phone.":
          "Keuntungan cawangan ini belum tersedia kerana kos perniagaannya "+
          "belum diselaraskan daripada telefon cawangan tersebut.",
        11,host.muted,false),-1,-2);
      host.gap(host.body,8);
    }
    host.add(host.body,host.text(host.english()?
        "Tap TOTAL SALES above to view sales, QR payments, costs and "+
        "local history for this branch.":
        "Tekan JUMLAH JUALAN di atas untuk melihat laporan jualan, bayaran QR, "+
        "kos dan sejarah tempatan cawangan ini.",12,host.muted,false),-1,-2);
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
        host.profitColor(profit),null);
      host.add(host.body,expenses,-1,-2);host.gap(host.body,8);
      host.add(host.body,host.text("Pengeluaran peribadi: "+rm(personal)+
        "  ·  Baki pembekal perlu bayar: "+rm(host.supplierDue()),12,host.muted,false),-1,-2);
      host.gap(host.body,6);
      host.add(host.body,host.text(host.english()?
        "These costs and profits use only the active branch's local POS "+
        "records, not overall cloud profit.":
        "Kos dan keuntungan di sini hanya berdasarkan rekod POS tempatan "+
        "cawangan aktif, bukan keuntungan keseluruhan awan.",11,host.muted,false),-1,-2);
    }else{
      host.add(host.body,host.text(host.english()?
        "Cloud sales are available for this branch, but local costs and "+
        "profit have not been synchronized from its phone. Costs are hidden "+
        "to avoid incorrectly displaying RM0.":
        "Jualan awan cawangan ini tersedia, tetapi kos dan keuntungan tempatan "+
        "belum diselaraskan daripada telefon cawangan. Nilai kos disembunyikan "+
        "supaya tidak tersalah dipaparkan sebagai RM0.",12,host.muted,false),-1,-2);
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
    showQrHistory(selected,start(),end());
  }
  void showQrHistory(BranchRow selected,String from,String to){
    if(!accounts.owner())return;
    ensureLoaded();
    final String ownerId=accounts.ownerUid();
    final String rangeLabel=from.equals(to)?from:period();
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
        host.darkBuilder().setTitle("REKOD QR · "+rangeLabel)
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
