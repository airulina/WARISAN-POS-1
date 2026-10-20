package my.warisan.pos;

import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Explicit employee shift check-in/out, independent of POS Opening/Closing.
 * Authentication/login by itself is NOT evidence that a worker is on shift.
 * Keeps sales, stock, orders and existing liveSessions completely untouched.
 */
final class StaffShifts {
  final MainActivity host;
  final BranchAccounts accounts;
  final FirebaseFirestore db;
  private ListenerRegistration myListener, ownerBranchesListener;
  private final ArrayList<ListenerRegistration> ownerDetailListeners=new ArrayList<>();
  private final Map<String,String> branchNames=new HashMap<>();
  private final Map<String,Map<String,String>> workerNames=new HashMap<>();
  private final Map<String,List<DocumentSnapshot>> attendance=new HashMap<>();
  private String myKey="", ownerKey="";
  private Boolean myActive=null;
  private boolean myFinished=false;

  StaffShifts(MainActivity host){
    this.host=host;accounts=host.branchAccounts;db=host.firestoreForStaff();
  }
  DocumentReference myShift(String day,String uid){
    return db.collection("workspaces").document(accounts.ownerUid())
      .collection("branches").document(accounts.branchId())
      .collection("shiftAttendance").document(day+"_"+uid);
  }
  void stop(){
    stopMy();stopOwner();
  }
  private void stopMy(){
    if(myListener!=null){myListener.remove();myListener=null;}
    myKey="";myActive=null;myFinished=false;
  }
  private void stopOwner(){
    if(ownerBranchesListener!=null){ownerBranchesListener.remove();ownerBranchesListener=null;}
    for(ListenerRegistration listener:ownerDetailListeners)listener.remove();
    ownerDetailListeners.clear();ownerKey="";
    branchNames.clear();workerNames.clear();attendance.clear();
  }
  private void redraw(){
    if(accounts.owner()&&(host.activePage==3||host.activePage==15||
      host.activePage==16||host.activePage==17))host.draw();
  }
  void observeMyShift(){
    if(!accounts.staff()||accounts.user()==null){stopMy();return;}
    String uid=accounts.user().getUid(),day=host.businessDay();
    String key=accounts.ownerUid()+":"+accounts.branchId()+":"+uid+":"+day;
    if(key.equals(myKey)&&myListener!=null)return;
    stopMy();myKey=key;
    myListener=myShift(day,uid).addSnapshotListener((doc,error)->{
      if(!key.equals(myKey)||!accounts.staff())return;
      if(error!=null||doc==null){myActive=null;myFinished=false;}
      else {
        myActive=doc.exists()&&Boolean.TRUE.equals(doc.getBoolean("active"));
        myFinished=doc.exists()&&!Boolean.TRUE.equals(doc.getBoolean("active"));
      }
      if(host.activePage==12)host.draw();
    });
  }
  void drawMyShift(){
    if(!accounts.staff())return;
    observeMyShift();
    host.add(host.body,host.text("SHIFT PEKERJA",13,host.gold,true),-1,-2);
    host.gap(host.body,7);
    if(myActive==null){
      host.add(host.body,host.text("Memeriksa kehadiran shift daripada Cloud...",
        12,host.muted,false),-1,-2);
    }else if(myActive){
      host.add(host.body,host.text("🟢 Sedang bertugas · Branch "+accounts.branchName(),
        12,host.ink,true),-1,-2);
      host.action("TAMAT SHIFT",()->saveMyShift(false));
    }else if(myFinished){
      host.add(host.body,host.text("Shift hari ini telah tamat. Rekod kehadiran disimpan.",
        12,host.muted,false),-1,-2);
    }else{
      host.add(host.body,host.text("Belum mula shift. Login sahaja tidak dikira hadir.",
        12,host.muted,false),-1,-2);
      host.action("MULA SHIFT",()->saveMyShift(true));
    }
    host.gap(host.body,10);
  }
  private void saveMyShift(boolean start){
    if(!accounts.staff()||!accounts.staffBranchVerified||accounts.user()==null){
      host.message("Sahkan login dan akses branch dahulu.");return;
    }
    final String uid=accounts.user().getUid(),owner=accounts.ownerUid();
    final String branch=accounts.branchId(),day=host.businessDay();
    final String mail=accounts.user().getEmail()==null?"":accounts.user().getEmail();
    final DocumentReference ref=myShift(day,uid);
    db.runTransaction(tx->{
      DocumentSnapshot current=tx.get(ref);
      if(start){
        if(current.exists())
          throw new IllegalStateException("Shift sudah bermula atau tamat hari ini.");
        Map<String,Object> record=new HashMap<>();
        record.put("ownerUid",owner);record.put("branchId",branch);
        record.put("day",day);record.put("employeeUid",uid);record.put("email",mail);
        record.put("active",true);record.put("startedAt",FieldValue.serverTimestamp());
        tx.set(ref,record);
      }else{
        if(!current.exists()||!Boolean.TRUE.equals(current.getBoolean("active")))
          throw new IllegalStateException("Tiada shift aktif untuk ditamatkan.");
        tx.update(ref,"active",false,"endedAt",FieldValue.serverTimestamp());
      }
      return null;
    }).addOnSuccessListener(v->{
      if(accounts.staff()&&uid.equals(accounts.user()==null?"":accounts.user().getUid()))
        host.message(start?"Shift bermula dan Owner boleh lihat nama pekerja.":
          "Shift tamat. Nama dikeluarkan daripada senarai pekerja bertugas.");
    }).addOnFailureListener(e->host.message("Gagal simpan shift di Cloud: "+e.getMessage()));
  }
  void ensureOwner(){
    if(!accounts.owner()){stopOwner();return;}
    final String owner=accounts.ownerUid(),today=host.businessDay();
    final String key=owner+":"+today;
    if(key.equals(ownerKey)&&ownerBranchesListener!=null)return;
    stopOwner();ownerKey=key;
    ownerBranchesListener=db.collection("workspaces").document(owner)
      .collection("branches").addSnapshotListener((branches,error)->{
        if(!key.equals(ownerKey)||!accounts.owner()||error!=null||branches==null)return;
        for(ListenerRegistration listener:ownerDetailListeners)listener.remove();
        ownerDetailListeners.clear();
        branchNames.clear();workerNames.clear();attendance.clear();
        for(QueryDocumentSnapshot branch:branches){
          final String branchId=branch.getId();
          if(Boolean.TRUE.equals(branch.getBoolean("archived")))continue;
          final String name=branch.getString("name")==null?branchId:branch.getString("name");
          branchNames.put(branchId,name);
          workerNames.put(branchId,new HashMap<>());
          attendance.put(branchId,new ArrayList<>());
          ownerDetailListeners.add(branch.getReference().collection("members")
            .addSnapshotListener((members,e)->{
              if(!key.equals(ownerKey)||e!=null||members==null)return;
              Map<String,String> found=new HashMap<>();
              for(QueryDocumentSnapshot worker:members){
                if(!"STAFF".equals(worker.getString("role")))continue;
                String label=worker.getString("fullName");
                if(label==null||label.trim().isEmpty())label=worker.getString("email");
                if(label!=null)found.put(worker.getId(),label);
              }
              workerNames.put(branchId,found);redraw();
            }));
          ownerDetailListeners.add(branch.getReference().collection("shiftAttendance")
            .whereEqualTo("day",today).addSnapshotListener((shifts,e)->{
              if(!key.equals(ownerKey)||e!=null||shifts==null)return;
              attendance.put(branchId,new ArrayList<DocumentSnapshot>(shifts.getDocuments()));
              redraw();
            }));
        }
        redraw();
      });
  }
  String branchStatus(String branchId){
    if(!attendance.containsKey(branchId))return "Memeriksa pekerja bertugas...";
    List<String> active=new ArrayList<>();
    Map<String,String> names=workerNames.get(branchId);
    for(DocumentSnapshot doc:attendance.get(branchId)){
      if(!Boolean.TRUE.equals(doc.getBoolean("active")))continue;
      String uid=doc.getString("employeeUid");
      String name=names==null||uid==null?null:names.get(uid);
      if(name==null||name.trim().isEmpty())name=doc.getString("email");
      active.add(name==null?"Pekerja":name);
    }
    if(active.isEmpty())return "Tiada pekerja on shift";
    Collections.sort(active);
    return "On shift ("+active.size()+"): "+android.text.TextUtils.join(", ",active);
  }
  void drawOwnerBranches(){
    if(!accounts.owner())return;
    ensureOwner();
    host.add(host.body,host.text("PEKERJA ON SHIFT · LIVE",13,host.gold,true),-1,-2);
    host.gap(host.body,8);
    if(branchNames.isEmpty()){
      host.add(host.body,host.text("Sedang semak kehadiran pekerja...",
        12,host.muted,false),-1,-2);
    }else{
      List<String> ids=new ArrayList<>(branchNames.keySet());
      Collections.sort(ids,(a,b)->branchNames.get(a).compareToIgnoreCase(branchNames.get(b)));
      for(String id:ids){
        host.add(host.body,host.text("🏪 "+branchNames.get(id)+" · "+branchStatus(id),
          12,host.ink,true),-1,-2);
        host.gap(host.body,7);
      }
    }
    host.add(host.body,host.text("Status berdasarkan pekerja tekan MULA SHIFT / TAMAT SHIFT; "+
      "login atau Opening POS sahaja tidak dikira masuk shift.",
      11,host.muted,false),-1,-2);
    host.gap(host.body,10);
  }
}
