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
    long sales, cash, qr, transactions;
    boolean cloud;
    Day(){}
    Day(long s,long c,long q,long t,boolean isCloud){
      sales=s;cash=c;qr=q;transactions=t;cloud=isCloud;
    }
  }
  static final class BranchRow {
    final String id,name,status;
    final TreeMap<String,Day> remote = new TreeMap<>();
    TreeMap<String,Day> local = new TreeMap<>();
    boolean seen=false,cache=false;
    BranchRow(String id,String name,String status){
      this.id=id;this.name=name;this.status=status;
    }
    TreeMap<String,Day> combined(){
      TreeMap<String,Day> merged=new TreeMap<>(local);
      merged.putAll(remote); // ONE source per day; server is authoritative when present
      return merged;
    }
    Day totals(){
      Day total=new Day();
      for(Day d:combined().values()){
        total.sales+=d.sales;total.cash+=d.cash;
        total.qr+=d.qr;total.transactions+=d.transactions;
      }
      return total;
    }
    boolean hasHistoricalFallback(){
      for(String day:local.keySet())if(!remote.containsKey(day))return true;
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
          row.local=readLocalDays(row.id,from,to);
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
  TreeMap<String,Day> readLocalDays(String branchId,String from,String to){
    TreeMap<String,Day> result=new TreeMap<>();
    SharedPreferences p=BranchAccounts.LEGACY_BRANCH.equals(branchId)?
      host.getPreferences(0):
      host.getSharedPreferences("warisan_branch_"+branchId,0);
    try{
      JSONArray sales=new JSONArray(p.getString("stock_sales","[]"));
      for(int i=0;i<sales.length();i++){
        JSONObject receipt=sales.optJSONObject(i);
        if(receipt==null||receipt.optBoolean("refunded",false))continue;
        String day=host.entryDay(receipt);
        if(day.compareTo(from)<0||day.compareTo(to)>0)continue;
        long value=host.saleTotal(receipt);
        Day d=result.get(day);
        if(d==null){d=new Day();result.put(day,d);}
        d.sales+=value;d.transactions++;
        String method=receipt.optString("method","").toLowerCase(Locale.ROOT);
        if(method.contains("tunai")||method.equals("cash"))d.cash+=value;
        else if(method.contains("qr")||method.contains("duitnow")||method.contains("tng"))
          d.qr+=value;
      }
    }catch(Exception ignored){
      // Never erase or modify the old POS preferences on a read failure.
    }
    // Very old Warisan POS saved only day totals, not per-sale receipts.
    for(String k:p.getAll().keySet()){
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
      all.sales+=one.sales;all.cash+=one.cash;
      all.qr+=one.qr;all.transactions+=one.transactions;
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
        "Jumlah tidak menggandakan rekod telefon dan Cloud pada hari sama. "+
        "Rekod lama yang hanya ada pada telefon lain belum semestinya diselaraskan. "+
        "Untung bersih memerlukan kos setiap branch; angka di sini ialah jualan kasar.",
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
    host.metric(pair,"QR",ready()?rm(total.qr):"—",host.gold,null);
    host.add(host.body,pair,-1,-2);host.gap(host.body,9);
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
    host.metric(pay,"QR",rm(total.qr),host.blue,null);
    host.add(host.body,pay,-1,-2);host.gap(host.body,9);
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
  void showDay(String day,Day value,String branchName){
    host.darkBuilder().setTitle("Jualan "+branchName+" · "+day)
      .setMessage("Jumlah jualan: "+rm(value.sales)+
        "\nTunai: "+rm(value.cash)+"\nQR: "+rm(value.qr)+
        "\nTransaksi: "+value.transactions+
        "\nSumber: "+(value.cloud?"Firebase Live":"Rekod lama telefon Owner")+
        "\n\nRekod lama mungkin tidak mempunyai pecahan Tunai/QR lengkap.")
      .setPositiveButton("TUTUP",null).show();
  }
}
