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
  void accountAlert(String title,String detail){
    host.darkBuilder().setTitle(title).setMessage(detail)
      .setPositiveButton("FAHAM",null).show();
  }
  /** Firebase server is the source of truth for the role. Local device data
      can NEVER grant a worker Owner access, even after account switches. */
  void checkMayBecomeOwner(FirebaseUser candidate,Runnable allowed){
    if(candidate==null){host.message("Login Google dahulu.");return;}
    if(staff()&&candidate.getUid().equals(identity.getString("sessionUid",""))){
      accountAlert("Akaun Pekerja","Email ini masih didaftarkan sebagai pekerja. "+
        "Minta Owner BUANG AKSES pekerja dahulu sebelum daftar Owner.");return;
    }
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
    checkMayBecomeOwner(u,()->{
      if(active()&&owner()){host.loadMenu();host.draw();return;}
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
    EditText e=new EditText(host);e.setSingleLine(true);e.setHint(hint);host.styleInput(e);return e;
  }
  void registerOwner(){
    FirebaseUser u=user();
    if(u==null){host.message("Sign in Google untuk daftar Owner.");return;}
    if(staff()){accountAlert("Akses pekerja","Pekerja tidak dibenarkan daftar Owner.");return;}
    checkMayBecomeOwner(u,()->registerOwnerVerified(u));
  }
  void registerOwnerVerified(FirebaseUser u){
    if(hasLegacyRecords()){
      linkLegacyOwnerVerified(u);return;
    }
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
    checkMayBecomeOwner(u,()->chooseOwnerBranchVerified(u));
  }
  void chooseOwnerBranchVerified(FirebaseUser u){
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
