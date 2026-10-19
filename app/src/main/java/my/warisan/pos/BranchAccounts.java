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
  static final String LEGACY_BRANCH="utama_legacy";
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
  boolean blocked(){return !active();}
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
    identity.edit().putBoolean("configured",true).putString("sessionUid",uid)
      .putString("ownerUid",ownerUid).putString("branchId",branchId)
      .putString("branchName",branchName).putString("role",role).commit();
    host.loadMenu();host.activePage=0;
    if(host.live!=null)host.live.attach();
    host.draw();
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
    FirebaseUser u=user();
    if(u==null)return;
    if(active()){host.loadMenu();host.draw();return;}
    // Do not require any registration after reinstalling an APK update.
    // Reconnect to existing Firestore Owner branches, or offer one-tap use of legacy data.
    db.collection(ROOT).document(u.getUid()).get().addOnSuccessListener(doc->{
      if(doc.exists())chooseBranch();
      else {
        host.activePage=5;host.draw();
        if(hasLegacyRecords())host.message("Data lama masih ada. Tekan 'Guna data lama sebagai Owner' di Account.");
      }
    }).addOnFailureListener(e->{
      host.activePage=5;host.draw();
      host.message("Data POS lama kekal. Sambungan Google boleh dicuba semula kemudian.");
    });
  }
  void showActions(){
    FirebaseUser u=user();
    if(u==null){host.message("Sign in Google dahulu di halaman Account");return;}
    host.action("Branch aktif: "+summary(),()->chooseBranch());
    if(!configured() || !active()){
      if(hasLegacyRecords())host.action("Guna data lama sebagai Owner (tanpa daftar semula)",()->linkLegacyOwner());
      else host.action("Daftar Owner pertama kali",()->registerOwner());
    }
    if(owner()){
      host.action("+ Tambah Branch",()->createBranch());
      host.action("+ Daftar pekerja · Email + Password",()->host.staffSecurity.createEmployee());
      host.action("Refund menunggu kelulusan Owner",()->host.staffSecurity.ownerReview());
      host.action("Tukar Branch",()->chooseBranch());
    }
    if(staff()){
      host.action("Semak status refund saya",()->host.staffSecurity.showMyRefunds());
    }
    if(!active())host.action("Login Pekerja · Email + Password",()->host.staffSecurity.loginEmployee());
  }
  EditText entry(String hint){
    EditText e=new EditText(host);e.setSingleLine(true);e.setHint(hint);host.styleInput(e);return e;
  }
  void registerOwner(){
    if(hasLegacyRecords()){
      linkLegacyOwner();return;
    }
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
            // Updating or signing in again must never create a second Owner account.
            chooseBranch();return;
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
  void linkLegacyOwner(){
    FirebaseUser u=user();
    if(u==null){host.message("Sign in Google dahulu untuk hubungkan Owner lama.");return;}
    if(!hasLegacyRecords()){
      host.message("Tiada rekod lama pada telefon. Gunakan Daftar Owner pertama kali.");return;
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
    FirebaseUser u=user();if(u==null){host.message("Sign in Google dahulu");return;}
    // A new phone or restored installation can find the Owner's branches by Google UID.
    db.collection(ROOT).document(u.getUid()).get().addOnSuccessListener(doc->{
      if(!doc.exists()){
        if(hasLegacyRecords()){
          host.darkBuilder().setTitle("Akaun lama dikesan")
            .setMessage("Gunakan data Warisan POS sedia ada sebagai Owner, tanpa daftar dari awal?")
            .setPositiveButton("Owner lama",(d,w)->linkLegacyOwner())
            .setNeutralButton("Jemputan pekerja",(d,w)->showInvitations())
            .setNegativeButton("Batal",null).show();
        }else showInvitations();
        return;
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
