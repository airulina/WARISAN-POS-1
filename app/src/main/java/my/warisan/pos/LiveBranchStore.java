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
import com.google.firebase.firestore.DocumentChange;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.text.SimpleDateFormat;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

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
  private ListenerRegistration inventoryListener,dayListener,orderListener;
  private String activeKey="",activeDay="";
  private volatile boolean enabled=false,serverConfirmed=false,busy=false,inventoryChecked=false;
  private volatile List<Long> cloudStock=new ArrayList<>();
  private volatile long daySales=0,dayCash=0,dayQr=0,dayTransactions=0;
  private String cloudBranchName="";
  private final String deviceId;
  LiveBranchStore(MainActivity host){
    this.host=host;this.db=host.firestoreForStaff();this.accounts=host.branchAccounts;
    SharedPreferences settings=host.getSharedPreferences("warisan_identity",0);
    String device=settings.getString("device_uid","");
    if(device.isEmpty()){
      device=UUID.randomUUID().toString();settings.edit().putString("device_uid",device).commit();
    }
    deviceId=device;
  }
  boolean enabled(){return enabled&&accounts.active();}
  boolean usable(){return enabled()&&serverConfirmed;}
  boolean inventoryChecked(){return inventoryChecked;}
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
    clearOwnerOpeningListeners();
    if(inventoryListener!=null){inventoryListener.remove();inventoryListener=null;}
    if(dayListener!=null){dayListener.remove();dayListener=null;}
    if(orderListener!=null){orderListener.remove();orderListener=null;}
    activeKey="";activeDay="";enabled=false;serverConfirmed=false;busy=false;
    inventoryChecked=false;
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
        if(snapshot==null)return;
        if(!snapshot.getMetadata().isFromCache())inventoryChecked=true;
        if(!snapshot.exists()){
          enabled=false;serverConfirmed=false;
          if(host.activePage==13&&inventoryChecked)host.draw();
          return;
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
            Object hidden=snapshot.get("menuHidden");
            if(hidden instanceof List){
              List<?> flags=(List<?>)hidden;
              for(int i=0;i<flags.size();i++)
                edit.putBoolean("menu_hidden_"+i,Boolean.TRUE.equals(flags.get(i)));
            }
            edit.putString("live_menu_version",marker).commit();
            host.loadMenu();
          }
        }
        if(host.activePage==0||host.activePage==1||host.activePage==12||host.activePage==13)host.draw();
      });
      recoverPendingOrders();
      reconcileLocalPending();
    }
    attachDay();
  }
  void attachDay(){
    if(!accounts.active())return;
    String newDay=host.businessDay();
    if(newDay.equals(activeDay))return;
    if(dayListener!=null)dayListener.remove();
    if(orderListener!=null){orderListener.remove();orderListener=null;}
    activeDay=newDay;daySales=0;dayCash=0;dayQr=0;dayTransactions=0;
    String key=activeKey,selected=newDay;
    dayListener=daily(selected).addSnapshotListener((snap,error)->{
      if(!selected.equals(activeDay)||!key.equals(activeKey)||snap==null||error!=null)return;
      Long sale=snap.getLong("sales"),cash=snap.getLong("cash"),qr=snap.getLong("qr"),tx=snap.getLong("transactions");
      daySales=sale==null?0:sale;dayCash=cash==null?0:cash;
      dayQr=qr==null?0:qr;dayTransactions=tx==null?0:tx;
      if(host.activePage==12)host.draw();
    });
    // Owner's existing local report also receives confirmed sales from workers.
    // Only orders from OTHER devices are merged; we never replace old records.
    if(accounts.owner()){
      orderListener=branch().collection("liveOrders")
        .whereEqualTo("day",selected)
        .addSnapshotListener((results,error)->{
          if(error!=null||results==null||!selected.equals(activeDay)||!key.equals(activeKey))return;
          boolean changed=false;
          for(DocumentChange diff:results.getDocumentChanges()){
            if(mergeRemoteOrder(diff.getDocument()))changed=true;
          }
          if(changed&&(host.activePage==1||host.activePage==2||host.activePage==3))host.draw();
        });
    }
  }
  boolean mergeRemoteOrder(DocumentSnapshot remote){
    if(!accounts.owner())return false;
    String status=remote.getString("status");
    if(!"PAID".equals(status)&&!"REFUNDED".equals(status))return false;
    if(deviceId.equals(remote.getString("deviceId")))return false;
    String id=remote.getId(),day=remote.getString("day");
    if(day==null||!day.matches("\\d{4}-\\d{2}-\\d{2}"))return false;
    try{
      JSONArray sales=host.entries("stock_sales");
      for(int i=0;i<sales.length();i++){
        JSONObject old=sales.optJSONObject(i);
        if(old==null||!id.equals(old.optString("liveOrderId","")))continue;
        if("REFUNDED".equals(status)&&!old.optBoolean("refunded",false)){
          old.put("refunded",true);old.put("refundTime",host.timestamp());
          old.put("refundApprovalId",remote.getString("refundApprovalId"));
          sales.put(i,old);
          int amount=host.saleTotal(old);
          return host.posPrefs().edit().putString("stock_sales",sales.toString())
            .putInt("sales_"+day,Math.max(0,host.posPrefs().getInt("sales_"+day,0)-amount)).commit();
        }
        return false;
      }
      List<?> quantities=(List<?>)remote.get("qty");
      if(quantities==null||quantities.size()>host.names.length)return false;
      JSONObject sale=new JSONObject();JSONArray q=new JSONArray();
      int cost=0;
      for(int i=0;i<host.names.length;i++){
        int amount=i<quantities.size()?((Number)quantities.get(i)).intValue():0;
        q.put(amount);
        if(amount>0&&!host.unlimited(i))cost+=host.restockCost(i,amount);
      }
      String stamp=host.timestamp();
      com.google.firebase.Timestamp started=remote.getTimestamp("createdAt");
      if(started!=null)stamp=new SimpleDateFormat("dd/MM/yyyy HH:mm",Locale.US).format(started.toDate());
      int amount=remote.getLong("total")==null?0:remote.getLong("total").intValue();
      sale.put("time",stamp);sale.put("businessDay",day);
      sale.put("order",0);sale.put("qty",q);sale.put("total",amount);
      sale.put("paid",true);sale.put("method",remote.getString("method"));
      sale.put("refunded","REFUNDED".equals(status));sale.put("costTotal",cost);
      sale.put("liveOrderId",id);sale.put("cloudSynced",true);
      sales.put(sale);
      int toAdd="REFUNDED".equals(status)?0:amount;
      return host.posPrefs().edit()
        .putString("stock_sales",sales.toString())
        .putInt("sales_"+day,host.posPrefs().getInt("sales_"+day,0)+toAdd)
        .commit();
    }catch(Exception ex){host.message("Sync rekod jualan gagal: "+ex.getMessage());return false;}
  }
  void recoverPendingOrders(){
    if(!accounts.active())return;
    String session=activeKey,who=accounts.user().getUid();
    com.google.firebase.firestore.Query pendingQuery=accounts.staff()?
      branch().collection("liveOrders").whereEqualTo("actorUid",who):
      branch().collection("liveOrders").whereEqualTo("status","PENDING");
    pendingQuery.get().addOnSuccessListener(results->{
        if(!session.equals(activeKey))return;
        JSONArray local=host.entries("pending_orders");boolean changed=false;
        for(QueryDocumentSnapshot doc:results){
          if(!"PENDING".equals(doc.getString("status")))continue;
          if(accounts.staff()&&!who.equals(doc.getString("actorUid")))continue;
          boolean exists=false;
          for(int i=0;i<local.length();i++){
            JSONObject item=local.optJSONObject(i);
            if(item!=null&&doc.getId().equals(item.optString("liveId",""))){exists=true;break;}
          }
          if(exists)continue;
          try{
            JSONObject recovered=new JSONObject();JSONArray qty=new JSONArray();
            List<?> values=(List<?>)doc.get("qty");
            if(values==null)continue;
            for(Object v:values)qty.put(((Number)v).intValue());
            String business=doc.getString("day");
            int seq=host.posPrefs().getInt("order_seq_"+business,0)+1;
            recovered.put("time",host.timestamp());recovered.put("businessDay",business);
            recovered.put("order",seq);recovered.put("total",doc.getLong("total"));
            recovered.put("qty",qty);recovered.put("paid",false);
            recovered.put("cancelled",false);recovered.put("liveId",doc.getId());
            recovered.put("recoveredFromCloud",true);
            local.put(recovered);
            host.posPrefs().edit().putInt("order_seq_"+business,seq).apply();
            changed=true;
          }catch(Exception ignored){}
        }
        if(changed){
          host.posPrefs().edit().putString("pending_orders",local.toString()).commit();
          host.draw();host.message("Order pending Live dipulihkan tanpa memadam data lama.");
        }
      }).addOnFailureListener(e->host.message("Semak pending cloud gagal: "+e.getMessage()));
  }
  void reconcileLocalPending(){
    JSONArray local=host.entries("pending_orders");
    for(int i=0;i<local.length();i++){
      JSONObject o=local.optJSONObject(i);
      if(o==null||o.optBoolean("paid",false)||o.optBoolean("cancelled",false))continue;
      String cloudId=o.optString("liveId","");
      if(cloudId.isEmpty())continue;
      final int index=i;
      orders(cloudId).get().addOnSuccessListener(doc->{
        if(!doc.exists()||doc.getMetadata().isFromCache())return;
        String state=doc.getString("status");
        if("PAID".equals(state)){
          host.restoreCloudPaymentLocal(index,doc.getString("method"));
        }else if("CANCELLED".equals(state))host.cancelPendingLocally(index);
      });
    }
  }
  void activate(){
    if(!accounts.owner()){host.message("Owner sahaja boleh aktifkan Live.");return;}
    if(enabled()){host.message("Branch Live telah aktif. Stok dikawal oleh server.");return;}
    host.darkBuilder().setTitle("Aktifkan Live untuk branch?")
      .setMessage("Stok sedia ada akan diambil SEKALI sebagai baki awal cloud. Data POS asal tidak diubah. Selepas itu transaksi kaunter Live memerlukan internet.")
      .setPositiveButton("AKTIFKAN",(dlg,which)->{
        final ArrayList<Long> initial=new ArrayList<>(),priceList=new ArrayList<>();
        final ArrayList<Boolean> hiddenList=new ArrayList<>();
        for(int i=0;i<host.names.length;i++){
          initial.add((long)(host.unlimited(i)?0:host.stock(i)));
          priceList.add((long)host.prices[i]);
          hiddenList.add(host.posPrefs().getBoolean("menu_hidden_"+i,false));
        }
        String name=accounts.branchName();
        Map<String,Object> initialDoc=new HashMap<>();
        initialDoc.put("stock",initial);initialDoc.put("menuPrices",priceList);
        initialDoc.put("menuHidden",hiddenList);
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
    ArrayList<Boolean> hidden=new ArrayList<>();
    for(int i=0;i<host.prices.length;i++){
      prices.add((long)host.prices[i]);
      hidden.add(host.posPrefs().getBoolean("menu_hidden_"+i,false));
    }
    String custom=host.posPrefs().getString("custom_menu","[]");
    Map<String,Object> update=new HashMap<>();
    update.put("menuPrices",prices);update.put("customMenu",custom);
    update.put("menuHidden",hidden);
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
      orderData.put("deviceId",deviceId);
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
  void editStockBalance(int item,int newBalance){
    if(!accounts.owner()||!usable()||busy){
      host.message("Edit stok Live perlukan Owner dan internet.");return;
    }
    if(item<0||item>=host.names.length||host.unlimited(item)||newBalance<0||newBalance>1000000){
      host.message("Jumlah stok tidak sah.");return;
    }
    busy=true;
    final String name=host.names[item];
    DocumentReference ref=inventory();
    DocumentReference audit=branch().collection("stockAdjustments").document();
    db.runTransaction(tx->{
      DocumentSnapshot doc=tx.get(ref);ArrayList<Long> values=readStock(doc);
      if(item>=values.size())throw new IllegalStateException("Menu branch belum diselaraskan");
      long oldBalance=values.get(item);
      values.set(item,(long)newBalance);
      Map<String,Object> record=new HashMap<>();
      record.put("ownerUid",accounts.ownerUid());record.put("branchId",accounts.branchId());
      record.put("actorUid",accounts.user().getUid());
      record.put("item",item);record.put("name",name);
      record.put("before",oldBalance);record.put("after",(long)newBalance);
      record.put("difference",(long)newBalance-oldBalance);
      record.put("type","OWNER_CORRECTION");
      record.put("createdAt",FieldValue.serverTimestamp());
      tx.update(ref,"stock",values,"updatedAt",FieldValue.serverTimestamp());
      tx.set(audit,record);
      return null;
    }).addOnSuccessListener(v->{
      busy=false;host.draw();
      host.message("Baki "+name+" telah dikemas kini kepada "+newBalance+" di cloud.");
    }).addOnFailureListener(e->{
      busy=false;host.message("Edit stok tidak disimpan: "+e.getMessage());
    });
  }
  void editStockDialog(int id){
    if(!accounts.owner()){host.message("Owner sahaja boleh edit baki stok.");return;}
    if(!usable()){host.message("Sambung internet untuk edit stok Live.");return;}
    int before=stock(id);
    EditText input=new EditText(host);host.styleInput(input);
    input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
    input.setSingleLine(true);input.setText(String.valueOf(before));
    host.darkBuilder().setTitle("EDIT BAKI STOK · "+host.names[id])
      .setMessage("Branch: "+accounts.branchName()+"\nBaki cloud sekarang: "+before+
        "\n\nMasukkan jumlah baki sebenar. Perubahan ini pembetulan stok, BUKAN jualan atau pembelian. Rekod audit disimpan di Firebase.")
      .setView(input).setPositiveButton("SIMPAN",(d,w)->{
        try{
          int newBalance=Integer.parseInt(input.getText().toString().trim());
          if(newBalance<0||newBalance>1000000)throw new NumberFormatException();
          if(newBalance==before){host.message("Tiada perubahan stok.");return;}
          host.darkBuilder().setTitle("Sahkan pembetulan stok")
            .setMessage(accounts.branchName()+"\n"+host.names[id]+
              "\nDaripada "+before+" → "+newBalance+
              "\n\nPastikan branch dan bilangan betul.")
            .setPositiveButton("SAHKAN",(d2,w2)->editStockBalance(id,newBalance))
            .setNegativeButton("BATAL",null).show();
        }catch(NumberFormatException e){host.message("Masukkan baki 0 hingga 1,000,000.");}
      }).setNegativeButton("BATAL",null).show();
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
  private ListenerRegistration ownerBranchesListener;
  private final ArrayList<ListenerRegistration> ownerSessionListeners=new ArrayList<>();
  private final Map<String,String> openingSummary=new HashMap<>();
  private final Map<String,String> staffLoginSummary=new HashMap<>();
  private String ownerSummaryKey="";
  void clearOwnerOpeningListeners(){
    if(ownerBranchesListener!=null){ownerBranchesListener.remove();ownerBranchesListener=null;}
    for(ListenerRegistration l:ownerSessionListeners)l.remove();
    ownerSessionListeners.clear();
    openingSummary.clear();staffLoginSummary.clear();ownerSummaryKey="";
  }
  void refreshOwnerOpeningStatuses(){
    if(!accounts.owner())return;
    clearOwnerOpeningListeners();
    loadOwnerOpeningStatuses();
  }
  void loadOwnerOpeningStatuses(){
    if(!accounts.owner())return;
    final String today=host.businessDay();
    final String key=accounts.ownerUid()+":"+today;
    if(key.equals(ownerSummaryKey)&&ownerBranchesListener!=null)return;
    clearOwnerOpeningListeners();
    ownerSummaryKey=key;
    ownerBranchesListener=db.collection("workspaces").document(accounts.ownerUid())
      .collection("branches").addSnapshotListener((branches,error)->{
        if(error!=null||branches==null||!key.equals(ownerSummaryKey))return;
        for(ListenerRegistration l:ownerSessionListeners)l.remove();
        ownerSessionListeners.clear();openingSummary.clear();staffLoginSummary.clear();
        for(QueryDocumentSnapshot b:branches){
          final String branchId=b.getId();
          final String branchName=b.getString("name")==null?branchId:b.getString("name");
          final String operation=BranchAccounts.operatingLabel(b.getString("operatingStatus"));
          final boolean closed=!"BUKA".equals(operation);
          openingSummary.put(branchId,branchName+" · "+
            (closed?operation+" · PEKERJA DISEKAT":"⚪ BELUM OPENING"));
          ListenerRegistration listener=b.getReference().collection("liveSessions")
            .document(today).addSnapshotListener((doc,err)->{
              if(!key.equals(ownerSummaryKey)||err!=null||doc==null)return;
              boolean open=doc.exists()&&Boolean.TRUE.equals(doc.getBoolean("open"));
              String status=open?"🟢 SUDAH OPENING":doc.exists()?"🔴 CLOSING":"⚪ BELUM OPENING";
              String worker=doc.exists()?doc.getString(open?"openedByEmail":"closedByEmail"):null;
              String stamp="";
              if(doc.exists()&&doc.getTimestamp("updatedAt")!=null)
                stamp=new SimpleDateFormat("HH:mm",Locale.US).format(doc.getTimestamp("updatedAt").toDate());
              String line=branchName+" · "+(closed?operation+" · PEKERJA DISEKAT · ":"")+status+
                (worker==null||worker.isEmpty()?"":" · "+worker)+
                (stamp.isEmpty()?"":" · "+stamp);
              String before=openingSummary.put(branchId,line);
              if(!line.equals(before)&&host.activePage==3&&accounts.owner())host.draw();
            });
          ownerSessionListeners.add(listener);
          ListenerRegistration loginListener=b.getReference().collection("staffLogins")
            .orderBy("createdAt",Query.Direction.DESCENDING).limit(1)
            .addSnapshotListener((log,logErr)->{
              if(logErr!=null||log==null||!key.equals(ownerSummaryKey))return;
              String line="Belum ada login pekerja";
              if(!log.isEmpty()){
                DocumentSnapshot latest=log.getDocuments().get(0);
                String email=latest.getString("email");
                line="Login: "+(email==null?"Pekerja":email);
                if(latest.getTimestamp("createdAt")!=null)
                  line+=" · "+new SimpleDateFormat("HH:mm",Locale.US)
                    .format(latest.getTimestamp("createdAt").toDate());
              }
              String old=staffLoginSummary.put(branchId,line);
              if(!line.equals(old)&&host.activePage==3&&accounts.owner())host.draw();
            });
          ownerSessionListeners.add(loginListener);
        }
        if(host.activePage==3&&accounts.owner())host.draw();
      });
  }
  void drawOpeningSummary(){
    if(!accounts.owner())return;
    loadOwnerOpeningStatuses();
    host.add(host.body,host.text("STATUS BRANCH HARI INI · LIVE",13,host.gold,true),-1,-2);
    host.gap(host.body,6);
    if(openingSummary.isEmpty()){
      host.add(host.body,host.text("Sedang semak Opening semua branch...",12,host.muted,false),-1,-2);
    }else{
      ArrayList<String> list=new ArrayList<>(openingSummary.values());
      java.util.Collections.sort(list);
      for(String entry:list){
        host.add(host.body,host.text(entry,12,host.ink,true),-1,-2);
        // Login summary below the matching branch is shown on the next
        // refresh from the same server snapshot listener.
        String branchName=entry.split(" · ",2)[0];
        String login=null;
        for(Map.Entry<String,String> member:openingSummary.entrySet())
          if(member.getValue().equals(entry)){login=staffLoginSummary.get(member.getKey());break;}
        if(login!=null)host.add(host.body,host.text("    "+login,11,host.muted,false),-1,-2);
        host.gap(host.body,8);
      }
    }
    host.gap(host.body,9);
  }
  void showOpeningBranches(){
    if(!accounts.owner())return;
    loadOwnerOpeningStatuses();
    StringBuilder content=new StringBuilder("Tarikh: "+host.businessDay()+"\n\n");
    for(Map.Entry<String,String> item:openingSummary.entrySet()){
      content.append(item.getValue()).append("\n");
      if(staffLoginSummary.containsKey(item.getKey()))
        content.append("  ").append(staffLoginSummary.get(item.getKey())).append("\n");
      content.append("\n");
    }
    if(openingSummary.isEmpty())content.append("Belum menerima status daripada Firestore.");
    host.darkBuilder().setTitle("CASHFLOW LIVE · SEMUA BRANCH")
      .setMessage(content.toString())
      .setPositiveButton("TUTUP",null).show();
  }
  void showOpeningAlerts(){
    if(!accounts.owner())return;
    final String today=host.businessDay();
    db.collection("workspaces").document(accounts.ownerUid()).collection("branches")
      .get().addOnSuccessListener(branches->{
        ArrayList<com.google.android.gms.tasks.Task<com.google.firebase.firestore.QuerySnapshot>> checks=new ArrayList<>();
        ArrayList<String> names=new ArrayList<>();
        for(QueryDocumentSnapshot branch:branches){
          String name=branch.getString("name")==null?branch.getId():branch.getString("name");
          checks.add(branch.getReference().collection("openingChecks")
            .whereEqualTo("day",today).get());
          names.add(name+" · Opening");
          checks.add(branch.getReference().collection("loginChecks")
            .whereEqualTo("day",today).get());
          names.add(name+" · Log In");
        }
        if(checks.isEmpty()){host.message("Tiada branch.");return;}
        com.google.android.gms.tasks.Tasks.whenAllSuccess(checks).addOnSuccessListener(res->{
          StringBuilder report=new StringBuilder("Tarikh "+today+"\n\n");
          int blocked=0;
          for(int i=0;i<res.size();i++){
            com.google.firebase.firestore.QuerySnapshot events=(com.google.firebase.firestore.QuerySnapshot)res.get(i);
            for(QueryDocumentSnapshot doc:events){
              String state=doc.getString("status");
              if("VERIFIED".equals(state)||"WITHIN_BRANCH".equals(state))continue;
              blocked++;
              report.append(names.get(i)).append("\n")
                .append(state==null?"Tidak diketahui":state)
                .append(" · ").append(doc.getString("email")==null?
                  doc.getString("employeeUid"):doc.getString("email"))
                .append("\n");
              Long meters=doc.getLong("distanceMeters");
              if(meters!=null&&meters>=0)report.append("Jarak ±").append(meters).append(" m\n");
              if(doc.getTimestamp("createdAt")!=null)
                report.append(new SimpleDateFormat("dd/MM HH:mm",Locale.US)
                  .format(doc.getTimestamp("createdAt").toDate())).append("\n");
              report.append("\n");
            }
          }
          if(blocked==0)report.append("Tiada percubaan Opening disekat yang direkod.");
          host.darkBuilder().setTitle("CUBAAN OPENING · "+blocked)
            .setMessage(report.toString()).setPositiveButton("TUTUP",null).show();
        }).addOnFailureListener(e->host.message("Semak cubaan gagal: "+e.getMessage()));
      }).addOnFailureListener(e->host.message("Gagal semak branch: "+e.getMessage()));
  }
  void sessionConfirmed(boolean open,String day,int amount,Done onSuccess){
    if(!usable()||!accounts.staff()){host.message("Sesi pekerja memerlukan Live ONLINE.");return;}
    final String owner=accounts.ownerUid(),branchId=accounts.branchId();
    final String uid=accounts.user().getUid();
    DocumentReference doc=branch().collection("liveSessions").document(day);
    db.runTransaction(tx->{
      DocumentSnapshot existing=tx.get(doc);
      if(existing.exists()&&Boolean.TRUE.equals(existing.getBoolean("open"))&&open)
        throw new IllegalStateException("Branch telah Opening pada telefon lain. Minta Owner semak.");
      Map<String,Object> state=new HashMap<>();
      state.put("day",day);state.put("branchId",branchId);state.put("ownerUid",owner);
      state.put("open",open);
      state.put(open?"openedBy":"closedBy",uid);
      state.put(open?"openedByEmail":"closedByEmail",accounts.user().getEmail());
      state.put(open?"opening":"closing",amount);
      state.put("updatedAt",FieldValue.serverTimestamp());
      tx.set(doc,state,com.google.firebase.firestore.SetOptions.merge());
      return null;
    }).addOnSuccessListener(result->onSuccess.apply())
      .addOnFailureListener(e->host.darkBuilder().setTitle("OPENING CLOUD GAGAL")
        .setMessage("Opening tidak dibuat kerana Cloud menolak operasi: "+e.getMessage())
        .setPositiveButton("FAHAM",null).show());
  }
  void session(boolean open,String day,int amount){
    if(!accounts.active()||!usable())return;
    DocumentReference doc=branch().collection("liveSessions").document(day);
    Map<String,Object> state=new HashMap<>();
    state.put("day",day);state.put("branchId",accounts.branchId());
    state.put("ownerUid",accounts.ownerUid());
    if(open){
      state.put("openedBy",accounts.user().getUid());
      state.put("openedByEmail",accounts.user().getEmail());
      state.put("opening",amount);state.put("open",true);
    }else{
      state.put("closedBy",accounts.user().getUid());
      state.put("closedByEmail",accounts.user().getEmail());
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
        String report="WARISAN POS · LAPORAN LIVE\n"+accounts.branchName()+"\n"+
          month+"\n\nJualan: "+host.money((int)sale)+
          "\nTunai: "+host.money((int)cash)+"\nQR: "+host.money((int)qr)+
          "\nTransaksi: "+tx+"\n\n"+(byDay.length()==0?"Belum ada rekod live":byDay.toString());
        host.darkBuilder().setTitle("LAPORAN LIVE · "+month)
          .setMessage(report)
          .setNeutralButton("CETAK A4 / PDF",(dlg,w)->WarisanPrinting.textA4(host,report,"Warisan Live "+month))
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
