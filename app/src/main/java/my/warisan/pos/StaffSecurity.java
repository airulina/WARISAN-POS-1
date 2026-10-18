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
import com.google.firebase.functions.FirebaseFunctions;
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
  final FirebaseFunctions functions;
  StaffSecurity(MainActivity a){
    host=a;accounts=a.branchAccounts;db=a.firestoreForStaff();
    functions=FirebaseFunctions.getInstance("asia-southeast1");
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
        Map<String,Object> args=new HashMap<>();args.put("email",mail);args.put("password",p);
        args.put("branchId",accounts.branchId());
        functions.getHttpsCallable("createStaffAccount").call(args)
          .addOnSuccessListener(res->host.message("Pekerja berjaya didaftarkan. Kongsi password sementara secara peribadi."))
          .addOnFailureListener(e->host.message("Gagal daftar pekerja: "+e.getMessage()+
              ". Pastikan Firebase Cloud Functions telah dipasang."));
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
          if(!"APPROVED".equals(selected.getString("status"))){
            host.message("Status refund: "+selected.getString("status"));return;
          }
          claimRefund(selected.getReference(),selected.getString("saleFingerprint"));
        }).setNegativeButton("Tutup",null).show();
      }).addOnFailureListener(e->host.message("Tak dapat semak refund: "+e.getMessage()));
  }
  void claimRefund(DocumentReference ref,String key){
    if(!accounts.staff()||accounts.user()==null)return;
    JSONArray sales=host.entries("stock_sales");
    int match=-1;
    for(int i=0;i<sales.length();i++){
      JSONObject sale=sales.optJSONObject(i);
      if(sale!=null&&!sale.optBoolean("refunded",false)&&
         key.equals(fingerprint(sale,host.saleTotal(sale)))){match=i;break;}
    }
    if(match<0){host.message("Transaksi tidak dijumpai dalam telefon ini. Refund tidak dibuat.");return;}
    final int saleIndex=match;
    String uid=accounts.user().getUid();
    db.runTransaction(tx->{
      com.google.firebase.firestore.DocumentSnapshot doc=tx.get(ref);
      if(!"APPROVED".equals(doc.getString("status"))||
          !uid.equals(doc.getString("employeeUid"))||
          !key.equals(doc.getString("saleFingerprint")))throw new IllegalStateException("Kelulusan tidak sah");
      Map<String,Object> update=new HashMap<>();
      update.put("status","CLAIMED");update.put("claimedAt",FieldValue.serverTimestamp());
      tx.update(ref,update);return null;
    }).addOnSuccessListener(v->{
      boolean success=host.refundSaleAuthorized(saleIndex,ref.getId());
      if(success){
        ref.update("status","COMPLETED","completedAt",FieldValue.serverTimestamp())
          .addOnFailureListener(e->host.message("Refund lokal direkod, status cloud belum disahkan."));
      } else host.message("Refund lokal gagal selepas claim. Hubungi Owner untuk semakan.");
    }).addOnFailureListener(e->host.message("Refund tidak dibenarkan: "+e.getMessage()));
  }
}
