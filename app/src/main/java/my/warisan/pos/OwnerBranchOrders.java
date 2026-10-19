package my.warisan.pos;

import android.graphics.Color;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Read-only orders from other branches. Payment/cancel stays on the originating branch. */
final class OwnerBranchOrders {
  private final MainActivity host;
  private final FirebaseFirestore db;
  private String ownerKey="",error="";
  private boolean loading=false,loaded=false;
  private int remaining=0;
  private final ArrayList<RemoteOrder> orders=new ArrayList<>();
  static final class RemoteOrder {
    String branch,id,number,day,status,time;
    boolean numbered;
    long total;
    List<?> qty;
    long createdAt;
  }
  OwnerBranchOrders(MainActivity activity){
    host=activity;db=activity.firestoreForStaff();
  }
  void refresh(){
    ownerKey="";loaded=false;loading=false;orders.clear();error="";
    load();
  }
  void load(){
    if(!host.branchAccounts.owner())return;
    String owner=host.branchAccounts.ownerUid();
    if(owner==null||owner.isEmpty())return;
    if(owner.equals(ownerKey)&&(loading||loaded))return;
    ownerKey=owner;loading=true;loaded=false;error="";orders.clear();
    db.collection("workspaces").document(owner).collection("branches").get()
      .addOnSuccessListener(branches->{
        if(!owner.equals(ownerKey))return;
        ArrayList<QueryDocumentSnapshot> others=new ArrayList<>();
        for(QueryDocumentSnapshot b:branches){
          if(!b.getId().equals(host.branchAccounts.branchId()))others.add(b);
        }
        remaining=others.size();
        if(remaining==0){finish(owner);return;}
        for(QueryDocumentSnapshot b:others){
          String name=b.getString("name");
          if(name==null||name.trim().isEmpty())name="Branch "+b.getId();
          final String branchName=name;
          b.getReference().collection("liveOrders")
            .orderBy("createdAt",Query.Direction.DESCENDING).limit(15).get()
            .addOnSuccessListener(snapshot->{
              if(!owner.equals(ownerKey))return;
              for(DocumentSnapshot doc:snapshot.getDocuments()){
                RemoteOrder o=new RemoteOrder();
                o.branch=branchName;o.id=doc.getId();
                Long no=doc.getLong("receiptNo");
                o.numbered=no!=null&&no>0;
                o.number=o.numbered?String.format(Locale.US,"#%04d",no):
                  doc.getId().substring(0,Math.min(10,doc.getId().length())).toUpperCase(Locale.US);
                o.day=doc.getString("day");
                o.status=doc.getString("status");
                Long total=doc.getLong("total");o.total=total==null?0:total;
                o.qty=(List<?>)doc.get("qty");
                com.google.firebase.Timestamp time=doc.getTimestamp("createdAt");
                o.createdAt=time==null?0:time.toDate().getTime();
                o.time=time==null?"":new SimpleDateFormat("dd/MM HH:mm",Locale.US).format(time.toDate());
                orders.add(o);
              }
              oneFinished(owner);
            })
            .addOnFailureListener(e->{
              if(!owner.equals(ownerKey))return;
              error="Sebahagian order branch tidak dapat dibaca. Sila refresh bila online.";
              oneFinished(owner);
            });
        }
      }).addOnFailureListener(e->{
        if(!owner.equals(ownerKey))return;
        error="Gagal mengambil senarai branch. Semak internet atau akses Firebase.";
        finish(owner);
      });
  }
  private void oneFinished(String owner){
    if(--remaining<=0)finish(owner);
  }
  private void finish(String owner){
    if(!owner.equals(ownerKey))return;
    loading=false;loaded=true;
    Collections.sort(orders,(a,b)->Long.compare(b.createdAt,a.createdAt));
    if(host.activePage==11)host.draw();
  }
  void draw(){
    if(!host.branchAccounts.owner())return;
    load();
    host.gap(host.body,12);
    host.add(host.body,host.text("ORDER DARI BRANCH LAIN · CLOUD",13,host.gold,true),-1,-2);
    host.gap(host.body,7);
    host.action("↻  REFRESH ORDER SEMUA BRANCH",()->refresh());
    if(loading){
      host.add(host.body,host.text("Memuatkan order daripada branch lain...",12,host.muted,false),-1,-2);
      return;
    }
    if(!error.isEmpty())
      host.add(host.body,host.text(error,12,host.gold,true),-1,-2);
    if(orders.isEmpty()){
      host.add(host.body,host.text("Tiada order Cloud branch lain yang dapat dipaparkan.",12,host.muted,false),-1,-2);
      return;
    }
    for(RemoteOrder order:orders){
      LinearLayout card=host.col();
      card.setPadding(host.dp(13),host.dp(12),host.dp(13),host.dp(12));
      card.setBackground(host.shape(host.surface,13));
      LinearLayout top=host.row();
      top.addView(host.text("🏬  "+order.branch,14,host.ink,true),
        new LinearLayout.LayoutParams(0,-2,1));
      host.add(top,host.text(OwnerCashflow.rm(order.total),15,host.gold,true),-2,-2);
      host.add(card,top,-1,-2);host.gap(card,5);
      host.add(card,host.text((order.numbered?"NO. RESIT":"RUJUKAN CLOUD")+
        " · "+order.number,12,host.blue,true),-1,-2);
      host.gap(card,3);
      String date=order.time.isEmpty()?(order.day==null?"":order.day):order.time;
      host.add(card,host.text("Dari: "+order.branch+"   ·   "+date+
        "   ·   "+status(order.status),11,host.muted,false),-1,-2);
      StringBuilder products=new StringBuilder();
      if(order.qty!=null){
        for(int i=0;i<Math.min(order.qty.size(),host.names.length);i++){
          Object value=order.qty.get(i);
          if(!(value instanceof Number))continue;
          int n=((Number)value).intValue();if(n<=0)continue;
          if(products.length()>0)products.append("  •  ");
          products.append(host.names[i]).append(" × ").append(n);
        }
      }
      if(products.length()>0){
        host.gap(card,6);
        host.add(card,host.text(products.toString(),12,host.ink,false),-1,-2);
      }
      host.add(host.body,card,-1,-2);host.gap(host.body,7);
    }
    host.add(host.body,host.text("Order Cloud dari branch lain untuk pemantauan Owner sahaja. "+
      "Bayar atau batal dibuat pada branch asal; rekod tidak diubah dari paparan ini.",
      11,host.muted,false),-1,-2);
  }
  private static String status(String value){
    if("PAID".equals(value))return "TELAH BAYAR";
    if("PENDING".equals(value))return "BELUM BAYAR";
    if("CANCELLED".equals(value))return "DIBATALKAN";
    if("REFUNDED".equals(value))return "REFUND";
    return value==null?"STATUS TIDAK DIKETAHUI":value;
  }
}
