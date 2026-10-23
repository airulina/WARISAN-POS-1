package my.warisan.pos;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.view.View;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import com.google.firebase.firestore.Source;
import com.google.firebase.firestore.WriteBatch;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * One-app Owner / Staff identity and branch selector.
 * Sales data remains local to each device in this release, with owner-only cloud backup.
 * DO NOT treat local role hiding as security: deploy firestore.rules for server-side authorization.
 */
final class BranchAccounts {
  final MainActivity host;
  final FirebaseAuth auth;
  final FirebaseFirestore db;
  final SharedPreferences identity;
  volatile boolean staffBranchVerified=false;
  static final String ROOT="workspaces";
  static final String LEGACY_BRANCH="utama_legacy";
  final ArrayList<String> stockBranchIds=new ArrayList<>();
  final ArrayList<String> stockBranchNames=new ArrayList<>();
  final ArrayList<String> archivedStockIds=new ArrayList<>();
  final ArrayList<String> archivedStockNames=new ArrayList<>();
  // Display cache only. Inventory and stock updates remain in LiveBranchStore.
  final Map<String,StockPreview> stockPreviews=new HashMap<>();
  static final int PREVIEW_LOW_STOCK=20;
  static final String[] DEFAULT_STOCK_NAMES={"Sate Ayam","Sate Daging",
    "Sate Kambing","Nasi Impit","Extra Kuah Kacang","Laksa Utara","Kuih Siput"};
  static final class StockPreview {
    boolean ready,live,error;
    long loadedAt;
    final ArrayList<Long> quantity=new ArrayList<>();
    final ArrayList<Boolean> hidden=new ArrayList<>();
    final ArrayList<String> names=new ArrayList<>();
  }
  boolean stockBranchesLoaded=false,stockBranchesLoading=false;
  String stockListOwner="";
  static String operatingLabel(String code) {
    if("OFFDAY".equals(code))return "OFF DAY";
    if("STOCK_OUT".equals(code))return "TUTUP · STOK HABIS";
    return "BUKA";
  }
  void manageBranchOperatingStatus(){
    if(!owner()){host.message("Hanya Owner boleh tetapkan Off Day.");return;}
    final String uid=ownerUid();
    db.collection(ROOT).document(uid).collection("branches")
      .get(com.google.firebase.firestore.Source.SERVER)
      .addOnSuccessListener(branches->{
        if(!owner()||!uid.equals(ownerUid()))return;
        ArrayList<QueryDocumentSnapshot> docs=new ArrayList<>();
        ArrayList<String> labels=new ArrayList<>();
        for(QueryDocumentSnapshot b:branches){
          if(Boolean.TRUE.equals(b.getBoolean("archived")))continue;
          docs.add(b);
          String name=b.getString("name");if(name==null)name=b.getId();
          String status=operatingLabel(b.getString("operatingStatus"));
          labels.add(name+"  ·  "+status);
        }
        if(docs.isEmpty()){host.message("Belum ada branch.");return;}
        host.darkBuilder().setTitle("OPERASI BRANCH · OWNER")
          // AlertDialog cannot combine setMessage with setItems.
          .setItems(labels.toArray(new String[0]),(dialog,i)->chooseBranchStatus(docs.get(i)))
          .setNegativeButton("TUTUP",null).show();
      }).addOnFailureListener(e->host.message("Semak branch gagal: "+e.getMessage()));
  }
  void chooseBranchStatus(QueryDocumentSnapshot branch){
    if(!owner())return;
    final String id=branch.getId(),name=branch.getString("name")==null?id:branch.getString("name");
    final String old=branch.getString("operatingStatus");
    final String[] options={
      "🟢  BUKA · Benarkan pekerja Log In / Opening",
      "🔴  OFF DAY · Cuti / Tidak berniaga",
      "🟠  TUTUP · STOK HABIS"
    };
    host.darkBuilder().setTitle("Status "+name+" · "+operatingLabel(old))
      .setItems(options,(dialog,i)->{
        String newStatus=i==0?"OPEN":i==1?"OFFDAY":"STOCK_OUT";
        if(newStatus.equals(old)||(old==null&&"OPEN".equals(newStatus))){
          host.message("Branch sudah berstatus "+operatingLabel(newStatus));return;
        }
        String note=i==1?"OFF DAY":i==2?"STOK HABIS":"BUKA SEMULA";
        host.darkBuilder().setTitle("Sahkan "+name+" · "+note)
          .setMessage(i==0?
            "Buka semula akses pekerja untuk "+name+"? Pekerja boleh Log In semula.":
            "Tutup "+name+" sekarang? Semua akaun pekerja branch ini akan disekat daripada login, Opening dan transaksi Live, walaupun pada telefon lain. Rekod lama tidak dipadam. Jika sesi sedang OPEN, Owner perlu semak dan urus Closing.")
          .setPositiveButton("SAHKAN",(d,w)->updateBranchStatus(id,name,newStatus))
          .setNegativeButton("BATAL",null).show();
      }).setNegativeButton("BATAL",null).show();
  }
  void updateBranchStatus(String id,String name,String status){
    if(!owner()||user()==null)return;
    if(!("OPEN".equals(status)||"OFFDAY".equals(status)||"STOCK_OUT".equals(status)))return;
    final String ownerId=ownerUid(),actor=user().getUid();
    DocumentReference ref=db.collection(ROOT).document(ownerId).collection("branches").document(id);
    Map<String,Object> fields=new HashMap<>();
    fields.put("operatingStatus",status);
    fields.put("operatingReason","OFFDAY".equals(status)?"OFF DAY":
      "STOCK_OUT".equals(status)?"STOK HABIS":"");
    fields.put("operatingUpdatedBy",actor);
    fields.put("operatingUpdatedAt",com.google.firebase.firestore.FieldValue.serverTimestamp());
    ref.update(fields).addOnSuccessListener(v->{
      if(!owner()||!ownerId.equals(ownerUid()))return;
      host.message(name+" → "+operatingLabel(status)+". Status disimpan di Firebase.");
      if(host.live!=null)host.live.refreshOwnerOpeningStatuses();
      manageBranchOperatingStatus();
    }).addOnFailureListener(e->host.darkBuilder().setTitle("Status tidak disimpan")
      .setMessage("Firestore gagal: "+e.getMessage()+". Pastikan online dan Rules Owner sudah aktif.")
      .setPositiveButton("OK",null).show());
  }
  void showStockBranches(){
    if(!owner()){host.message("Hanya Owner boleh urus stok branch.");return;}
    String uid=ownerUid();
    if(!uid.equals(stockListOwner)){
      stockBranchesLoaded=false;stockBranchIds.clear();stockBranchNames.clear();
      archivedStockIds.clear();archivedStockNames.clear();
      stockPreviews.clear();
      stockListOwner=uid;
    }
    drawStockHeader();
    host.add(host.body,host.text("Cawangan yang diaktifkan akan dipaparkan di sini secara automatik.",
      12,host.muted,false),-1,-2);
    host.gap(host.body,9);
    if(!stockBranchesLoaded){
      host.add(host.body,host.text(stockBranchesLoading?
        "Sedang mendapatkan senarai branch...":"Memuatkan senarai branch...",12,host.muted,false),-1,-2);
      loadStockBranches();
      return;
    }
    if(stockBranchIds.isEmpty()&&archivedStockIds.isEmpty()){
      host.add(host.body,host.text("Belum ada branch. Daftar branch melalui Setting → Account.",13,host.muted,false),-1,-2);
      return;
    }
    for(String id:stockBranchIds)loadStockPreview(id);
    for(int i=0;i<stockBranchIds.size();i++){
      final String id=stockBranchIds.get(i),name=stockBranchNames.get(i);
      drawStockBranchCard(id,name,stockPrimaryIds.contains(id));
    }
    if(!archivedStockIds.isEmpty()){
      LinearLayout archive=host.col();archive.setPadding(host.dp(13),host.dp(12),host.dp(13),host.dp(12));
      archive.setBackground(stockOutline(host.surfaceAlt,host.muted,12,1));
      TextView heading=host.text("▣  Arkib Cawangan ("+archivedStockIds.size()+")",16,host.ink,true);
      host.add(archive,heading,-1,-2);
      host.add(archive,host.text("Cawangan yang ditutup kekal disimpan di sini.",11,host.muted,false),-1,-2);
      host.gap(archive,9);
      for(int i=0;i<archivedStockIds.size();i++){
        final String id=archivedStockIds.get(i),name=archivedStockNames.get(i);
        LinearLayout line=host.row();line.setPadding(host.dp(10),host.dp(10),host.dp(10),host.dp(10));
        line.setBackground(host.shape(host.surface,10));
        LinearLayout detail=host.col();
        host.add(detail,host.text("▣  "+name,14,host.ink,true),-1,-2);
        host.add(detail,host.text("DITUTUP KEKAL · Rekod disimpan",10,host.muted,false),-1,-2);
        line.addView(detail,new LinearLayout.LayoutParams(0,-2,1));
        TextView records=host.chip("Lihat Rekod",host.surfaceAlt,host.ink);
        host.add(line,records,-2,38);
        line.setOnClickListener(v->host.ownerCashflow.openArchivedBranchReport(id));
        records.setOnClickListener(v->host.ownerCashflow.openArchivedBranchReport(id));
        host.add(archive,line,-1,-2);host.gap(archive,5);
      }
      host.add(host.body,archive,-1,-2);host.gap(host.body,12);
    }
    if(!stockBranchIds.isEmpty()){
      stockGoldButton(host.body,"▣  Restock Cawangan (Admin Sahaja)",()->chooseStockRestock());
      host.gap(host.body,10);
    }
    TextView refresh=host.text("↻  Segarkan paparan stok",12,host.muted,true);
    refresh.setGravity(Gravity.CENTER);
    host.add(host.body,refresh,-1,44);
    refresh.setOnClickListener(v->{stockPreviews.clear();stockBranchesLoaded=false;loadStockBranches();host.draw();});
  }
  // The summary reads existing stock documents, without adjusting stock, sales, or permissions.
  final ArrayList<String> stockPrimaryIds=new ArrayList<>();
  void drawStockHeader(){
    LinearLayout masthead=host.row();
    ImageView logo=new ImageView(host);host.setStoreLogo(logo);host.makeCircle(logo);
    host.add(masthead,logo,54,54);
    TextView brand=host.text("WARISAN POS-1",20,host.gold,true);
    brand.setTypeface(Typeface.SERIF,Typeface.BOLD);brand.setSingleLine(true);
    brand.setEllipsize(android.text.TextUtils.TruncateAt.END);
    LinearLayout.LayoutParams title=new LinearLayout.LayoutParams(0,-2,1);
    title.leftMargin=host.dp(9);masthead.addView(brand,title);
    TextView admin=host.chip("● ADMIN",0xff134f37,0xff56ec96);
    admin.setTextSize(10);host.add(masthead,admin,-2,34);
    host.add(host.body,masthead,-1,-2);host.gap(host.body,18);
    host.add(host.body,host.text("Stok",30,host.ink,true),-1,-2);
    host.gap(host.body,12);
    host.add(host.body,host.text("Cawangan Aktif",20,host.ink,true),-1,-2);
  }
  void loadStockPreview(String id){
    if(!owner()||stockPreviews.containsKey(id))return;
    final String uid=ownerUid();
    StockPreview preview=new StockPreview();stockPreviews.put(id,preview);
    db.collection(ROOT).document(uid).collection("branches").document(id)
      .collection("live").document("current").get(Source.SERVER)
      .addOnSuccessListener(doc->{
        if(!owner()||!uid.equals(ownerUid())||stockPreviews.get(id)!=preview)return;
        preview.ready=true;preview.live=doc.exists();preview.loadedAt=System.currentTimeMillis();
        if(doc.exists())readStockPreview(doc,preview);
        if(host.activePage==1)host.draw();
      }).addOnFailureListener(error->{
        if(!owner()||!uid.equals(ownerUid())||stockPreviews.get(id)!=preview)return;
        preview.ready=true;preview.error=true;
        if(host.activePage==1)host.draw();
      });
  }
  void readStockPreview(DocumentSnapshot doc,StockPreview preview){
    Object raw=doc.get("stock");
    if(!(raw instanceof List)){preview.error=true;return;}
    for(Object n:(List<?>)raw){
      if(!(n instanceof Number)){preview.error=true;return;}
      preview.quantity.add(Math.max(0,((Number)n).longValue()));
    }
    Object flags=doc.get("menuHidden");
    if(flags instanceof List)for(Object f:(List<?>)flags)
      preview.hidden.add(Boolean.TRUE.equals(f));
    for(String name:DEFAULT_STOCK_NAMES)preview.names.add(name);
    try{
      JSONArray extra=new JSONArray(doc.getString("customMenu")==null?"[]":doc.getString("customMenu"));
      for(int i=0;i<extra.length();i++){
        JSONObject item=extra.optJSONObject(i);
        preview.names.add(item==null?"Menu":item.optString("name","Menu"));
      }
    }catch(Exception ignored){}
  }
  StockPreview shownStockPreview(String id){
    StockPreview cloud=stockPreviews.get(id);
    if(cloud!=null&&cloud.live)return cloud;
    if(!id.equals(branchId()))return cloud;
    if(cloud==null||!cloud.ready||cloud.error||host.live!=null&&host.live.enabled())
      return cloud;
    // Only the selected branch may fall back to its actual on-device stock.
    // Never present another branch's local balance as that branch's balance.
    StockPreview local=new StockPreview();local.ready=true;
    for(int i=0;i<host.names.length;i++){
      local.names.add(host.names[i]);
      local.hidden.add(host.posPrefs().getBoolean("menu_hidden_"+i,false));
      local.quantity.add((long)(host.unlimited(i)?0:host.stock(i)));
    }
    return local;
  }
  boolean previewVisible(StockPreview p,int index){
    return index>=0&&index<p.quantity.size()&&
      (index>=p.hidden.size()||!p.hidden.get(index));
  }
  boolean unitUnlimited(int index){return index==4;}
  GradientDrawable stockOutline(int background,int stroke,int radius,int thickness){
    GradientDrawable shape=host.shape(background,radius);
    shape.setStroke(host.dp(thickness),host.themeColor(stroke));return shape;
  }
  void stockGoldButton(LinearLayout container,String label,Runnable open){
    TextView button=host.text(label,15,host.onAccent,true);button.setGravity(Gravity.CENTER);
    button.setPadding(host.dp(8),host.dp(12),host.dp(8),host.dp(12));
    GradientDrawable bg=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
      new int[]{host.themeColor(0xffffd67b),host.themeColor(host.gold)});
    bg.setCornerRadius(host.dp(11));button.setBackground(bg);
    host.add(container,button,-1,51);button.setOnClickListener(v->open.run());
  }
  void stockMetric(LinearLayout container,String symbol,String label,String value,int color){
    LinearLayout tile=host.col();tile.setPadding(host.dp(8),host.dp(8),host.dp(5),host.dp(8));
    tile.setBackground(stockOutline(host.surfaceAlt,host.muted,11,1));
    host.add(tile,host.text(symbol+"  "+label,10,host.muted,false),-1,-2);
    TextView amount=host.text(value,21,color,true);amount.setGravity(Gravity.CENTER);
    host.add(tile,amount,-1,-2);
    LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,host.dp(74),1);
    lp.setMargins(host.dp(2),0,host.dp(2),0);container.addView(tile,lp);
  }
  void stockBar(LinearLayout panel,String label,long amount,long maximum){
    LinearLayout line=host.row();
    TextView title=host.text(label,12,host.ink,true);title.setSingleLine(true);
    title.setEllipsize(android.text.TextUtils.TruncateAt.END);
    line.addView(title,new LinearLayout.LayoutParams(0,host.dp(30),1.2f));
    ProgressBar progress=new ProgressBar(host,null,android.R.attr.progressBarStyleHorizontal);
    progress.setMax(1000);
    progress.setProgress((int)Math.min(1000,(amount*1000L)/Math.max(1,maximum)));
    progress.setProgressTintList(ColorStateList.valueOf(0xff22c66d));
    progress.setProgressBackgroundTintList(ColorStateList.valueOf(host.themeColor(host.surfaceAlt)));
    LinearLayout.LayoutParams bar=new LinearLayout.LayoutParams(0,host.dp(13),2.4f);
    bar.setMargins(host.dp(7),0,host.dp(7),0);line.addView(progress,bar);
    TextView value=host.text(String.valueOf(amount),13,host.ink,true);
    value.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);
    line.addView(value,new LinearLayout.LayoutParams(0,host.dp(30),.55f));
    host.add(panel,line,-1,-2);
  }
  void drawStockBranchCard(String id,String name,boolean primary){
    StockPreview p=shownStockPreview(id);
    LinearLayout card=host.col();card.setPadding(host.dp(13),host.dp(13),host.dp(13),host.dp(13));
    card.setBackground(stockOutline(host.surface,host.gold,15,2));
    LinearLayout head=host.row();
    host.add(head,host.text("▣",26,host.gold,true),30,45);
    LinearLayout info=host.col();info.setPadding(host.dp(9),0,0,0);
    host.add(info,host.text(name+(primary?"  🔒":""),18,host.ink,true),-1,-2);
    host.add(info,host.text(primary?"Cawangan utama sistem · Tidak boleh diarkibkan":
      "Cawangan aktif",11,host.muted,false),-1,-2);
    head.addView(info,new LinearLayout.LayoutParams(0,-2,1));
    TextView status=host.chip(primary?"KEKAL AKTIF":"● AKTIF",
      primary?host.gold:0xff134f37,primary?host.onAccent:0xff55e99a);
    status.setTextSize(10);host.add(head,status,-2,34);
    host.add(card,head,-1,-2);host.gap(card,10);
    LinearLayout stats=host.row();
    if(p==null||!p.ready||p.error||(!p.live&&!id.equals(branchId()))){
      stockMetric(stats,"◇","Item Aktif","—",host.ink);
      stockMetric(stats,"▲","Stok Rendah","—",host.gold);
    }else{
      int active=0,low=0;
      for(int i=0;i<p.quantity.size();i++)if(previewVisible(p,i)){
        active++;if(!unitUnlimited(i)&&p.quantity.get(i)<PREVIEW_LOW_STOCK)low++;
      }
      stockMetric(stats,"◇","Item Aktif",""+active,0xff35cf7b);
      stockMetric(stats,"▲","Stok Rendah",""+low,0xffffc452);
    }
    // Damage is stored in branch-local history; other branches cannot be given an invented zero.
    String damage="—";
    if(id.equals(branchId())){
      int count=0;
      JSONArray events=host.entries("stock_damage");
      for(int i=0;i<events.length();i++){
        JSONObject event=events.optJSONObject(i);
        if(event!=null&&!event.optBoolean("stockReset",false)&&
          host.businessDay().equals(host.entryDay(event)))
          count+=Math.max(0,event.optInt("qty"));
      }
      damage=""+count;
    }
    stockMetric(stats,"!","Rosak Hari Ini",damage,0xffff6868);
    host.add(card,stats,-1,-2);host.gap(card,10);
    if(p==null||!p.ready){
      host.add(card,host.text("Sedang memuatkan inventori cawangan...",11,host.muted,false),-1,-2);
    }else if(p.error){
      host.add(card,host.text("Ringkasan stok belum dapat disahkan. Tekan Lihat Stok untuk semakan.",11,host.muted,false),-1,-2);
    }else if(!p.live&&!id.equals(branchId())){
      host.add(card,host.text("Stok Live belum aktif. Buka cawangan untuk melihat rekod setempat.",11,host.muted,false),-1,-2);
    }else{
      host.add(card,host.text("Inventori "+name,12,host.muted,true),-1,-2);
      long maximum=1;int shown=0;
      for(int i=0;i<p.quantity.size();i++)if(previewVisible(p,i)&&!unitUnlimited(i)){
        maximum=Math.max(maximum,p.quantity.get(i));
      }
      for(int i=0;i<p.quantity.size()&&shown<2;i++)if(previewVisible(p,i)&&!unitUnlimited(i)){
        stockBar(card,i<p.names.size()?p.names.get(i):"Item "+(i+1),p.quantity.get(i),maximum);
        shown++;
      }
      if(shown==0)host.add(card,host.text("Tiada item stok untuk dipaparkan.",11,host.muted,false),-1,-2);
    }
    host.gap(card,12);
    stockGoldButton(card,"☷  Lihat Stok   ›",()->openStockBranch(id,name));
    host.add(host.body,card,-1,-2);host.gap(host.body,12);
  }
  void chooseStockRestock(){
    if(!owner()||stockBranchIds.isEmpty())return;
    if(stockBranchIds.size()==1){openStockBranch(stockBranchIds.get(0),stockBranchNames.get(0));return;}
    host.darkBuilder().setTitle("Restock · Pilih Cawangan")
      .setItems(stockBranchNames.toArray(new String[0]),(d,which)->
        openStockBranch(stockBranchIds.get(which),stockBranchNames.get(which)))
      .setNegativeButton("BATAL",null).show();
  }
  void loadStockBranches(){
    if(!owner()||stockBranchesLoading)return;
    final String uid=ownerUid();stockBranchesLoading=true;
    db.collection(ROOT).document(uid).collection("branches").get()
      .addOnSuccessListener(result->{
        stockBranchesLoading=false;
        if(!owner()||!uid.equals(ownerUid()))return;
        stockBranchIds.clear();stockBranchNames.clear();
        archivedStockIds.clear();archivedStockNames.clear();
        stockPrimaryIds.clear();
        for(QueryDocumentSnapshot doc:result){
          String name=doc.getString("name");
          name=name==null||name.trim().isEmpty()?"Branch "+doc.getId():name;
          if(Boolean.TRUE.equals(doc.getBoolean("archived"))){
            archivedStockIds.add(doc.getId());archivedStockNames.add(name);
          }else if(isPrimaryBranch(doc)){
            stockBranchIds.add(0,doc.getId());stockBranchNames.add(0,name);
            stockPrimaryIds.add(doc.getId());
          }else{
            stockBranchIds.add(doc.getId());stockBranchNames.add(name);
          }
        }
        stockBranchesLoaded=true;
        for(String id:stockBranchIds)loadStockPreview(id);
        if(host.activePage==1)host.draw();
      }).addOnFailureListener(error->{
        stockBranchesLoading=false;
        if(host.activePage==1)host.message("Senarai branch gagal: "+error.getMessage());
      });
  }
  void openStockBranch(String id,String name){
    if(!owner()||user()==null){host.message("Sesi Owner diperlukan.");return;}
    if(!stockBranchIds.contains(id)){host.message("Branch belum disahkan.");return;}
    // Fetch a fresh visual summary after returning from the existing stock page.
    stockPreviews.remove(id);
    // Branch selection reuses the Owner's saved login without registering or
    // clearing any previous branch or legacy sales/stock data.
    if(!id.equals(branchId())){
      if(host.live!=null)host.live.stop();
      setSession(user().getUid(),ownerUid(),id,name,"OWNER");
    }
    host.selectedStockDay=host.businessDay();
    host.activePage=13;
    host.draw();
  }

  boolean hasLegacyRecords(){
    // Ignore housekeeping flags: they do not mean a shop has real data.
    for(String key:host.getPreferences(Context.MODE_PRIVATE).getAll().keySet()){
      if(key.startsWith("sales_")||key.startsWith("orders_")||
        key.startsWith("price_")||key.startsWith("cost_")||
        key.startsWith("supplier_")||key.startsWith("menu_")||
        key.equals("stock_entries")||key.equals("stock_sales")||
        key.equals("stock_damage")||key.equals("cash_entries")||
        key.equals("pending_orders")||key.equals("custom_menu")||
        key.equals("shop_name")||key.equals("company_name"))return true;
    }
    return false;
  }
  BranchAccounts(MainActivity host,FirebaseAuth auth,FirebaseFirestore db){
    this.host=host;this.auth=auth;this.db=db;
    this.identity=host.getSharedPreferences("warisan_identity",Context.MODE_PRIVATE);
  }
  FirebaseUser user(){return auth.getCurrentUser();}
  boolean configured(){return identity.getBoolean("configured",false);}
  boolean active(){
    FirebaseUser u=user();
    return u!=null&&u.getUid().equals(identity.getString("sessionUid",""))
       && !identity.getString("branchId","").isEmpty()
       && !identity.getString("ownerUid","").isEmpty();
  }
  // Fail closed: a fresh worker install must never get Owner tabs just because
  // a legacy migration or app housekeeping created records in local storage.
  boolean blocked(){return !active()||(staff()&&!staffBranchVerified);}
  boolean staff(){return active()&&"STAFF".equals(identity.getString("role",""));}
  boolean owner(){return active()&&"OWNER".equals(identity.getString("role",""));}
  String ownerUid(){return identity.getString("ownerUid","");}
  String branchId(){return identity.getString("branchId","");}
  String branchName(){return identity.getString("branchName","Kios");}
  String summary(){
    if(!active())return "Belum pilih branch";
    return branchName()+" · "+(owner()?"Owner":"Pekerja");
  }
  SharedPreferences posPrefs(){
    // Existing Warisan POS data is untouched until an owner explicitly registers a branch.
    if(!active())return host.getPreferences(Context.MODE_PRIVATE);
    // The legacy owner's first branch stays on the EXACT original preferences file.
    // No data copy, reset, or migration occurs during app upgrade or branch activation.
    if(owner()&&LEGACY_BRANCH.equals(branchId()))return host.getPreferences(Context.MODE_PRIVATE);
    return host.getSharedPreferences("warisan_branch_"+branchId(),Context.MODE_PRIVATE);
  }
  void setSession(String uid,String ownerUid,String branchId,String branchName,String role){
    // Worker sessions begin only after a server-verified OPEN branch check.
    // Reinstalled/restarted apps default to restricted until remote validation.
    staffBranchVerified=!"STAFF".equals(role);
    identity.edit().putBoolean("configured",true).putString("sessionUid",uid)
      .putString("ownerUid",ownerUid).putString("branchId",branchId)
      .putString("branchName",branchName).putString("role",role).commit();
    host.loadMenu();
    // After a successful worker login, show the Opening/Closing dashboard first.
    // Owner continues to land on MENU; role and branch records are unchanged.
    host.activePage="STAFF".equals(role)?12:0;
    if(host.sharedPaymentQr!=null)host.sharedPaymentQr.attach();
    if(host.live!=null)host.live.attach();
    host.draw();
  }
  // IMPORTANT: Do NOT recreate migrateLegacyIfFirstBranch().
  // Older versions called it during registration and copied the entire
  // Branch Utama ledger into the first new branch. New branches always
  // receive a clean, independently named preferences file.
  void accountAlert(String title,String detail){
    host.darkBuilder().setTitle(title).setMessage(detail)
      .setPositiveButton("FAHAM",null).show();
  }
  /** Firebase server is the source of truth for the role. Local device data
      can NEVER grant a worker Owner access, even after account switches. */
  void checkMayBecomeOwner(FirebaseUser candidate,Runnable allowed){
    if(candidate==null){host.message("Login Google dahulu.");return;}
    db.collection("staffProfiles").document(candidate.getUid())
      .get(com.google.firebase.firestore.Source.SERVER)
      .addOnSuccessListener(profile->{
        if(profile.exists()&&"STAFF".equals(profile.getString("role"))
          && !Boolean.TRUE.equals(profile.getBoolean("removed"))){
          accountAlert("Tidak boleh daftar Owner",
            "Email ini masih akaun pekerja. Minta Owner lama gunakan "+
            "Senarai Pekerja → BUANG AKSES dahulu. Sekat sementara sahaja tidak mencukupi.");
          return;
        }
        String email=candidate.getEmail();
        if(email==null||email.trim().isEmpty()){
          accountAlert("Email diperlukan","Daftar Owner perlu akaun Google dengan email sah.");return;
        }
        String key=email.trim().toLowerCase(Locale.ROOT);
        db.collection("staffEmails").document(key)
          .get(com.google.firebase.firestore.Source.SERVER)
          .addOnSuccessListener(lock->{
            if(lock.exists()&&!Boolean.TRUE.equals(lock.getBoolean("removed"))){
              accountAlert("Email masih milik pekerja",
                "Email ini telah didaftarkan sebagai pekerja. Hanya selepas Owner "+
                "buang akses pekerja, email ini boleh daftar sebagai Owner.");return;
            }
            allowed.run();
          }).addOnFailureListener(e->accountAlert("Tidak dapat sahkan status",
            "Internet / Firestore Rules perlu aktif. Tiada akaun Owner baharu dibuat. "+
            "Data POS lama kekal. "+e.getMessage()));
      }).addOnFailureListener(e->accountAlert("Semakan akaun gagal",
        "Tak dapat periksa role pekerja pada Firebase. Cuba semula selepas semak internet "+
        "dan Firestore Rules. "+e.getMessage()));
  }
  void afterLogin(){
    FirebaseUser u=user();if(u==null)return;
    // A user switching from a staff login to Google sign-in must not inherit
    // the previously saved staff branch/role. This never touches POS records.
    if(staff())identity.edit().remove("sessionUid").remove("role")
      .remove("ownerUid").remove("branchId").remove("branchName").commit();
    if(host.live!=null)host.live.stop();
    checkMayBecomeOwner(u,()->{
      if(active()&&owner()){
        // Login always returns Owner to Branch Utama, not the last branch.
        // Switching via BRANCH is still available after successful login.
        chooseOwnerBranchVerified(u);return;
      }
      db.collection(ROOT).document(u.getUid())
        .get(com.google.firebase.firestore.Source.SERVER).addOnSuccessListener(doc->{
          if(doc.exists())chooseBranch();
          else {
            host.activePage=0;host.draw();
            // New users must explicitly choose Sign Up. Never auto-register.
            host.message("Pengguna baru: tekan Daftar Owner / Sign Up.");
          }
        }).addOnFailureListener(e->accountAlert("Login Owner",
          "Tak dapat sahkan Owner. Data lama kekal. "+e.getMessage()));
    });
  }
  void showActions(){
    FirebaseUser u=user();
    if(u==null){host.message("Sign in Google dahulu di halaman Account");return;}
    if(staff()){
      host.action("Branch pekerja: "+branchName(),()->host.message("Branch pekerja ditetapkan oleh Owner."));
      host.action("Semak status refund saya",()->host.staffSecurity.showMyRefunds());
      return; // NEVER display Owner recovery/signup or branch chooser to workers.
    }
    if(owner())host.action("Branch aktif: "+summary(),()->chooseBranch());
    else if(hasLegacyRecords())
      host.action("Guna data Owner lama (Owner sahaja)",()->linkLegacyOwner());
    else host.action("Daftar Owner / Sign Up",()->registerOwner());
    if(owner()){
      host.action("+ Tambah Branch",()->createBranch());
      host.action("+ Daftar pekerja · Email + Password",()->host.staffSecurity.createEmployee());
      host.action("👥  SENARAI SEMUA PEKERJA / BUANG AKSES",()->host.staffSecurity.manageWorkers());
      host.action("Refund menunggu kelulusan Owner",()->host.staffSecurity.ownerReview());
      host.action("Tukar Branch",()->chooseBranch());
    }
    if(!active())host.action("Login Pekerja · Email + Password",()->host.staffSecurity.loginEmployee());
  }
  EditText entry(String hint){
    EditText e=new EditText(host);e.setSingleLine(true);e.setHint(host.ui(hint));host.styleInput(e);return e;
  }
  void registerOwner(){
    FirebaseUser u=user();
    if(u==null){host.message("Sign in Google untuk daftar Owner.");return;}
    if(staff()){accountAlert("Akses pekerja","Pekerja tidak dibenarkan daftar Owner.");return;}
    checkMayBecomeOwner(u,()->registerOwnerVerified(u));
  }
  void registerOwnerVerified(FirebaseUser u){
    // "Sign Up" creates a distinct new Owner workspace. It NEVER silently
    // claims previous POS data simply because this device holds old records.
    // Existing owners can opt in via the separate Owner lama recovery path.
    LinearLayout form=host.col();form.setPadding(host.dp(18),0,host.dp(18),0);
    EditText business=entry("Nama perniagaan"),branch=entry("Nama branch pertama");
    host.add(form,business,-1,-2);host.add(form,branch,-1,-2);
    host.darkBuilder().setTitle("Daftar Owner · WARISAN POS")
      .setMessage("Akaun Google ini akan menjadi Owner bagi perniagaan baharu.")
      .setView(form).setPositiveButton("Daftar",(d,w)->{
        String company=business.getText().toString().trim();
        String name=branch.getText().toString().trim();
        if(company.isEmpty()||name.isEmpty()){host.message("Isi nama perniagaan dan branch.");return;}
        String ownerId=u.getUid();
        DocumentReference workspace=db.collection(ROOT).document(ownerId);
        DocumentReference first=workspace.collection("branches").document();
        Map<String,Object> root=new HashMap<>();
        root.put("ownerUid",ownerId);root.put("name",company);root.put("createdAt",new Date());
        Map<String,Object> loc=new HashMap<>();
        loc.put("ownerUid",ownerId);loc.put("name",name);loc.put("createdAt",new Date());
        workspace.get().addOnSuccessListener(existing->{
          if(existing.exists()){
            // Updating or signing in again must never create a second Owner account.
            chooseBranch();return;
          }
          WriteBatch batch=db.batch();batch.set(workspace,root);batch.set(first,loc);
          batch.commit().addOnSuccessListener(done->{
            // New Owner sign-up starts with a clean branch; old business
            // records remain untouched in legacy storage, not copied to strangers.
            setSession(ownerId,ownerId,first.getId(),name,"OWNER");
            host.message("Owner dan branch pertama berjaya didaftarkan.");
          }).addOnFailureListener(e->host.message("Daftar Owner gagal: "+e.getMessage()));
        }).addOnFailureListener(e->host.message("Gagal semak Owner: "+e.getMessage()));
      }).setNegativeButton("Batal",null).show();
  }
  void linkLegacyOwner(){
    FirebaseUser u=user();
    if(u==null){host.message("Sign in Google dahulu untuk hubungkan Owner lama.");return;}
    if(staff()){accountAlert("Bukan Owner","Pekerja tidak boleh menuntut data Owner lama.");return;}
    checkMayBecomeOwner(u,()->linkLegacyOwnerVerified(u));
  }
  void linkLegacyOwnerVerified(FirebaseUser u){
    if(!hasLegacyRecords()){
      host.message("Tiada rekod lama pada telefon. Gunakan Daftar Owner / Sign Up.");return;
    }
    String ownerId=u.getUid();
    DocumentReference workspace=db.collection(ROOT).document(ownerId);
    DocumentReference branch=workspace.collection("branches").document(LEGACY_BRANCH);
    workspace.get().addOnSuccessListener(existing->{
      if(existing.exists()){chooseBranch();return;}
      host.darkBuilder().setTitle("Guna Warisan POS lama sebagai Owner?")
        .setMessage("Tak perlu isi nama bisnes atau daftar semula. Jualan, stok, order dan duit akan terus dibaca daripada data asal telefon ini. Data tidak disalin atau dipadam.")
        .setPositiveButton("Gunakan akaun lama",(dialog,which)->{
          Map<String,Object> root=new HashMap<>(),loc=new HashMap<>();
          root.put("ownerUid",ownerId);root.put("name","WARISAN POS");root.put("createdAt",new Date());
          loc.put("ownerUid",ownerId);loc.put("name","Branch Utama");loc.put("legacyDeviceData",true);loc.put("createdAt",new Date());
          WriteBatch batch=db.batch();batch.set(workspace,root);batch.set(branch,loc);
          batch.commit().addOnSuccessListener(done->{
            setSession(ownerId,ownerId,LEGACY_BRANCH,"Branch Utama","OWNER");
            host.message("Owner lama aktif. Semua data tempatan asal dikekalkan.");
          }).addOnFailureListener(e->host.message("Gagal hubungkan Owner: "+e.getMessage()+". Data lama masih selamat."));
        }).setNegativeButton("Batal",null).show();
    }).addOnFailureListener(e->host.message("Gagal semak Owner. Data POS lama tidak diubah."));
  }

  void createBranch(){
    if(!owner()){host.message("Owner sahaja boleh tambah branch.");return;}
    EditText name=entry("Contoh: Warisan Banting");
    host.darkBuilder().setTitle("Tambah Branch").setView(name)
      .setPositiveButton("Daftar",(d,w)->{
        String label=name.getText().toString().trim();
        if(label.isEmpty()){host.message("Isi nama branch.");return;}
        DocumentReference doc=db.collection(ROOT).document(ownerUid()).collection("branches").document();
        Map<String,Object> data=new HashMap<>();
        data.put("name",label);data.put("ownerUid",ownerUid());data.put("createdAt",new Date());
        String oid=ownerUid();String uid=user().getUid();
        doc.set(data).addOnSuccessListener(done->{
          setSession(uid,oid,doc.getId(),label,"OWNER");
          host.message("Branch baharu siap. Data stok dan jualan diasingkan.");
        }).addOnFailureListener(e->host.message("Tambah branch gagal: "+e.getMessage()));
      }).setNegativeButton("Batal",null).show();
  }
  /**
   * Owner-only branch cleanup. Permanent deletion is limited to completely
   * empty, non-primary branches; otherwise archive the branch and retain its
   * sales, receipts, employees and inventory for historical reporting.
   */
  void manageBranchRemoval(){
    if(!owner()||user()==null){host.message("Padam branch hanya Owner.");return;}
    final String oid=ownerUid();
    db.collection(ROOT).document(oid).collection("branches")
      .get(com.google.firebase.firestore.Source.SERVER)
      .addOnSuccessListener(branches->{
        if(!owner()||!oid.equals(ownerUid()))return;
        ArrayList<QueryDocumentSnapshot> candidates=new ArrayList<>();
        ArrayList<String> labels=new ArrayList<>();
        for(QueryDocumentSnapshot b:branches){
          if(isPrimaryBranch(b))continue;
          candidates.add(b);
          String name=b.getString("name");
          if(name==null||name.trim().isEmpty())name=b.getId();
          labels.add(name+(Boolean.TRUE.equals(b.getBoolean("archived"))
            ?"  ·  ARKIB":"  ·  AKTIF")+(branchId().equals(b.getId())?"  ·  SEDANG DIPILIH":""));
        }
        if(candidates.isEmpty()){
          host.darkBuilder().setTitle("TIADA BRANCH BOLEH DIPADAM")
            .setMessage("Branch Utama dilindungi kerana menyimpan rekod utama. "+
              "Hanya branch tambahan boleh dipadam atau diarkibkan.")
            .setPositiveButton("OK",null).show();return;
        }
        host.darkBuilder().setTitle("PADAM / ARKIB BRANCH")
          .setItems(labels.toArray(new String[0]),(d,i)->
            branchRemovalActions(candidates.get(i)))
          .setNegativeButton("BATAL",null).show();
      }).addOnFailureListener(e->
        host.message("Gagal baca branch dari Firebase: "+e.getMessage()));
  }
  boolean isPrimaryBranch(com.google.firebase.firestore.DocumentSnapshot branch){
    String name=branch.getString("name");
    return LEGACY_BRANCH.equals(branch.getId())||
      Boolean.TRUE.equals(branch.getBoolean("legacyDeviceData"))||
      "Branch Utama".equalsIgnoreCase(name==null?"":name.trim());
  }
  void branchRemovalActions(QueryDocumentSnapshot b){
    if(!owner()||user()==null||isPrimaryBranch(b)){
      host.message("Branch Utama tidak boleh dipadam.");return;
    }
    String id=b.getId();
    String name=b.getString("name");
    if(name==null||name.trim().isEmpty())name=id;
    final String finalName=name;
    boolean archived=Boolean.TRUE.equals(b.getBoolean("archived"));
    if(branchId().equals(id)){
      host.darkBuilder().setTitle("TUKAR BRANCH DAHULU")
        .setMessage("Branch "+name+" sedang aktif pada telefon Owner. "+
          "Pilih Branch Utama melalui Tukar Branch sebelum memadam.")
        .setPositiveButton("OK",null).show();return;
    }
    if(archived){
      host.darkBuilder().setTitle("BRANCH DIARKIBKAN · "+name)
        .setItems(new String[]{"PULIHKAN BRANCH","PADAM KEKAL · JIKA TIADA DATA"},
          (d,choice)->{
            if(choice==0)confirmRestoreBranch(id,finalName);
            else confirmDeleteEmptyBranch(id,finalName);
          }).setNegativeButton("BATAL",null).show();
    }else{
      host.darkBuilder().setTitle("URUS BRANCH · "+name)
        .setItems(new String[]{"ARKIB · SIMPAN SEMUA REKOD",
          "PADAM KEKAL · BRANCH KOSONG SAHAJA"},
          (d,choice)->{
            if(choice==0)confirmArchiveBranch(id,finalName);
            else confirmDeleteEmptyBranch(id,finalName);
          }).setNegativeButton("BATAL",null).show();
    }
  }
  void confirmArchiveBranch(String id,String name){
    if(!owner()||user()==null||id.equals(branchId())||LEGACY_BRANCH.equals(id))return;
    host.darkBuilder().setTitle("ARKIB BRANCH · "+name)
      .setMessage("Branch akan hilang daripada pilihan operasi aktif, "+
        "pekerja branch ini akan disekat, dan transaksi baharu tidak dibenarkan. "+
        "SEMUA stok, resit, jualan dan sejarah kekal untuk laporan Owner. "+
        "Branch boleh dipulihkan semula. Pastikan order belum bayar diselesaikan.")
      .setPositiveButton("YA, ARKIB",(d,w)->setBranchArchive(id,name,true))
      .setNegativeButton("BATAL",null).show();
  }
  void confirmRestoreBranch(String id,String name){
    if(!owner()||user()==null||id.equals(branchId())||LEGACY_BRANCH.equals(id))return;
    host.darkBuilder().setTitle("PULIHKAN BRANCH · "+name)
      .setMessage("Pulihkan branch kepada status BUKA. Pekerja yang masih "+
        "berdaftar boleh mengakses semula branch ini. Stok dan jualan lama kekal.")
      .setPositiveButton("YA, PULIHKAN",(d,w)->setBranchArchive(id,name,false))
      .setNegativeButton("BATAL",null).show();
  }
  void setBranchArchive(String id,String name,boolean archive){
    if(!owner()||user()==null||id.equals(branchId())||LEGACY_BRANCH.equals(id))return;
    final String oid=ownerUid();
    DocumentReference ref=db.collection(ROOT).document(oid)
      .collection("branches").document(id);
    ref.get(com.google.firebase.firestore.Source.SERVER)
      .addOnSuccessListener(current->{
        if(!owner()||!oid.equals(ownerUid())||!current.exists()||
          isPrimaryBranch(current)||branchId().equals(id))return;
        Map<String,Object> changes=new HashMap<>();
        changes.put("archived",archive);
        changes.put("operatingStatus",archive?"OFFDAY":"OPEN");
        changes.put("operatingReason",archive?"BRANCH DIARKIBKAN":"");
        changes.put("operatingUpdatedBy",user().getUid());
        changes.put("operatingUpdatedAt",
          com.google.firebase.firestore.FieldValue.serverTimestamp());
        ref.update(changes).addOnSuccessListener(done->{
          stockBranchesLoaded=false;
          host.message(name+(archive?" telah diarkibkan. Rekod lama dikekalkan.":
            " dipulihkan dan dibuka semula."));
          manageBranchRemoval();
        }).addOnFailureListener(e->host.message("Gagal kemas kini branch: "+e.getMessage()));
      }).addOnFailureListener(e->host.message("Gagal sahkan branch: "+e.getMessage()));
  }
  void confirmDeleteEmptyBranch(String id,String name){
    if(!owner()||user()==null||id.equals(branchId())||LEGACY_BRANCH.equals(id))return;
    host.darkBuilder().setTitle("PADAM KEKAL BRANCH KOSONG?")
      .setMessage("Hanya branch TAMBAHAN yang tiada stok, sejarah jualan, "+
        "order, sesi, pekerja, resit atau data tempatan boleh dipadam kekal. "+
        "Jika ada rekod, pemadaman akan ditolak; gunakan ARKIB sahaja. "+
        "Branch Utama tidak boleh dipadam.")
      .setPositiveButton("SEMAK & PADAM",(d,w)->deleteOnlyEmptyBranch(id,name))
      .setNegativeButton("BATAL",null).show();
  }
  void deleteOnlyEmptyBranch(String id,String name){
    if(!owner()||user()==null||id.equals(branchId())||LEGACY_BRANCH.equals(id))return;
    final String oid=ownerUid();
    final DocumentReference ref=db.collection(ROOT).document(oid)
      .collection("branches").document(id);
    // Never remove a branch whose phone holds any branch-specific preferences.
    // Even an old unrecognized key may be historical data, so fail safely.
    if(!host.getSharedPreferences("warisan_branch_"+id,Context.MODE_PRIVATE)
      .getAll().isEmpty()){
      host.darkBuilder().setTitle("PADAM DISEKAT")
        .setMessage("Data tempatan branch ini masih ada pada telefon Owner. "+
          "Gunakan ARKIB supaya rekod lama tidak hilang.")
        .setPositiveButton("FAHAM",null).show();return;
    }
    ref.get(com.google.firebase.firestore.Source.SERVER).addOnSuccessListener(doc->{
      if(!owner()||!oid.equals(ownerUid())||!doc.exists()||
        isPrimaryBranch(doc)||branchId().equals(id))return;
      // Closed before checking for remaining documents, so employee writes
      // cannot race this cleanup. If a branch has history it stays archived.
      Map<String,Object> lockdown=new HashMap<>();
      lockdown.put("archived",true);lockdown.put("operatingStatus","OFFDAY");
      lockdown.put("operatingReason","PADAM BRANCH KOSONG");
      lockdown.put("operatingUpdatedBy",user().getUid());
      lockdown.put("operatingUpdatedAt",
        com.google.firebase.firestore.FieldValue.serverTimestamp());
      ref.update(lockdown).addOnSuccessListener(done->
        checkEmptyBranchCollections(ref,name,oid,id))
        .addOnFailureListener(e->host.message("Tak dapat sekat branch untuk pemeriksaan: "+e.getMessage()));
    }).addOnFailureListener(e->host.message("Gagal periksa branch: "+e.getMessage()));
  }
  void checkEmptyBranchCollections(DocumentReference ref,String name,String oid,String id){
    if(!owner()||!oid.equals(ownerUid())||id.equals(branchId()))return;
    // All known app subcollections are checked on SERVER, not cache.
    // Firestore does NOT cascade-delete subcollections automatically.
    final String[] collections={"members","live","liveOrders","daily",
      "stockAdjustments","liveSessions","staffLogins","openingChecks",
      "loginChecks","refundRequests","backups"};
    ArrayList<com.google.android.gms.tasks.Task<com.google.firebase.firestore.QuerySnapshot>> tasks=
      new ArrayList<>();
    for(String collection:collections)
      tasks.add(ref.collection(collection).limit(1)
        .get(com.google.firebase.firestore.Source.SERVER));
    Tasks.whenAllSuccess(tasks).addOnSuccessListener(responses->{
      if(!owner()||!oid.equals(ownerUid())||id.equals(branchId()))return;
      for(Object result:responses){
        com.google.firebase.firestore.QuerySnapshot q=
          (com.google.firebase.firestore.QuerySnapshot)result;
        if(!q.isEmpty()){
          host.darkBuilder().setTitle("BRANCH ADA REKOD · ARKIB SAHAJA")
            .setMessage(name+" mengandungi data Firestore. Branch kini diarkibkan, "+
              "bukan dipadam. Jualan, stok, resit dan sejarah pekerja dikekalkan. "+
              "Boleh dipulihkan melalui PADAM / ARKIB BRANCH.")
            .setPositiveButton("FAHAM",null).show();
          stockBranchesLoaded=false;return;
        }
      }
      // This is only the branch metadata document; queries above prove there
      // are no documents in the app's known collections.
      ref.delete().addOnSuccessListener(v->{
        stockBranchesLoaded=false;
        host.message("Branch kosong "+name+" berjaya dipadam.");
        manageBranchRemoval();
      }).addOnFailureListener(e->host.message(
        "Padam metadata branch gagal. Branch masih diarkibkan: "+e.getMessage()));
    }).addOnFailureListener(e->{
      host.darkBuilder().setTitle("SEMAKAN TIDAK LENGKAP")
        .setMessage("Tidak dapat pastikan semua data kosong. "+
          "Branch kekal diarkibkan (bukan dipadam) untuk keselamatan. "+e.getMessage())
        .setPositiveButton("FAHAM",null).show();
    });
  }

  void inviteStaff(){
    if(host.staffSecurity!=null)host.staffSecurity.createEmployee();
    else host.message("Sila log masuk sebagai Owner.");
  }
  void showInvitations(){
    FirebaseUser u=user();if(u==null||u.getEmail()==null){host.message("Sign in Google dahulu.");return;}
    String email=u.getEmail().trim().toLowerCase(Locale.ROOT);
    db.collection("staffInvites").whereEqualTo("email",email).get()
      .addOnSuccessListener(result->{
        ArrayList<String> labels=new ArrayList<>();
        ArrayList<QueryDocumentSnapshot> inviteList=new ArrayList<>();
        for(QueryDocumentSnapshot doc:result){
          if(!Boolean.TRUE.equals(doc.getBoolean("active")))continue;
          labels.add(doc.getString("branchName")+" (Pekerja)");
          inviteList.add(doc);
        }
        if(inviteList.isEmpty()){host.message("Tiada jemputan untuk "+email);return;}
        host.darkBuilder().setTitle("Jemputan pekerja").setItems(labels.toArray(new String[0]),(d,index)->{
          QueryDocumentSnapshot invite=inviteList.get(index);
          String ownerId=invite.getString("ownerUid"),branch=invite.getString("branchId");
          String name=invite.getString("branchName");
          if(ownerId==null||branch==null){host.message("Jemputan tidak lengkap");return;}
          DocumentReference member=db.collection(ROOT).document(ownerId)
              .collection("branches").document(branch).collection("members").document(u.getUid());
          Map<String,Object> record=new HashMap<>();
          record.put("uid",u.getUid());record.put("email",email);
          record.put("role","STAFF");record.put("active",true);
          record.put("invitationId",invite.getId());record.put("joinedAt",new Date());
          member.set(record).addOnSuccessListener(done->{
            setSession(u.getUid(),ownerId,branch,name==null?"Branch":name,"STAFF");
            host.message("Branch pekerja berjaya dipilih.");
          }).addOnFailureListener(e->host.message("Tidak boleh terima jemputan: "+e.getMessage()));
        }).setNegativeButton("Batal",null).show();
      }).addOnFailureListener(e->host.message("Gagal semak jemputan: "+e.getMessage()));
  }
  void chooseBranch(){
    if(staff()){
      host.message("Akaun pekerja tidak boleh masuk tetapan atau daftar Owner.");
      return;
    }
    FirebaseUser u=user();if(u==null){host.message("Login Google dahulu");return;}
    checkMayBecomeOwner(u,()->chooseOwnerBranchVerified(u,true));
  }
  void chooseOwnerBranchVerified(FirebaseUser u){
    chooseOwnerBranchVerified(u,false);
  }
  void chooseOwnerBranchVerified(FirebaseUser u,boolean manualSelection){
    db.collection(ROOT).document(u.getUid()).get()
      .addOnSuccessListener(doc->{
        if(!doc.exists()){
          host.activePage=0;host.draw();
          host.message("Belum ada akaun Owner. Pilih Daftar Owner / Sign Up.");
          return;
        }
        String ownerId=u.getUid();
        db.collection(ROOT).document(ownerId).collection("branches").get()
          .addOnSuccessListener(result->{
            ArrayList<String> labels=new ArrayList<>(),ids=new ArrayList<>();
            for(QueryDocumentSnapshot branch:result){
              if(Boolean.TRUE.equals(branch.getBoolean("archived")))continue;
              String label=branch.getString("name");
              labels.add(label==null||label.trim().isEmpty()?branch.getId():label);
              ids.add(branch.getId());
            }
            if(labels.isEmpty()){host.message("Semua branch diarkibkan. Pulihkan branch melalui menu BRANCH.");return;}
            if(manualSelection){
              host.darkBuilder().setTitle("TUKAR BRANCH OWNER")
                .setItems(labels.toArray(new String[0]),(d,which)->
                  setSession(u.getUid(),ownerId,ids.get(which),labels.get(which),"OWNER"))
                .setNegativeButton("BATAL",null).show();
              return;
            }
            // Prefer legacy Branch Utama: its original local sales/stock
            // preferences must not be silently replaced with another branch.
            int primary=-1;
            for(int i=0;i<ids.size();i++){
              if(LEGACY_BRANCH.equals(ids.get(i))){primary=i;break;}
            }
            if(primary<0)for(int i=0;i<labels.size();i++){
              if("Branch Utama".equalsIgnoreCase(
                labels.get(i)==null?"":labels.get(i).trim())){
                primary=i;break;
              }
            }
            if(primary>=0){
              String display=labels.get(primary)==null?"Branch Utama":labels.get(primary);
              setSession(u.getUid(),ownerId,ids.get(primary),display,"OWNER");
              return;
            }
            // Never automatically select a non-primary shop.
            host.darkBuilder().setTitle("Pilih Branch Owner")
              .setMessage("Branch Utama tidak ditemui. Pilih branch dengan teliti.")
              .setItems(labels.toArray(new String[0]),(d,which)->
                 setSession(u.getUid(),ownerId,ids.get(which),labels.get(which),"OWNER"))
              .setNegativeButton("Batal",null).show();
          }).addOnFailureListener(e->host.message("Gagal senaraikan branch: "+e.getMessage()));
      }).addOnFailureListener(e->host.message("Gagal semak branch: "+e.getMessage()));
  }
}
