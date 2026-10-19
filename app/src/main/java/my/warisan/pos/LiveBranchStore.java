package my.warisan.pos;

import android.content.SharedPreferences;
import android.widget.EditText;
import android.widget.LinearLayout;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.MetadataChanges;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Branch cloud operations. All live sales use Firestore transactions and do not
 * proceed offline; other devices receive snapshot listeners. The legacy records
 * are never deleted or overwritten by cloud initialization.
 */
final class LiveBranchStore {
  interface Done {void apply();}
  private final MainActivity host;
  private final FirebaseFirestore db;
  private final BranchAccounts accounts;
  private ListenerRegistration inventoryListener,dayListener;
  private String activeKey="",activeDay="";
  private volatile boolean enabled=false,serverConfirmed=false,busy=false;
  private volatile List<Long> cloudStock=new ArrayList<>();
  private volatile long daySales=0,dayCash=0,dayQr=0,dayTransactions=0;
  private String cloudBranchName="";
  LiveBranchStore(MainActivity host){
    this.host=host;this.db=host.firestoreForStaff();this.accounts=host.branchAccounts;
  }
  boolean enabled(){return enabled&&accounts.active();}
  boolean usable(){return enabled()&&serverConfirmed;}
  long sales(){return daySales;}
  long cash(){return dayCash;}
  long qr(){return dayQr;}
  long transactions(){return dayTransactions;}
  String day(){return activeDay;}
  int stock(int i){
    if(i<0||i>=cloudStock.size())return 0;
    long n=cloudStock.get(i);return (int)Math.max(0,Math.min(Integer.MAX_VALUE,n));
  }
  DocumentReference branch(){
    return db.collection("workspaces").document(accounts.ownerUid())
      .collection("branches").document(accounts.branchId());
  }
  DocumentReference inventory(){return branch().collection("live").document("current");}
  DocumentReference orders(String id){return branch().collection("liveOrders").document(id);}
  DocumentReference daily(String day){return branch().collection("daily").document(day);}
  void stop(){
    if(inventoryListener!=null){inventoryListener.remove();inventoryListener=null;}
    if(dayListener!=null){dayListener.remove();dayListener=null;}
    activeKey="";activeDay="";enabled=false;serverConfirmed=false;busy=false;
    cloudStock=new ArrayList<>();
  }
  void attach(){
    if(!accounts.active()){stop();return;}
    String key=accounts.ownerUid()+":"+accounts.branchId();
    if(!key.equals(activeKey)){
      stop();activeKey=key;
      inventoryListener=inventory().addSnapshotListener(MetadataChanges.INCLUDE,(snapshot,error)->{
        if(!key.equals(activeKey))return;
        if(error!=null){serverConfirmed=false;host.message("Live inventory: "+error.getMessage());return;}
        if(snapshot==null||!snapshot.exists()){
          enabled=false;serverConfirmed=false;return;
        }
        enabled=true;
        serverConfirmed=!snapshot.getMetadata().isFromCache()&&!snapshot.getMetadata().hasPendingWrites();
        Object raw=snapshot.get("stock");
        ArrayList<Long> values=new ArrayList<>();
        if(raw instanceof List)for(Object v:(List<?>)raw)
          values.add(v instanceof Number?Math.max(0,((Number)v).longValue()):0L);
        cloudStock=values;
        cloudBranchName=snapshot.getString("branchName");
        // Staff menu is refreshed from published public menu values, not supplier costs.
        if(accounts.staff()){
          SharedPreferences preferences=accounts.posPrefs();
          List<?> p=(List<?>)snapshot.get("menuPrices");
          String marker=snapshot.getString("menuVersion");
          if(p!=null&&marker!=null&&!marker.equals(preferences.getString("live_menu_version",""))){
            SharedPreferences.Editor edit=preferences.edit();
            for(int i=0;i<p.size();i++)if(p.get(i) instanceof Number)
              edit.putInt("price_"+i,((Number)p.get(i)).intValue());
            String custom=snapshot.getString("customMenu");
            if(custom!=null)edit.putString("custom_menu",custom);
            edit.putString("live_menu_version",marker).commit();
            host.loadMenu();
          }
        }
        if(host.activePage==0||host.activePage==1||host.activePage==12)host.draw();
      });
    }
    attachDay();
  }
  void attachDay(){
    if(!accounts.active())return;
    String newDay=host.businessDay();
    if(newDay.equals(activeDay))return;
    if(dayListener!=null)dayListener.remove();
    activeDay=newDay;daySales=0;dayCash=0;dayQr=0;dayTransactions=0;
    String key=activeKey,selected=newDay;
    dayListener=daily(selected).addSnapshotListener((snap,error)->{
      if(!selected.equals(activeDay)||!key.equals(activeKey)||snap==null||error!=null)return;
      Long sale=snap.getLong("sales"),cash=snap.getLong("cash"),qr=snap.getLong("qr"),tx=snap.getLong("transactions");
      daySales=sale==null?0:sale;dayCash=cash==null?0:cash;
      dayQr=qr==null?0:qr;dayTransactions=tx==null?0:tx;
      if(host.activePage==12)host.draw();
    });
  }
  void activate(){
    if(!accounts.owner()){host.message("Owner sahaja boleh aktifkan Live.");return;}
    if(enabled()){host.message("Branch Live telah aktif. Stok dikawal oleh server.");return;}
    host.darkBuilder().setTitle("Aktifkan Live untuk branch?")
      .setMessage("Stok sedia ada akan diambil SEKALI sebagai baki awal cloud. Data POS asal tidak diubah. Selepas itu transaksi kaunter Live memerlukan internet.")
      .setPositiveButton("AKTIFKAN",(dlg,which)->{
        final ArrayList<Long> initial=new ArrayList<>(),priceList=new ArrayList<>();
        for(int i=0;i<host.names.length;i++){
          initial.add((long)(host.unlimited(i)?0:host.stock(i)));
          priceList.add((long)host.prices[i]);
        }
        String name=accounts.branchName();
        Map<String,Object> initialDoc=new HashMap<>();
        initialDoc.put("stock",initial);initialDoc.put("menuPrices",priceList);
        initialDoc.put("customMenu",host.posPrefs().getString("custom_menu","[]"));
        initialDoc.put("branchName",name);
        initialDoc.put("menuVersion",UUID.randomUUID().toString());
        initialDoc.put("ownerUid",accounts.ownerUid());
        initialDoc.put("createdAt",FieldValue.serverTimestamp());
        db.runTransaction(transaction->{
          DocumentSnapshot old=transaction.get(inventory());
          if(old.exists())throw new IllegalStateException("Branch sudah diaktifkan pada telefon lain");
          transaction.set(inventory(),initialDoc);return null;
        }).addOnSuccessListener(result->{
          host.message("Branch Live diaktifkan. Rekod lama masih ada.");
          attach();
        }).addOnFailureListener(error->host.message("Aktifkan Live gagal: "+error.getMessage()));
      }).setNegativeButton("BATAL",null).show();
  }
  void publishMenu(){
    if(!accounts.owner()||!usable()){host.message("Aktifkan branch Live dahulu.");return;}
    ArrayList<Long> prices=new ArrayList<>();
    for(int v:host.prices)prices.add((long)v);
    String custom=host.posPrefs().getString("custom_menu","[]");
    Map<String,Object> update=new HashMap<>();
    update.put("menuPrices",prices);update.put("customMenu",custom);
    update.put("menuVersion",UUID.randomUUID().toString());
    inventory().update(update).addOnSuccessListener(v->host.message("Menu Live dikemas kini."))
      .addOnFailureListener(e->host.message("Menu Live gagal: "+e.getMessage()));
  }
  private ArrayList<Long> readStock(DocumentSnapshot s){
    Object raw=s.get("stock");if(!(raw instanceof List))throw new IllegalStateException("Stok cloud tidak sah");
    ArrayList<Long> out=new ArrayList<>();
    for(Object v:(List<?>)raw){
      if(!(v instanceof Number))throw new IllegalStateException("Stok cloud tidak sah");
      out.add(((Number)v).longValue());
    }
    return out;
  }
  void reserve(int[] qty,int due,String day,String receiptId,Done onSuccess){
    if(!usable()||busy){host.message("Live belum siap / tiada internet. Jangan buat order offline.");return;}
    busy=true;
    DocumentReference ref=orders(receiptId),inv=inventory();
    ArrayList<Long> quantities=new ArrayList<>();
    for(int n:qty)quantities.add((long)n);
    db.runTransaction(tx->{
      DocumentSnapshot stockDoc=tx.get(inv),order=tx.get(ref);
      if(order.exists())return null; // idempotent retry: preserve existing reservation
      if(!stockDoc.exists())throw new IllegalStateException("Stok Live belum diaktifkan");
      ArrayList<Long> remaining=readStock(stockDoc);
      if(remaining.size()<quantities.size())throw new IllegalStateException("Menu Live tidak sepadan");
      for(int i=0;i<quantities.size();i++){
        long quantity=quantities.get(i);
        if(quantity<0)throw new IllegalStateException("Kuantiti tidak sah");
        if(i!=4){
          if(remaining.get(i)<quantity)throw new IllegalStateException("Stok "+(i+1)+" tidak mencukupi");
          remaining.set(i,remaining.get(i)-quantity);
        }
      }
      Map<String,Object> orderData=new HashMap<>();
      orderData.put("ownerUid",accounts.ownerUid());orderData.put("branchId",accounts.branchId());
      orderData.put("actorUid",accounts.user().getUid());orderData.put("deviceOrderId",receiptId);
      orderData.put("day",day);orderData.put("total",due);
      orderData.put("qty",quantities);orderData.put("status","PENDING");
      orderData.put("createdAt",FieldValue.serverTimestamp());
      tx.update(inv,"stock",remaining,"lastOrderId",receiptId,"lastStockAction","RESERVE",
        "updatedAt",FieldValue.serverTimestamp());
      tx.set(ref,orderData);return null;
    }).addOnSuccessListener(v->{busy=false;onSuccess.apply();})
      .addOnFailureListener(e->{busy=false;host.message("Order Live gagal: "+e.getMessage());});
  }
  void cancel(String id,Done onSuccess){
    if(!usable()||busy){host.message("Live offline. Order belum dibatalkan.");return;}
    busy=true;DocumentReference ref=orders(id),inv=inventory();
    db.runTransaction(tx->{
      DocumentSnapshot stockDoc=tx.get(inv),order=tx.get(ref);
      if(!order.exists())throw new IllegalStateException("Order Cloud tidak ditemui");
      String status=order.getString("status");
      if("CANCELLED".equals(status))return null;
      if(!"PENDING".equals(status))throw new IllegalStateException("Order sudah dibayar");
      ArrayList<Long> remaining=readStock(stockDoc);
      List<?> qty=(List<?>)order.get("qty");
      for(int i=0;i<qty.size()&&i<remaining.size();i++)if(i!=4)
        remaining.set(i,remaining.get(i)+((Number)qty.get(i)).longValue());
      tx.update(inv,"stock",remaining,"lastOrderId",id,"lastStockAction","CANCEL",
        "updatedAt",FieldValue.serverTimestamp());
      tx.update(ref,"status","CANCELLED","cancelledAt",FieldValue.serverTimestamp());
      return null;
    }).addOnSuccessListener(v->{busy=false;onSuccess.apply();})
      .addOnFailureListener(e->{busy=false;host.message("Batal Live gagal: "+e.getMessage());});
  }
  void paid(String id,String method,Done onSuccess){
    if(!usable()||busy){host.message("Live offline. Bayaran belum direkod. Jangan tekan berulang kali.");return;}
    busy=true;DocumentReference ref=orders(id);
    db.runTransaction(tx->{
      DocumentSnapshot doc=tx.get(ref);
      if(!doc.exists())throw new IllegalStateException("Order Cloud tiada");
      String status=doc.getString("status");
      if("PAID".equals(status))return null;
      if(!"PENDING".equals(status))throw new IllegalStateException("Order bukan PENDING");
      String orderDay=doc.getString("day");
      if(orderDay==null||!orderDay.matches("\\d{4}-\\d{2}-\\d{2}"))throw new IllegalStateException("Tarikh order tidak sah");
      DocumentReference report=daily(orderDay);
      DocumentSnapshot reportData=tx.get(report);
      long value=doc.getLong("total")==null?0:doc.getLong("total");
      Map<String,Object> totals=new HashMap<>();
      long sales=reportData.exists()&&reportData.getLong("sales")!=null?reportData.getLong("sales"):0;
      long cash=reportData.exists()&&reportData.getLong("cash")!=null?reportData.getLong("cash"):0;
      long qr=reportData.exists()&&reportData.getLong("qr")!=null?reportData.getLong("qr"):0;
      long count=reportData.exists()&&reportData.getLong("transactions")!=null?reportData.getLong("transactions"):0;
      totals.put("sales",sales+value);totals.put("cash",cash+("Tunai".equals(method)?value:0L));
      totals.put("qr",qr+("Tunai".equals(method)?0L:value));
      totals.put("transactions",count+1);totals.put("businessDay",orderDay);
      totals.put("lastPaymentId",id);
      totals.put("updatedAt",FieldValue.serverTimestamp());
      tx.set(report,totals,com.google.firebase.firestore.SetOptions.merge());
      tx.update(ref,"status","PAID","method",method,"paidAt",FieldValue.serverTimestamp());
      return null;
    }).addOnSuccessListener(v->{busy=false;attachDay();onSuccess.apply();})
      .addOnFailureListener(e->{busy=false;host.message("Bayaran Live gagal: "+e.getMessage());});
  }
  void addStock(int item,int count,Done onSuccess){
    if(!accounts.owner()||!usable()||busy){host.message("Owner perlu Live online untuk restock.");return;}
    if(count<=0){host.message("Stok mesti lebih daripada 0.");return;}
    busy=true;DocumentReference ref=inventory();
    db.runTransaction(tx->{
      DocumentSnapshot snap=tx.get(ref);ArrayList<Long> remain=readStock(snap);
      if(item<0||item>=remain.size())throw new IllegalStateException("Menu tak sepadan");
      remain.set(item,remain.get(item)+count);
      tx.update(ref,"stock",remain,"updatedAt",FieldValue.serverTimestamp());
      return null;
    }).addOnSuccessListener(v->{busy=false;onSuccess.apply();})
      .addOnFailureListener(e->{busy=false;host.message("Restock Live gagal: "+e.getMessage());});
  }
  void restockDialog(int id){
    if(!accounts.owner()){host.message("Owner sahaja.");return;}
    EditText input=new EditText(host);host.styleInput(input);
    input.setHint("Jumlah stok baru");input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
    host.darkBuilder().setTitle("Restock Live · "+host.names[id])
      .setMessage("Stok akan dikemas kini secara atomik untuk semua telefon. Rekod kos pembelian disimpan di telefon Owner.")
      .setView(input).setPositiveButton("TAMBAH",(d,w)->{
        try{
          int amount=Integer.parseInt(input.getText().toString().trim());
          if(amount<=0||amount>100000)throw new NumberFormatException();
          addStock(id,amount,()->host.applyLiveStockRestock(id,amount));
        }catch(Exception e){host.message("Isi jumlah stok yang sah.");}
      }).setNegativeButton("BATAL",null).show();
  }
  void refund(String cloudId,String approvalId,Done onSuccess){
    if(!usable()||busy){host.message("Refund Live memerlukan internet.");return;}
    if(!accounts.owner()&&(approvalId==null||approvalId.isEmpty()||"OWNER".equals(approvalId))){
      host.message("Kelulusan refund diperlukan.");return;
    }
    busy=true;DocumentReference orderRef=orders(cloudId);
    db.runTransaction(tx->{
      DocumentSnapshot order=tx.get(orderRef);
      if(!order.exists())throw new IllegalStateException("Order asal tiada");
      String status=order.getString("status");
      if("REFUNDED".equals(status))return null;
      if(!"PAID".equals(status))throw new IllegalStateException("Order belum dibayar atau sudah dibatalkan");
      DocumentReference dayRef=daily(order.getString("day"));
      DocumentSnapshot today=tx.get(dayRef);
      if(!today.exists())throw new IllegalStateException("Laporan jualan cloud tiada");
      long total=order.getLong("total");
      String method=order.getString("method");
      long sales=today.getLong("sales")==null?0:today.getLong("sales");
      long cash=today.getLong("cash")==null?0:today.getLong("cash");
      long qr=today.getLong("qr")==null?0:today.getLong("qr");
      if(sales<total)throw new IllegalStateException("Baki jualan cloud tak sepadan");
      Map<String,Object> report=new HashMap<>();
      report.put("sales",sales-total);
      report.put("cash",Math.max(0,cash-("Tunai".equals(method)?total:0)));
      report.put("qr",Math.max(0,qr-("Tunai".equals(method)?0:total)));
      report.put("lastRefundId",cloudId);report.put("updatedAt",FieldValue.serverTimestamp());
      tx.set(dayRef,report,com.google.firebase.firestore.SetOptions.merge());
      tx.update(orderRef,"status","REFUNDED","refundApprovalId",approvalId,
        "refundBy",accounts.user().getUid(),"refundedAt",FieldValue.serverTimestamp());
      return null;
    }).addOnSuccessListener(v->{busy=false;onSuccess.apply();})
      .addOnFailureListener(e->{busy=false;host.message("Refund Live gagal: "+e.getMessage());});
  }
  void session(boolean open,String day,int amount){
    if(!accounts.active()||!usable())return;
    DocumentReference doc=branch().collection("liveSessions").document(day);
    Map<String,Object> state=new HashMap<>();
    state.put("day",day);state.put("branchId",accounts.branchId());
    state.put("ownerUid",accounts.ownerUid());
    if(open){
      state.put("openedBy",accounts.user().getUid());
      state.put("opening",amount);state.put("open",true);
    }else{
      state.put("closedBy",accounts.user().getUid());
      state.put("closing",amount);state.put("open",false);
    }
    state.put("updatedAt",FieldValue.serverTimestamp());
    doc.set(state,com.google.firebase.firestore.SetOptions.merge())
      .addOnFailureListener(e->host.message("Sesi lokal direkod, tetapi sync Opening/Closing gagal: "+e.getMessage()));
  }
  void showMonth(String month){
    if(!accounts.owner()||!accounts.active())return;
    branch().collection("daily")
      .whereGreaterThanOrEqualTo(com.google.firebase.firestore.FieldPath.documentId(),month+"-01")
      .whereLessThanOrEqualTo(com.google.firebase.firestore.FieldPath.documentId(),month+"-31")
      .get().addOnSuccessListener(results->{
        long sale=0,cash=0,qr=0,tx=0;
        StringBuilder byDay=new StringBuilder();
        for(QueryDocumentSnapshot doc:results){
          long s=doc.getLong("sales")==null?0:doc.getLong("sales");
          sale+=s;cash+=doc.getLong("cash")==null?0:doc.getLong("cash");
          qr+=doc.getLong("qr")==null?0:doc.getLong("qr");
          tx+=doc.getLong("transactions")==null?0:doc.getLong("transactions");
          byDay.append(doc.getId()).append("   ").append(host.money((int)s)).append("\n");
        }
        host.darkBuilder().setTitle("LAPORAN LIVE · "+month)
          .setMessage(accounts.branchName()+"\nJualan: "+host.money((int)sale)+
            "\nTunai: "+host.money((int)cash)+"\nQR: "+host.money((int)qr)+
            "\nTransaksi: "+tx+"\n\n"+(byDay.length()==0?"Belum ada rekod live":byDay.toString()))
          .setPositiveButton("TUTUP",null).show();
      }).addOnFailureListener(e->host.message("Report Live gagal: "+e.getMessage()));
  }
  void showAllBranches(){
    if(!accounts.owner())return;
    final String owner=accounts.ownerUid(),day=host.businessDay();
    db.collection("workspaces").document(owner).collection("branches").get()
      .addOnSuccessListener(branches->{
        List<com.google.android.gms.tasks.Task<DocumentSnapshot>> tasks=new ArrayList<>();
        List<String> labels=new ArrayList<>();
        for(QueryDocumentSnapshot location:branches){
          labels.add(location.getString("name")==null?location.getId():location.getString("name"));
          tasks.add(location.getReference().collection("daily").document(day).get());
        }
        if(tasks.isEmpty()){host.message("Tiada branch.");return;}
        com.google.android.gms.tasks.Tasks.whenAllSuccess(tasks).addOnSuccessListener(data->{
          StringBuilder result=new StringBuilder("Tarikh "+day+"\n\n");
          long overall=0;
          for(int i=0;i<data.size();i++){
            DocumentSnapshot snap=(DocumentSnapshot)data.get(i);
            long sale=snap.getLong("sales")==null?0:snap.getLong("sales");
            long tx=snap.getLong("transactions")==null?0:snap.getLong("transactions");
            overall+=sale;
            result.append(labels.get(i)).append("\n")
              .append(host.money((int)sale)).append(" · ")
              .append(tx).append(" transaksi\n\n");
          }
          result.append("JUMLAH LIVE: ").append(host.money((int)overall));
          host.darkBuilder().setTitle("SEMUA BRANCH · LIVE")
            .setMessage(result.toString()).setPositiveButton("TUTUP",null).show();
        }).addOnFailureListener(e->host.message("Ada branch belum dapat disemak: "+e.getMessage()));
      }).addOnFailureListener(e->host.message("Gagal senarai branch: "+e.getMessage()));
  }
  void showDashboard(){
    if(!accounts.active()){host.message("Log masuk Owner dahulu");return;}
    if(!enabled()){if(accounts.owner())activate();else host.message("Owner belum aktifkan Live.");return;}
    String report=accounts.branchName()+"\n\n"+
      "Jualan "+host.money((int)daySales)+"\n"+
      "Tunai "+host.money((int)dayCash)+"\n"+
      "QR "+host.money((int)dayQr)+"\n"+
      "Transaksi "+dayTransactions+"\n"+
      "Status: "+(serverConfirmed?"Cloud ONLINE":"Menunggu server / offline")+
      "\n\nStok Live:\n";
    StringBuilder text=new StringBuilder(report);
    for(int i=0;i<cloudStock.size()&&i<host.names.length;i++)
      if(!host.unlimited(i))text.append(host.names[i]).append(": ").append(stock(i)).append("\n");
    host.darkBuilder().setTitle("LIVE BRANCH · "+activeDay).setMessage(text.toString())
      .setPositiveButton("KEMAS KINI MENU",(d,w)->publishMenu())
      .setNegativeButton("TUTUP",null).show();
  }
}
