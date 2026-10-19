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
    stop();
    ensureLoaded();
  }
  void ensureLoaded(){
    if(!accounts.owner())return;
    final String uid=accounts.ownerUid();
    final String from=start(),to=end(),scope=uid+":"+from+":"+to;
    if(scope.equals(subscriptionKey)&&branchListener!=null)return;
    stop();subscriptionKey=scope;
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
    if(host.activePage==3||host.activePage==16||host.activePage==17)
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
    host.heading("DUIT / CASHFLOW · OVERALL",
      "Keseluruhan jualan semua branch. Tekan Jumlah Jualan untuk pecahan mengikut branch.");
    modeSelector();
    ensureLoaded();
    host.action("↻  REFRESH JUALAN SEMUA BRANCH",()->refresh());
    host.add(host.body,host.text("OVERALL · "+period(),12,host.gold,true),-1,-2);
    host.gap(host.body,7);
    Day total=overall();
    String label=host.cashDailyMode?"JUMLAH JUALAN HARI INI  ›":"JUMLAH JUALAN BULAN INI  ›";
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
    host.action("🧾  REKOD BAYARAN QR / TNG / DUITNOW  ›",
      ()->showQrHistory(null));
    LinearLayout tx=host.row();
    host.metric(tx,"TRANSAKSI SEMUA BRANCH",ready()?String.valueOf(total.transactions):"—",
      host.blue,()->{host.activePage=16;host.draw();});
    host.add(host.body,tx,-1,-2);host.gap(host.body,9);
    host.action("LIHAT JUALAN MENGIKUT BRANCH  ›",()->{host.activePage=16;host.draw();});
    host.gap(host.body,8);
    dataMessage();
  }
  void drawBranchList(){
    if(!accounts.owner()){host.heading("Akses ditolak","Owner sahaja.");return;}
    ensureLoaded();
    host.heading("Jualan Mengikut Branch",period()+" · Pilih branch untuk lihat laporan lengkap.");
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
    host.heading("Jualan · "+chosen.name,period()+"  ·  "+chosen.status);
    host.action("‹  Kembali ke Jualan Semua Branch",()->{host.activePage=16;host.draw();});
    if(!branch.seen){dataMessage();return;}
    Day total=branch.totals();
    LinearLayout main=host.row();
    host.metric(main,"JUMLAH JUALAN BRANCH",rm(total.sales),host.blue,null);
    host.add(host.body,main,-1,-2);host.gap(host.body,9);
    LinearLayout pay=host.row();
    host.metric(pay,"TUNAI",rm(total.cash),host.gold,null);
    host.metric(pay,"QR / DUITNOW  ›",rm(total.qr),host.blue,
      ()->showQrHistory(chosen));
    host.add(host.body,pay,-1,-2);host.gap(host.body,9);
    host.action("🧾  REKOD TRANSAKSI QR · "+chosen.name,()->showQrHistory(chosen));
    if(total.unknown>0)
      host.add(host.body,host.text("Bayaran belum dikelaskan: "+rm(total.unknown)+
        " · Semak rekod lama yang tidak menyimpan kaedah pembayaran.",12,host.gold,true),-1,-2);
    LinearLayout count=host.row();
    host.metric(count,"BILANGAN TRANSAKSI",String.valueOf(total.transactions),host.ink,null);
    host.add(host.body,count,-1,-2);host.gap(host.body,10);
    host.add(host.body,host.text("PECAHAN JUALAN MENGIKUT HARI",12,host.gold,true),-1,-2);
    host.gap(host.body,7);
    TreeMap<String,Day> each=branch.combined();
    if(each.isEmpty())
      host.add(host.body,host.text("Belum ada rekod jualan dalam tempoh ini.",12,host.muted,false),-1,-2);
    else for(Map.Entry<String,Day> item:each.descendingMap().entrySet()){
      Day day=item.getValue();
      host.action(item.getKey()+"  ·  "+rm(day.sales)+
        "\n  Tunai "+rm(day.cash)+"   QR "+rm(day.qr)+
        "\n  "+day.transactions+" transaksi · "+(day.cloud?"Cloud Live":"Rekod lama telefon"),
        ()->showDay(item.getKey(),day,chosen.name));
    }
    if(branch.id.equals(accounts.branchId())){
      host.gap(host.body,8);
      host.action("📊  LAPORAN LAMA / KOS & UNTUNG BRANCH INI",()->{
        host.activePage=18;host.draw();
      });
    }
    host.gap(host.body,8);
    dataMessage();
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
