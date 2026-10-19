package my.warisan.pos;

import android.app.AlertDialog;
import android.content.Context;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.text.InputType;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Staff account creation and audited refund approval. Not a shared POS transaction ledger. */
final class StaffSecurity {
  final MainActivity host;
  final BranchAccounts accounts;
  final FirebaseFirestore db;
  final FirebaseAuth staffProvisionAuth;
  StaffSecurity(MainActivity a){
    host=a;accounts=a.branchAccounts;db=a.firestoreForStaff();
    FirebaseApp secondary;
    try { secondary=FirebaseApp.getInstance("warisan_staff_provisioning"); }
    catch(IllegalStateException e) {
      secondary=FirebaseApp.initializeApp(a,FirebaseApp.getInstance().getOptions(),"warisan_staff_provisioning");
    }
    staffProvisionAuth=FirebaseAuth.getInstance(secondary);
  }
  EditText input(String hint,boolean secret){
    EditText f=new EditText(host);
    f.setHint(hint);f.setSingleLine(true);
    f.setInputType(secret?InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD:
      InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
    host.styleInput(f);return f;
  }
  void createEmployee(){
    if(!accounts.owner()){host.message("Hanya Owner boleh daftar pekerja.");return;}
    LinearLayout layout=host.col();layout.setPadding(host.dp(18),0,host.dp(18),0);
    EditText email=input("Email pekerja",false),password=input("Password sementara pekerja (min 10 aksara)",true);
    EditText confirm=input("Sahkan password pekerja",true);
    host.add(layout,email,-1,-2);host.add(layout,password,-1,-2);host.add(layout,confirm,-1,-2);
    host.darkBuilder().setTitle("Owner · Daftar Pekerja")
      .setMessage("Akaun pekerja mempunyai email dan password sendiri. Owner tidak perlu beri kata laluan Google atau Owner. Kata laluan tidak disimpan dalam database POS.")
      .setView(layout).setPositiveButton("DAFTAR",(d,w)->{
        String mail=email.getText().toString().trim().toLowerCase(Locale.ROOT);
        String p=password.getText().toString();
        if(!android.util.Patterns.EMAIL_ADDRESS.matcher(mail).matches()){host.message("Email pekerja tidak sah.");return;}
        if(p.length()<10||!p.equals(confirm.getText().toString())){host.message("Password minimum 10 aksara dan mesti sepadan.");return;}
        // Secondary FirebaseAuth means the Owner stays signed in.
        // Email/Password + Firestore work on the no-cost Spark plan.
        if(!accounts.owner()){host.message("Sesi Owner sudah tamat.");return;}
        final String oid=accounts.ownerUid(),bid=accounts.branchId(),name=accounts.branchName();
        staffProvisionAuth.createUserWithEmailAndPassword(mail,p).addOnSuccessListener(result->{
          FirebaseUser worker=result.getUser();
          if(worker==null){host.message("Gagal cipta akaun pekerja.");return;}
          Map<String,Object> profile=new HashMap<>();
          profile.put("uid",worker.getUid());profile.put("email",mail);
          profile.put("role","STAFF");profile.put("active",true);
          profile.put("ownerUid",oid);profile.put("branchId",bid);
          profile.put("branchName",name);profile.put("createdAt",new Date());
          DocumentReference member=db.collection("workspaces").document(oid)
            .collection("branches").document(bid).collection("members").document(worker.getUid());
          Map<String,Object> m=new HashMap<>(profile);
          com.google.firebase.firestore.WriteBatch batch=db.batch();
          batch.set(db.collection("staffProfiles").document(worker.getUid()),profile);
          batch.set(member,m);
          batch.commit().addOnSuccessListener(done->{
            worker.sendEmailVerification().addOnCompleteListener(verification->{
              staffProvisionAuth.signOut();
              host.message("Akaun pekerja didaftarkan. Sahkan email sebelum login.");
            });
          }).addOnFailureListener(error->{
            worker.delete().addOnCompleteListener(deleted->staffProvisionAuth.signOut());
            host.message("Profil pekerja gagal disimpan: "+error.getMessage());
          });
        }).addOnFailureListener(error->host.message(
          "Gagal daftar pekerja. Aktifkan Email/Password dalam Firebase Authentication: "+error.getMessage()));

      }).setNegativeButton("Batal",null).show();
  }
  void loginEmployee(){
    LinearLayout form=host.col();form.setPadding(host.dp(18),0,host.dp(18),0);
    EditText email=input("Email pekerja",false),password=input("Password pekerja",true);
    host.add(form,email,-1,-2);host.add(form,password,-1,-2);
    host.darkBuilder().setTitle("Log Masuk Pekerja")
      .setMessage("Masukkan email dan password yang Owner sediakan.")
      .setView(form).setPositiveButton("LOGIN",(d,w)->{
        String mail=email.getText().toString().trim().toLowerCase(Locale.ROOT);
        String secret=password.getText().toString();
        if(mail.isEmpty()||secret.isEmpty()){host.message("Isi email dan password.");return;}
        accounts.auth.signInWithEmailAndPassword(mail,secret).addOnSuccessListener(result->{
          FirebaseUser u=accounts.user();
          if(u==null){host.message("Login gagal.");return;}
          if(!u.isEmailVerified()){
            u.sendEmailVerification();
            accounts.auth.signOut();
            host.message("Sahkan email pekerja dahulu, kemudian login semula.");
            host.draw();return;
          }
          db.collection("staffProfiles").document(u.getUid()).get()
            .addOnSuccessListener(profile->{
              if(!profile.exists()||!Boolean.TRUE.equals(profile.getBoolean("active"))
                ||!"STAFF".equals(profile.getString("role"))){
                accounts.auth.signOut();host.message("Akaun pekerja belum aktif atau telah ditutup.");host.draw();return;
              }
              String ownerUid=profile.getString("ownerUid"),branchId=profile.getString("branchId");
              String name=profile.getString("branchName");
              if(ownerUid==null||branchId==null||ownerUid.isEmpty()||branchId.isEmpty()){
                accounts.auth.signOut();host.message("Profil pekerja tidak lengkap.");host.draw();return;
              }
              // Online role check; only the Firebase Admin backend can create profiles.
              accounts.setSession(u.getUid(),ownerUid,branchId,
                name==null?"Branch":name,"STAFF");
              host.message("Login pekerja berjaya.");
            }).addOnFailureListener(e->{accounts.auth.signOut();host.draw();host.message("Pengesahan pekerja gagal: "+e.getMessage());});
        }).addOnFailureListener(e->host.message("Login gagal: "+e.getMessage()));
      }).setNegativeButton("Batal",null).show();
  }
  DocumentReference refundDoc(String id){
    return db.collection("workspaces").document(accounts.ownerUid())
      .collection("branches").document(accounts.branchId())
      .collection("refundRequests").document(id);
  }
  String fingerprint(JSONObject sale,int total){
    return sale.optString("time","")+"|"+host.entryDay(sale)+"|"+
      sale.optInt("order",0)+"|"+total;
  }
  void requestRefund(int index){
    if(!accounts.staff()||accounts.user()==null){host.message("Akses pekerja diperlukan.");return;}
    JSONArray sales=host.entries("stock_sales");
    JSONObject sale=sales.optJSONObject(index);
    if(sale==null||sale.optBoolean("refunded",false)){host.message("Transaksi tidak tersedia.");return;}
    String day=host.entryDay(sale);
    if(!host.businessDay().equals(day)){host.message("Pekerja boleh mohon refund hari ini sahaja.");return;}
    int total=host.saleTotal(sale);
    String key=fingerprint(sale,total);
    DocumentReference request=db.collection("workspaces").document(accounts.ownerUid())
      .collection("branches").document(accounts.branchId()).collection("refundRequests").document();
    Map<String,Object> entry=new HashMap<>();
    entry.put("ownerUid",accounts.ownerUid());entry.put("branchId",accounts.branchId());
    entry.put("employeeUid",accounts.user().getUid());entry.put("employeeEmail",accounts.user().getEmail());
    entry.put("businessDay",day);entry.put("saleFingerprint",key);
    entry.put("receipt",host.saleItemsText(sale));entry.put("amount",total);
    entry.put("status","PENDING");entry.put("requestedAt",FieldValue.serverTimestamp());
    request.set(entry).addOnSuccessListener(v->host.message("Permintaan refund dihantar. Owner perlu approve di Account."))
      .addOnFailureListener(e->host.message("Gagal hantar refund: "+e.getMessage()));
  }
  void ownerReview(){
    if(!accounts.owner()){host.message("Hanya Owner boleh approve.");return;}
    db.collection("workspaces").document(accounts.ownerUid())
      .collection("branches").document(accounts.branchId()).collection("refundRequests")
      .whereEqualTo("status","PENDING").get().addOnSuccessListener(results->{
        ArrayList<QueryDocumentSnapshot> docs=new ArrayList<>();
        ArrayList<String> labels=new ArrayList<>();
        for(QueryDocumentSnapshot item:results){docs.add(item);
          labels.add(item.getString("businessDay")+" · "+host.money(item.getLong("amount")==null?0:item.getLong("amount").intValue())+
            " · "+item.getString("employeeEmail"));}
        if(docs.isEmpty()){host.message("Tiada refund menunggu kelulusan.");return;}
        host.darkBuilder().setTitle("Refund menunggu Owner").setItems(labels.toArray(new String[0]),(d,at)->{
          QueryDocumentSnapshot item=docs.get(at);
          host.darkBuilder().setTitle("Kelulusan Refund")
            .setMessage("Pekerja: "+item.getString("employeeEmail")+
              "\nTarikh: "+item.getString("businessDay")+
              "\nJumlah: "+host.money(item.getLong("amount")==null?0:item.getLong("amount").intValue())+
              "\nItem: "+item.getString("receipt")+
              "\n\nPastikan transaksi betul sebelum approve.")
            .setPositiveButton("APPROVE",(dialog,which)->setApproval(item.getReference(),"APPROVED"))
            .setNeutralButton("TOLAK",(dialog,which)->setApproval(item.getReference(),"DENIED"))
            .setNegativeButton("BATAL",null).show();
        }).setNegativeButton("Tutup",null).show();
      }).addOnFailureListener(e->host.message("Gagal semak refund: "+e.getMessage()));
  }
  void setApproval(DocumentReference doc,String value){
    if(!accounts.owner())return;
    db.runTransaction(tx->{
      com.google.firebase.firestore.DocumentSnapshot current=tx.get(doc);
      if(!"PENDING".equals(current.getString("status")))throw new IllegalStateException("Permintaan sudah diproses");
      Map<String,Object> update=new HashMap<>();
      update.put("status",value);update.put("decidedBy",accounts.user().getUid());
      update.put("decidedAt",FieldValue.serverTimestamp());
      tx.update(doc,update);
      return null;
    }).addOnSuccessListener(v->host.message("Keputusan Owner disimpan: "+value))
      .addOnFailureListener(e->host.message("Keputusan gagal: "+e.getMessage()));
  }
  void showMyRefunds(){
    if(!accounts.staff()||accounts.user()==null)return;
    db.collection("workspaces").document(accounts.ownerUid())
      .collection("branches").document(accounts.branchId()).collection("refundRequests")
      .whereEqualTo("employeeUid",accounts.user().getUid()).get()
      .addOnSuccessListener(results->{
        ArrayList<QueryDocumentSnapshot> docs=new ArrayList<>();
        ArrayList<String> labels=new ArrayList<>();
        for(QueryDocumentSnapshot doc:results){docs.add(doc);
          labels.add(doc.getString("businessDay")+" · "+
            doc.getString("status")+" · "+
            host.money(doc.getLong("amount")==null?0:doc.getLong("amount").intValue()));}
        if(docs.isEmpty()){host.message("Belum ada permintaan refund.");return;}
        host.darkBuilder().setTitle("Status Refund").setItems(labels.toArray(new String[0]),(dialog,index)->{
          QueryDocumentSnapshot selected=docs.get(index);
          if(!"APPROVED".equals(selected.getString("status"))&&!"CLAIMED".equals(selected.getString("status"))){
            host.message("Status refund: "+selected.getString("status"));return;
          }
          claimRefund(selected.getReference(),selected.getString("saleFingerprint"));
        }).setNegativeButton("Tutup",null).show();
      }).addOnFailureListener(e->host.message("Tak dapat semak refund: "+e.getMessage()));
  }
  void claimRefund(DocumentReference ref,String key){
    if(!accounts.staff()||accounts.user()==null)return;
    JSONArray sales=host.entries("stock_sales");
    int match=-1;String orderId="";
    for(int i=0;i<sales.length();i++){
      JSONObject sale=sales.optJSONObject(i);
      if(sale!=null&&!sale.optBoolean("refunded",false)&&
        key.equals(fingerprint(sale,host.saleTotal(sale)))){
        match=i;orderId=sale.optString("liveOrderId","");break;
      }
    }
    if(match<0){host.message("Jualan asal tidak ada pada telefon ini.");return;}
    if(orderId.isEmpty()){host.message("Refund lama bukan transaksi Live. Minta Owner urus.");return;}
    if(!host.live.usable()){host.message("Perlu sambungan Live untuk refund.");return;}
    final int index=match;final String id=orderId,workerUid=accounts.user().getUid();
    db.runTransaction(tx->{
      com.google.firebase.firestore.DocumentSnapshot doc=tx.get(ref);
      String status=doc.getString("status");
      if(!workerUid.equals(doc.getString("employeeUid"))||
        !key.equals(doc.getString("saleFingerprint"))||
        (!"APPROVED".equals(status)&&!"CLAIMED".equals(status)))
        throw new IllegalStateException("Refund belum diluluskan");
      if("APPROVED".equals(status)){
        Map<String,Object> update=new HashMap<>();
        update.put("status","CLAIMED");
        update.put("claimedAt",FieldValue.serverTimestamp());
        tx.update(ref,update);
      }
      return null;
    }).addOnSuccessListener(v->{
      // Cloud sales totals reversed atomically BEFORE changing local records.
      // CLAIMED requests may be retried safely if the phone crashes.
      host.live.refund(id,ref.getId(),()->{
        boolean ok=host.refundSaleAuthorized(index,ref.getId());
        if(ok){
          ref.update("status","COMPLETED","completedAt",FieldValue.serverTimestamp())
            .addOnFailureListener(e->host.message("Refund lokal selesai; status cloud perlu semak semula."));
        }else host.message("Refund cloud sudah diproses. Rekod telefon perlu semakan manual oleh Owner.");
      });
    }).addOnFailureListener(e->host.message("Refund tak dibenarkan: "+e.getMessage()));
  }

}
