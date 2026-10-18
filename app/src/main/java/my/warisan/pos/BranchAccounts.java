package my.warisan.pos;

import android.content.Context;
import android.content.SharedPreferences;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.view.View;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import com.google.firebase.firestore.WriteBatch;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
  static final String ROOT="workspaces";
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
  boolean blocked(){return configured()&&!active();}
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
    return host.getSharedPreferences("warisan_branch_"+branchId(),Context.MODE_PRIVATE);
  }
  void setSession(String uid,String ownerUid,String branchId,String branchName,String role){
    identity.edit().putBoolean("configured",true).putString("sessionUid",uid)
      .putString("ownerUid",ownerUid).putString("branchId",branchId)
      .putString("branchName",branchName).putString("role",role).commit();
    host.loadMenu();host.activePage=0;host.draw();
  }
  void migrateLegacyIfFirstBranch(String branchId){
    SharedPreferences target=host.getSharedPreferences("warisan_branch_"+branchId,Context.MODE_PRIVATE);
    if(!target.getAll().isEmpty())return;
    SharedPreferences.Editor e=target.edit();
    for(Map.Entry<String,?> item:host.getPreferences(Context.MODE_PRIVATE).getAll().entrySet()){
      Object v=item.getValue();String k=item.getKey();
      if(v instanceof String)e.putString(k,(String)v);
      else if(v instanceof Integer)e.putInt(k,(Integer)v);
      else if(v instanceof Boolean)e.putBoolean(k,(Boolean)v);
      else if(v instanceof Long)e.putLong(k,(Long)v);
      else if(v instanceof Float)e.putFloat(k,(Float)v);
      else if(v instanceof Set)e.putStringSet(k,(Set<String>)v);
    }
    e.commit();
  }
  void afterLogin(){
    // Revalidate old branch session on a different Google account.
    FirebaseUser u=user();
    if(u==null)return;
    if(active()){host.loadMenu();host.draw();return;}
    host.activePage=5;host.draw();
    host.message("Log masuk berjaya. Pilih daftar Owner atau jemputan pekerja.");
  }
  void showActions(){
    FirebaseUser u=user();
    if(u==null){host.message("Sign in Google dahulu di halaman Account");return;}
    host.action("Branch aktif: "+summary(),()->chooseBranch());
    if(!configured() || !active())host.action("Daftar sebagai Owner baharu",()->registerOwner());
    if(owner()){
      host.action("+ Tambah Branch",()->createBranch());
      host.action("+ Daftar / jemput pekerja",()->inviteStaff());
      host.action("Tukar Branch",()->chooseBranch());
    }
    host.action("Semak jemputan pekerja (Google)",()->showInvitations());
    host.action("Pilih branch pekerja yang sedia ada",()->chooseBranch());
  }
  EditText entry(String hint){
    EditText e=new EditText(host);e.setSingleLine(true);e.setHint(hint);host.styleInput(e);return e;
  }
  void registerOwner(){
    FirebaseUser u=user();
    if(u==null){host.message("Sign in Google untuk daftar Owner.");return;}
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
            host.message("Owner sudah didaftarkan. Guna Tukar Branch untuk akses semula.");return;
          }
          WriteBatch batch=db.batch();batch.set(workspace,root);batch.set(first,loc);
          batch.commit().addOnSuccessListener(done->{
            migrateLegacyIfFirstBranch(first.getId());
            setSession(ownerId,ownerId,first.getId(),name,"OWNER");
            host.message("Owner dan branch pertama berjaya didaftarkan.");
          }).addOnFailureListener(e->host.message("Daftar Owner gagal: "+e.getMessage()));
        }).addOnFailureListener(e->host.message("Gagal semak Owner: "+e.getMessage()));
      }).setNegativeButton("Batal",null).show();
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
  void inviteStaff(){
    if(!owner()){host.message("Owner sahaja boleh daftar pekerja.");return;}
    EditText email=entry("Email Google pekerja");
    host.darkBuilder().setTitle("Daftar / Jemput Pekerja")
      .setMessage("Pekerja install APK yang sama dan Sign in menggunakan email Google yang dijemput. Jangan kongsi password Owner.")
      .setView(email).setPositiveButton("Jemput",(d,w)->{
        String address=email.getText().toString().trim().toLowerCase(Locale.ROOT);
        if(!android.util.Patterns.EMAIL_ADDRESS.matcher(address).matches()){
          host.message("Alamat email tidak sah.");return;
        }
        Map<String,Object> invite=new HashMap<>();
        invite.put("email",address);invite.put("ownerUid",ownerUid());
        invite.put("branchId",branchId());invite.put("branchName",branchName());
        invite.put("role","STAFF");invite.put("active",true);invite.put("createdAt",new Date());
        db.collection("staffInvites").add(invite)
           .addOnSuccessListener(doc->host.message("Jemputan disimpan. Pekerja perlu login guna email yang sama."))
           .addOnFailureListener(e->host.message("Jemputan gagal: "+e.getMessage()));
      }).setNegativeButton("Batal",null).show();
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
    FirebaseUser u=user();if(u==null){host.message("Sign in Google dahulu");return;}
    // A new phone or restored installation can find the Owner's branches by Google UID.
    db.collection(ROOT).document(u.getUid()).get().addOnSuccessListener(doc->{
      if(!doc.exists()){
        showInvitations();return;
      }
      String ownerId=u.getUid();
      db.collection(ROOT).document(ownerId).collection("branches").get()
        .addOnSuccessListener(result->{
          ArrayList<String> labels=new ArrayList<>(),ids=new ArrayList<>();
          for(QueryDocumentSnapshot branch:result){
            labels.add(branch.getString("name"));ids.add(branch.getId());
          }
          if(labels.isEmpty()){host.message("Belum ada branch");return;}
          host.darkBuilder().setTitle("Pilih Branch Owner")
            .setItems(labels.toArray(new String[0]),(d,which)->
               setSession(u.getUid(),ownerId,ids.get(which),labels.get(which),"OWNER"))
            .setNegativeButton("Batal",null).show();
        }).addOnFailureListener(e->host.message("Gagal senaraikan branch: "+e.getMessage()));
    }).addOnFailureListener(e->host.message("Gagal semak branch: "+e.getMessage()));
  }
}
