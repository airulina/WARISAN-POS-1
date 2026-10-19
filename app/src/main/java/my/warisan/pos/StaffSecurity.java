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
import com.google.firebase.firestore.ListenerRegistration;
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
  private ListenerRegistration workerAccessListener;
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
    final String ownerUid=accounts.ownerUid();
    db.collection("workspaces").document(ownerUid).collection("branches").get()
      .addOnSuccessListener(result->{
        if(!accounts.owner()||!ownerUid.equals(accounts.ownerUid()))return;
        ArrayList<String> names=new ArrayList<>(),ids=new ArrayList<>();
        int chosen=0;
        for(QueryDocumentSnapshot branch:result){
          ids.add(branch.getId());
          names.add(branch.getString("name")==null?branch.getId():branch.getString("name"));
          if(branch.getId().equals(accounts.branchId()))chosen=ids.size()-1;
        }
        if(ids.isEmpty()){host.message("Daftar branch dahulu sebelum daftar pekerja.");return;}
        showCreateEmployeeForm(ids,names,chosen);
      }).addOnFailureListener(e->host.message("Senarai branch tidak dapat diambil: "+e.getMessage()));
  }
  void showCreateEmployeeForm(ArrayList<String> ids,ArrayList<String> branchNames,int selected){
    if(!accounts.owner())return;
    LinearLayout layout=host.col();layout.setPadding(host.dp(18),0,host.dp(18),0);
    EditText email=input("Email pekerja",false);
    EditText password=input("Password sementara pekerja (min 10 aksara)",true);
    EditText confirm=input("Sahkan password pekerja",true);
    android.widget.Spinner branches=new android.widget.Spinner(host);
    android.widget.ArrayAdapter<String> adapter=new android.widget.ArrayAdapter<>(host,
      android.R.layout.simple_spinner_dropdown_item,branchNames);
    branches.setAdapter(adapter);branches.setSelection(selected);
    host.add(layout,host.text("EMAIL",12,host.gold,true),-1,-2);
    host.add(layout,email,-1,-2);
    host.add(layout,host.text("PASSWORD",12,host.gold,true),-1,-2);
    host.add(layout,password,-1,-2);host.add(layout,confirm,-1,-2);
    host.add(layout,host.text("BRANCH TETAP · hanya Owner boleh ubah",12,host.gold,true),-1,-2);
    host.add(layout,branches,-1,-2);
    host.darkBuilder().setTitle("DAFTAR PEKERJA")
      .setView(layout).setPositiveButton("DAFTAR",(d,w)->{
        String mail=email.getText().toString().trim().toLowerCase(Locale.ROOT);
        String p=password.getText().toString();
        if(!android.util.Patterns.EMAIL_ADDRESS.matcher(mail).matches()){
          host.message("Email pekerja tidak sah.");return;
        }
        if(p.length()<10||!p.equals(confirm.getText().toString())){
          host.message("Password minimum 10 aksara dan mesti sepadan.");return;
        }
        if(!accounts.owner())return;
        int i=branches.getSelectedItemPosition();
        if(i<0||i>=ids.size()){host.message("Pilih branch yang sah.");return;}
        String ownerUid=accounts.ownerUid(),branchId=ids.get(i),branchName=branchNames.get(i);
        db.collection("workspaces").document(ownerUid)
          .collection("branches").document(branchId)
          .get(com.google.firebase.firestore.Source.SERVER)
          .addOnSuccessListener(branch->{
            if(!accounts.owner()||!ownerUid.equals(accounts.ownerUid()))return;
            if(!branch.exists()){host.message("Branch tidak wujud. Cuba semula.");return;}
            provisionWorker(mail,p,ownerUid,branchId,branchName);
          }).addOnFailureListener(e->showRegistrationProblem(
            friendlyRegistrationError("FIRESTORE",e),e));
      }).setNegativeButton("BATAL",null).show();
  }
  void showRegistrationProblem(String text,Exception ignored){
    // A dialog is deliberately used instead of a truncated Android Toast.
    host.darkBuilder().setTitle("DAFTAR PEKERJA GAGAL")
      .setMessage(text+"\n\nJangan uninstall aplikasi atau padam data POS.")
      .setPositiveButton("FAHAM",null).show();
  }
  String friendlyRegistrationError(String stage,Exception e){
    String code="";
    if(e instanceof com.google.firebase.auth.FirebaseAuthException)
      code=((com.google.firebase.auth.FirebaseAuthException)e).getErrorCode();
    if(e instanceof com.google.firebase.firestore.FirebaseFirestoreException)
      code=((com.google.firebase.firestore.FirebaseFirestoreException)e).getCode().name();
    if(code==null)code="";
    String err=String.valueOf(e==null?"":e.getMessage());
    if("ERROR_EMAIL_ALREADY_IN_USE".equals(code)||err.toLowerCase(Locale.ROOT).contains("already in use")){
      return "Email ini SUDAH ada dalam Firebase Authentication. Bukan masalah Email/Password belum aktif. "+
        "Semak Firebase Console → Authentication → Users. Jika akaun ini baru dicipta dalam cubaan tadi, "+
        "jangan daftar semula berulang kali; hubungi Owner untuk semak rekod pekerja.";
    }
    if("ERROR_OPERATION_NOT_ALLOWED".equals(code)){
      return "Kaedah Email/Password tidak dibenarkan oleh Firebase untuk projek app. "+
        "Sahkan projek Firebase WARISAN POS yang digunakan oleh APK, bukan projek lain.";
    }
    if("ERROR_WEAK_PASSWORD".equals(code)){
      return "Password ditolak Firebase kerana terlalu lemah. Gunakan password yang lebih panjang dan sukar diteka.";
    }
    if("ERROR_INVALID_EMAIL".equals(code)){
      return "Format email tidak diterima oleh Firebase.";
    }
    if("PERMISSION_DENIED".equals(code)||err.contains("PERMISSION_DENIED")){
      return "Firebase Authentication mungkin berjaya, tetapi Firestore menolak akses. "+
        "Buka Firebase Console → Firestore Database → Rules. "+
        "Peraturan keselamatan WARISAN POS terkini perlu disemak dan di-Publish dahulu. "+
        "Aktifkan Email/Password SAHAJA tidak mencukupi.";
    }
    if("UNAVAILABLE".equals(code)||"DEADLINE_EXCEEDED".equals(code)||
      err.toLowerCase(Locale.ROOT).contains("network")){
      return "Server Firebase tidak dapat dicapai. Semak sambungan internet dan cuba lagi.";
    }
    if("NOT_FOUND".equals(code)){
      return "Dokumen Owner/branch atau Firestore Database belum wujud. "+
        "Pastikan Firebase Firestore Database telah diaktifkan untuk projek warisan-pos.";
    }
    String label="FIRESTORE".equals(stage)?"Firestore":"Firebase Authentication";
    return label+" gagal: "+(code.isEmpty()?"":code+" · ")+
      (err.length()>180?err.substring(0,180):err)+
      "\n\nLihat Firebase Console → Authentication → Users dan Firestore Database → Rules.";
  }
  void provisionWorker(String mail,String password,String oid,String bid,String name){
    if(!accounts.owner()||accounts.user()==null||!oid.equals(accounts.ownerUid())){
      showRegistrationProblem("Owner belum log masuk atau branch telah berubah.",null);return;
    }
    if(staffProvisionAuth.getCurrentUser()!=null)staffProvisionAuth.signOut();
    staffProvisionAuth.createUserWithEmailAndPassword(mail,password)
      .addOnSuccessListener(result->{
        FirebaseUser worker=result.getUser();
        if(worker==null){showRegistrationProblem("Firebase tidak memulangkan ID pekerja.",null);return;}
        Map<String,Object> profile=new HashMap<>();
        profile.put("uid",worker.getUid());profile.put("email",mail);
        profile.put("role","STAFF");profile.put("active",true);
        profile.put("ownerUid",oid);profile.put("branchId",bid);
        profile.put("branchName",name);profile.put("createdAt",new Date());
        DocumentReference member=db.collection("workspaces").document(oid)
          .collection("branches").document(bid).collection("members").document(worker.getUid());
        com.google.firebase.firestore.WriteBatch batch=db.batch();
        batch.set(db.collection("staffProfiles").document(worker.getUid()),profile);
        batch.set(member,new HashMap<>(profile));
        Map<String,Object> emailRole=new HashMap<>();
        emailRole.put("email",mail);emailRole.put("ownerUid",oid);
        emailRole.put("workerUid",worker.getUid());emailRole.put("branchId",bid);
        emailRole.put("role","STAFF");emailRole.put("removed",false);
        emailRole.put("updatedAt",FieldValue.serverTimestamp());
        // An email already registered as STAFF is reserved across Google/provider logins.
        // Firestore rules reject Owner signup while this role is active.
        batch.set(db.collection("staffEmails").document(mail),emailRole);
        batch.commit().addOnSuccessListener(done->
          worker.sendEmailVerification().addOnCompleteListener(task->{
            staffProvisionAuth.signOut();
            if(task.isSuccessful())host.darkBuilder().setTitle("PEKERJA BERJAYA DIDAFTAR")
              .setMessage(mail+"\n\nPekerja perlu buka email, sahkan alamat, "+
                "kemudian login melalui Email + Password.")
              .setPositiveButton("OK",null).show();
            else host.darkBuilder().setTitle("AKAUN PEKERJA SUDAH DIDAFTAR")
              .setMessage("Akaun dan akses branch sudah disimpan, tetapi email pengesahan gagal dihantar. "+
                "Semak internet/Firebase Authentication. Jangan daftar email sama kali kedua.")
              .setPositiveButton("OK",null).show();
          })
        ).addOnFailureListener(error->{
          // The Auth user has been created already; try to remove an orphaned
          // account without changing existing Worker / Owner Firebase accounts.
          final String reason=friendlyRegistrationError("FIRESTORE",error);
          worker.delete().addOnCompleteListener(cleanup->{
            staffProvisionAuth.signOut();
            showRegistrationProblem(reason+
              (cleanup.isSuccessful()?"\n\nAkaun sementara dibatalkan.":"\n\nAkaun mungkin masih dalam Firebase Users; jangan cuba daftar email yang sama lagi."),error);
          });
        });
      }).addOnFailureListener(error->
        showRegistrationProblem(friendlyRegistrationError("AUTH",error),error));
  }

  void loginEmployee(){
    LinearLayout form=host.col();form.setPadding(host.dp(18),0,host.dp(18),0);
    EditText email=input("Email",false),password=input("Password",true);
    host.add(form,email,-1,-2);host.add(form,password,-1,-2);
    host.darkBuilder().setTitle("LOG IN")
      .setView(form).setPositiveButton("LOG IN",(d,w)->
        loginWithCredentials(email.getText().toString().trim(),
          password.getText().toString()))
      .setNegativeButton("BATAL",null).show();
  }
  void loginWithCredentials(String rawMail,String secret){
    final String mail=rawMail.trim().toLowerCase(Locale.ROOT);
    if(!android.util.Patterns.EMAIL_ADDRESS.matcher(mail).matches()||secret.isEmpty()){
      host.message("Masukkan Email dan Password yang sah.");return;
    }
    accounts.auth.signInWithEmailAndPassword(mail,secret).addOnSuccessListener(result->{
      FirebaseUser user=result.getUser();
      if(user==null){host.message("Firebase Login gagal.");return;}
      db.collection("staffProfiles").document(user.getUid())
        .get(com.google.firebase.firestore.Source.SERVER)
        .addOnSuccessListener(profile->{
          if(profile.exists()&&"STAFF".equals(profile.getString("role"))){
            if(!Boolean.TRUE.equals(profile.getBoolean("active"))||
              Boolean.TRUE.equals(profile.getBoolean("removed"))){
              accounts.auth.signOut();host.draw();
              host.message("Akaun pekerja disekat atau sudah berhenti.");return;
            }
            if(!user.isEmailVerified()){
              user.sendEmailVerification().addOnCompleteListener(mailTask->{
                accounts.auth.signOut();host.draw();
                host.darkBuilder().setTitle("SAHKAN EMAIL")
                  .setMessage(mailTask.isSuccessful()?
                    "Buka email pekerja dan klik Verify Email, kemudian login semula.":
                    "Email pengesahan tidak berjaya dihantar. Semak inbox, spam atau Firebase.")
                  .setPositiveButton("FAHAM",null).show();
              });return;
            }
            bindWorkerDevice(user,profile);
            return;
          }
          if(!user.isEmailVerified()){
            user.sendEmailVerification();
            accounts.auth.signOut();host.draw();
            host.message("Sahkan Email sebelum Log In Owner.");return;
          }
          // If email credentials are an existing Owner, verify workspace on
          // server before restoring previous branches. Workers cannot elevate.
          accounts.afterLogin();
        }).addOnFailureListener(e->{
          accounts.auth.signOut();host.draw();
          host.message("Semak peranan akaun gagal: "+e.getMessage());
        });
    }).addOnFailureListener(e->host.message("Email / Password gagal: "+e.getMessage()));
  }
  void bindWorkerDevice(FirebaseUser user,com.google.firebase.firestore.DocumentSnapshot profile){
    final String uid=user.getUid();
    final String device=host.getSharedPreferences("warisan_identity",0)
      .getString("device_uid","");
    if(device.isEmpty()){accounts.auth.signOut();host.message("ID telefon tidak tersedia.");return;}
    final String assignedOwner=profile.getString("ownerUid");
    final String assignedBranch=profile.getString("branchId");
    final String assignedName=profile.getString("branchName");
    if(assignedOwner==null||assignedBranch==null){
      accounts.auth.signOut();host.message("Profil branch pekerja tidak lengkap.");return;
    }
    DocumentReference ref=db.collection("staffProfiles").document(uid);
    db.runTransaction(tx->{
      com.google.firebase.firestore.DocumentSnapshot current=tx.get(ref);
      if(!current.exists()||!Boolean.TRUE.equals(current.getBoolean("active"))||
        !"STAFF".equals(current.getString("role"))||
        Boolean.TRUE.equals(current.getBoolean("removed"))||
        !assignedBranch.equals(current.getString("branchId")))
        throw new IllegalStateException("Akaun pekerja tidak aktif atau branch berubah.");
      String bound=current.getString("deviceId");
      if(bound!=null&&!bound.isEmpty()&&!device.equals(bound))
        throw new IllegalStateException("Akaun ini sudah diikat pada telefon lain. Minta Owner RESET PERANTI.");
      if(bound==null||bound.isEmpty())
        tx.update(ref,"deviceId",device,"deviceBoundAt",
          com.google.firebase.firestore.FieldValue.serverTimestamp());
      return null;
    }).addOnSuccessListener(ok->{
      if(accounts.user()==null||!uid.equals(accounts.user().getUid()))return;
      accounts.setSession(uid,assignedOwner,assignedBranch,
        assignedName==null?"Branch":assignedName,"STAFF");
      observeWorker();
      recordStaffLogin(uid,mailOf(user),assignedOwner,assignedBranch,device);
      host.message("Log In berjaya · "+accounts.branchName());
    }).addOnFailureListener(e->{
      accounts.auth.signOut();host.draw();
      host.darkBuilder().setTitle("LOG IN PEKERJA DISEKAT")
        .setMessage(e.getMessage()+"\n\nSetiap pekerja hanya dibenarkan satu pemasangan telefon. "+
          "Jika bertukar telefon, Owner perlu reset peranti dalam Senarai Pekerja. "+
          "Login tidak membuktikan lokasi; GPS diperiksa ketika Opening.")
        .setPositiveButton("FAHAM",null).show();
    });
  }
  String mailOf(FirebaseUser user){
    return user.getEmail()==null?"":user.getEmail().trim().toLowerCase(Locale.ROOT);
  }
  void recordStaffLogin(String uid,String email,String owner,String branch,String device){
    Map<String,Object> log=new HashMap<>();
    log.put("ownerUid",owner);log.put("branchId",branch);
    log.put("employeeUid",uid);log.put("email",email);
    log.put("deviceTag",device.length()>6?device.substring(device.length()-6):device);
    log.put("createdAt",com.google.firebase.firestore.FieldValue.serverTimestamp());
    db.collection("workspaces").document(owner).collection("branches").document(branch)
      .collection("staffLogins").add(log);
  }
  void signupOwnerWithEmail(){
    LinearLayout form=host.col();form.setPadding(host.dp(18),0,host.dp(18),0);
    EditText email=input("Email pengguna baru",false);
    EditText password=input("Password baru (minimum 10 aksara)",true);
    EditText confirm=input("Ulang Password",true);
    host.add(form,email,-1,-2);host.add(form,password,-1,-2);host.add(form,confirm,-1,-2);
    host.darkBuilder().setTitle("SIGN UP · PENGGUNA BARU")
      .setView(form).setPositiveButton("SIGN UP",(d,w)->{
        String mail=email.getText().toString().trim().toLowerCase(Locale.ROOT);
        String pwd=password.getText().toString();
        if(!android.util.Patterns.EMAIL_ADDRESS.matcher(mail).matches()||
          pwd.length()<10||!pwd.equals(confirm.getText().toString())){
          host.message("Semak Email dan Password minimum 10 aksara.");return;
        }
        db.collection("staffEmails").document(mail)
          .get(com.google.firebase.firestore.Source.SERVER)
          .addOnSuccessListener(lock->{
            if(lock.exists()&&!Boolean.TRUE.equals(lock.getBoolean("removed"))){
              host.message("Email ini masih didaftarkan sebagai pekerja. Tidak boleh Sign Up.");return;
            }
            accounts.auth.createUserWithEmailAndPassword(mail,pwd)
              .addOnSuccessListener(created->{
                FirebaseUser u=created.getUser();
                if(u==null){host.message("Daftar gagal.");return;}
                u.sendEmailVerification().addOnCompleteListener(sent->{
                  accounts.auth.signOut();host.draw();
                  host.darkBuilder().setTitle("SAHKAN EMAIL")
                    .setMessage("Akaun berjaya dibuat. Sila sahkan email "+mail+
                      " dahulu, kemudian Log In dan pilih DAFTAR OWNER untuk isi perniagaan dan branch.")
                    .setPositiveButton("FAHAM",null).show();
                });
              }).addOnFailureListener(e->host.message("Sign Up gagal: "+e.getMessage()));
          }).addOnFailureListener(e->host.message("Gagal semak status email pekerja: "+e.getMessage()));
      }).setNegativeButton("BATAL",null).show();
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
  void observeWorker(){
    if(workerAccessListener!=null){workerAccessListener.remove();workerAccessListener=null;}
    if(!accounts.staff()||accounts.user()==null)return;
    final String uid=accounts.user().getUid();
    workerAccessListener=db.collection("staffProfiles").document(uid)
      .addSnapshotListener((profile,error)->{
        if(!accounts.staff()||accounts.user()==null||!uid.equals(accounts.user().getUid()))return;
        if(error!=null)return;
        if(profile!=null&&profile.exists()){
          String bound=profile.getString("deviceId");
          String device=host.getSharedPreferences("warisan_identity",0).getString("device_uid","");
          if(bound==null||!bound.equals(device)){
            if(workerAccessListener!=null){workerAccessListener.remove();workerAccessListener=null;}
            host.live.stop();accounts.auth.signOut();host.draw();
            host.message("Telefon pekerja dinyahikat. Log In semula selepas Owner sahkan.");
            return;
          }
        }
        if(profile!=null&&(!profile.exists()||
          !Boolean.TRUE.equals(profile.getBoolean("active"))||
          !"STAFF".equals(profile.getString("role"))||
          !accounts.ownerUid().equals(profile.getString("ownerUid"))||
          !accounts.branchId().equals(profile.getString("branchId")))){
          if(workerAccessListener!=null){workerAccessListener.remove();workerAccessListener=null;}
          host.live.stop();accounts.auth.signOut();host.draw();
          host.message("Akses pekerja telah dinyahaktifkan oleh Owner.");
        }
      });
  }
  /** One Owner screen covers all branches; "remove" means permanent access revocation
   *  with the audit/transaction history retained, NOT deletion of historical sales. */
  static final class WorkerRow {
    final QueryDocumentSnapshot member;
    final String branchName;
    final String branchId;
    WorkerRow(QueryDocumentSnapshot doc,String name,String id){
      member=doc;branchName=name;branchId=id;
    }
    boolean removed(){return Boolean.TRUE.equals(member.getBoolean("removed"));}
    boolean active(){return !removed()&&Boolean.TRUE.equals(member.getBoolean("active"));}
    String email(){
      String v=member.getString("email");
      return v==null?"(Email tidak diketahui)":v;
    }
  }
  void manageWorkers(){
    if(!accounts.owner()){host.message("Hanya Owner boleh lihat senarai pekerja.");return;}
    final String ownerUid=accounts.ownerUid();
    db.collection("workspaces").document(ownerUid).collection("branches")
      .get().addOnSuccessListener(branches->{
        ArrayList<com.google.android.gms.tasks.Task<com.google.firebase.firestore.QuerySnapshot>> tasks=new ArrayList<>();
        ArrayList<String> branchNames=new ArrayList<>(),branchIds=new ArrayList<>();
        for(QueryDocumentSnapshot b:branches){
          tasks.add(b.getReference().collection("members").get());
          branchNames.add(b.getString("name")==null?b.getId():b.getString("name"));
          branchIds.add(b.getId());
        }
        if(tasks.isEmpty()){host.message("Belum ada branch Owner.");return;}
        com.google.android.gms.tasks.Tasks.whenAllSuccess(tasks).addOnSuccessListener(results->{
          if(!accounts.owner()||!ownerUid.equals(accounts.ownerUid()))return;
          ArrayList<WorkerRow> activeRows=new ArrayList<>(),archivedRows=new ArrayList<>();
          for(int index=0;index<results.size();index++){
            com.google.firebase.firestore.QuerySnapshot snapshot=(com.google.firebase.firestore.QuerySnapshot)results.get(index);
            for(QueryDocumentSnapshot member:snapshot){
              if(!"STAFF".equals(member.getString("role")))continue;
              WorkerRow row=new WorkerRow(member,branchNames.get(index),branchIds.get(index));
              if(row.removed())archivedRows.add(row);else activeRows.add(row);
            }
          }
          ArrayList<WorkerRow> all=new ArrayList<>();
          all.addAll(activeRows);all.addAll(archivedRows);
          if(all.isEmpty()){
            host.darkBuilder().setTitle("Senarai Pekerja")
              .setMessage("Belum ada pekerja berdaftar untuk semua branch. Pergi Account → Daftar Pekerja.")
              .setPositiveButton("OK",null).show();
            return;
          }
          ArrayList<String> labels=new ArrayList<>();
          int enabled=0;
          for(WorkerRow row:all){
            if(row.active())enabled++;
            String state=row.removed()?"DIBUANG":(row.active()?"AKTIF":"DISEKAT");
            labels.add(row.email()+"\n"+row.branchName+"  ·  "+state);
          }
          // Android AlertDialog chooses the message panel INSTEAD of its list when
          // setMessage and setItems are both used: this hid actual employees.
          host.darkBuilder().setTitle("PEKERJA · "+all.size()+"  |  AKTIF "+enabled)
            .setItems(labels.toArray(new String[0]),(dialog,index)->workerDetails(all.get(index)))
            .setNegativeButton("Tutup",null).show();
        }).addOnFailureListener(e->host.message("Gagal baca pekerja semua branch: "+e.getMessage()));
      }).addOnFailureListener(e->host.message("Gagal baca branch: "+e.getMessage()));
  }
  void workerDetails(WorkerRow worker){
    if(!accounts.owner())return;
    String status=worker.removed()?"DIBUANG":(worker.active()?"AKTIF":"DISEKAT");
    ArrayList<String> options=new ArrayList<>();
    if(worker.removed()){
      options.add("Aktifkan semula akses pekerja");
    }else{
      options.add(worker.active()?"Sekat akses sementara":"Aktifkan semula pekerja");
      options.add("BUANG PEKERJA · Tarik balik semua akses");
    }
    options.add("Hantar pautan reset password ke email");
    options.add("Kembali ke senarai");
    // Do not setMessage() together with setItems(): list actions would disappear.
    host.darkBuilder().setTitle(worker.email()+"\n"+worker.branchName+" · "+status)
      .setItems(options.toArray(new String[0]),(dialog,choice)->{
        String action=options.get(choice);
        if(action.startsWith("BUANG")){
          confirmRemoveWorker(worker);
        }else if(action.startsWith("Sekat")){
          confirmBlockWorker(worker);
        }else if(action.startsWith("Aktifkan")){
          setEmployeeState(worker,true,false);
        }else if(action.startsWith("Hantar")){
          resetEmployeePassword(worker.email());
        }else manageWorkers();
      }).setNegativeButton("Tutup",null).show();
  }
  void confirmBlockWorker(WorkerRow worker){
    host.darkBuilder().setTitle("Sekat sementara?")
      .setMessage(worker.email()+"\nBranch: "+worker.branchName+
        "\n\nPekerja tak boleh mengakses branch atau jualan Live selepas perubahan disahkan server. Rekod lama kekal.")
      .setPositiveButton("SEKAT AKSES",(dialog,choice)->setEmployeeState(worker,false,false))
      .setNegativeButton("Batal",null).show();
  }
  void confirmRemoveWorker(WorkerRow worker){
    host.darkBuilder().setTitle("BUANG AKSES PEKERJA?")
      .setMessage(worker.email()+"\nBranch: "+worker.branchName+
        "\n\nAkses pekerja akan dinyahaktifkan dan disembunyikan daripada senarai aktif. Akaun Firebase Authentication tidak dipadam kerana memerlukan hak admin server.\n\nJualan, refund, stok dan rekod sejarah kekal.")
      .setPositiveButton("YA, BUANG AKSES",(dialog,choice)->setEmployeeState(worker,false,true))
      .setNegativeButton("BATAL",null).show();
  }
  void setEmployeeState(WorkerRow worker,boolean enabled,boolean removed){
    if(!accounts.owner()||accounts.user()==null)return;
    final String ownerId=accounts.ownerUid();
    if(worker.member.getReference().getParent().getParent()==null||
      !worker.branchId.equals(worker.member.getReference().getParent().getParent().getId())){
      host.message("Branch pekerja tidak sepadan.");return;
    }
    DocumentReference profile=db.collection("staffProfiles").document(worker.member.getId());
    com.google.firebase.firestore.WriteBatch batch=db.batch();
    Map<String,Object> changes=new HashMap<>();
    changes.put("active",enabled);changes.put("removed",removed);
    changes.put("updatedAt",FieldValue.serverTimestamp());
    if(removed)changes.put("removedAt",FieldValue.serverTimestamp());
    else changes.put("removedAt",FieldValue.delete());
    batch.update(worker.member.getReference(),changes);
    batch.update(profile,changes);
    String staffMail=worker.email().trim().toLowerCase(Locale.ROOT);
    if(android.util.Patterns.EMAIL_ADDRESS.matcher(staffMail).matches()){
      Map<String,Object> emailRole=new HashMap<>();
      emailRole.put("email",staffMail);emailRole.put("workerUid",worker.member.getId());
      emailRole.put("ownerUid",ownerId);emailRole.put("branchId",worker.branchId);
      emailRole.put("role","STAFF");emailRole.put("removed",removed);
      emailRole.put("updatedAt",FieldValue.serverTimestamp());
      // Merge supports workers enrolled on older APKs without a role index.
      batch.set(db.collection("staffEmails").document(staffMail),emailRole,
        com.google.firebase.firestore.SetOptions.merge());
    }
    batch.commit().addOnSuccessListener(v->{
      if(!accounts.owner()||!ownerId.equals(accounts.ownerUid()))return;
      host.message(removed?"Pekerja dibuang daripada akses semua fungsi branch.":
        enabled?"Akses pekerja diaktifkan semula.":"Pekerja disekat sementara.");
      manageWorkers();
    }).addOnFailureListener(e->host.message("Ubah akses gagal: "+e.getMessage()+
      ". Pastikan Firestore Rules terkini sudah diterbitkan."));
  }
  void resetEmployeePassword(String email){
    if(!accounts.owner()||email==null||email.isEmpty())return;
    accounts.auth.sendPasswordResetEmail(email)
      .addOnSuccessListener(v->host.message("Pautan reset password dihantar ke "+email))
      .addOnFailureListener(e->host.message("Reset password gagal: "+e.getMessage()));
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
