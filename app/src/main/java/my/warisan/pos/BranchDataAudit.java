package my.warisan.pos;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import com.google.firebase.firestore.Source;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * READ-ONLY provenance audit for copies of old Main Branch phone data.
 *
 * Earlier signup builds copied all original Owner preferences to the first
 * registered branch. Do NOT erase that branch: it may have genuine sales,
 * refunds, employee history, Cloud orders and stock adjustments after signup.
 * Only an exact, structurally identical local record counts as a duplicate.
 */
final class BranchDataAudit {
  static final String[] LEDGERS={
    "stock_sales","stock_entries","stock_damage","stock_distribution",
    "cash_entries","pending_orders"
  };
  static final class Result {
    final Map<String,Integer> found=new HashMap<>();
    final Map<String,Integer> total=new HashMap<>();
    final Map<String,Set<String>> copies=new HashMap<>();
    int copiedSales=0,copiedOther=0,matchingDailyTotals=0;
    boolean proven=false;
    String summary(String branch){
      StringBuilder sb=new StringBuilder();
      sb.append(branch).append("\n");
      for(String key:LEDGERS){
        int totalRecords=total.containsKey(key)?total.get(key):0;
        int exact=found.containsKey(key)?found.get(key):0;
        if(totalRecords>0)sb.append("  ").append(key)
          .append(": ").append(exact).append("/").append(totalRecords)
          .append(" sepadan tepat dengan rekod asal\n");
      }
      sb.append("  Salinan resit jualan: ").append(copiedSales).append("\n");
      sb.append("  Salinan rekod lain: ").append(copiedOther).append("\n");
      sb.append("  Jumlah harian lama yang sama: ")
        .append(matchingDailyTotals).append("\n");
      sb.append(proven?
        "  STATUS: SALINAN REKOD TEMPATAN DIKESAN\n":
        "  STATUS: BELUM CUKUP BUKTI SALINAN\n");
      return sb.toString();
    }
  }
  private BranchDataAudit(){}

  static SharedPreferences original(Context context){
    return ((MainActivity)context).getPreferences(Context.MODE_PRIVATE);
  }
  static SharedPreferences branch(Context context,String branchId){
    return context.getSharedPreferences("warisan_branch_"+branchId,Context.MODE_PRIVATE);
  }
  /** Sort every JSON object key recursively, including nested item quantity lists. */
  static String signature(Object value){
    if(value==null||value==JSONObject.NULL)return "null";
    if(value instanceof JSONObject){
      JSONObject json=(JSONObject)value;
      ArrayList<String> keys=new ArrayList<>();
      Iterator<String> it=json.keys();
      while(it.hasNext())keys.add(it.next());
      Collections.sort(keys);
      StringBuilder out=new StringBuilder("{");
      for(String key:keys){
        if(out.length()>1)out.append(',');
        out.append(JSONObject.quote(key)).append(':')
          .append(signature(json.opt(key)));
      }
      return out.append('}').toString();
    }
    if(value instanceof JSONArray){
      JSONArray array=(JSONArray)value;
      StringBuilder out=new StringBuilder("[");
      for(int i=0;i<array.length();i++){
        if(i>0)out.append(',');
        out.append(signature(array.opt(i)));
      }
      return out.append(']').toString();
    }
    if(value instanceof String)return JSONObject.quote((String)value);
    return String.valueOf(value);
  }
  static JSONArray ledger(SharedPreferences prefs,String key){
    try{return new JSONArray(prefs.getString(key,"[]"));}
    catch(Exception e){return new JSONArray();}
  }
  static Map<String,Integer> frequencies(JSONArray arr){
    Map<String,Integer> freq=new HashMap<>();
    for(int i=0;i<arr.length();i++){
      Object value=arr.opt(i);
      if(!(value instanceof JSONObject))continue;
      String stamp=signature(value);
      freq.put(stamp,freq.containsKey(stamp)?freq.get(stamp)+1:1);
    }
    return freq;
  }
  /**
   * Test entire arrays, not just item totals. Independent RM1 orders should
   * never be called clones unless the time, order id, quantities and every
   * other saved field also match exactly.
   */
  static Result compare(SharedPreferences source,SharedPreferences candidate){
    Result result=new Result();
    for(String key:LEDGERS){
      JSONArray orig=ledger(source,key),copy=ledger(candidate,key);
      result.total.put(key,copy.length());
      Map<String,Integer> originals=frequencies(orig);
      Set<String> matched=new HashSet<>();
      int count=0;
      for(int i=0;i<copy.length();i++){
        Object record=copy.opt(i);
        if(!(record instanceof JSONObject))continue;
        String sig=signature(record);
        int n=originals.containsKey(sig)?originals.get(sig):0;
        if(n<=0)continue;
        originals.put(sig,n-1);matched.add(sig);count++;
      }
      result.found.put(key,count);
      result.copies.put(key,matched);
      if(key.equals("stock_sales"))result.copiedSales=count;
      else result.copiedOther+=count;
    }
    for(String key:candidate.getAll().keySet()){
      if(!(key.matches("sales_\\d{4}-\\d{2}-\\d{2}")||
           key.matches("orders_\\d{4}-\\d{2}-\\d{2}")))continue;
      if(source.contains(key)&&
        String.valueOf(source.getAll().get(key))
          .equals(String.valueOf(candidate.getAll().get(key))))
        result.matchingDailyTotals++;
    }
    // Require strong provenance: two exact immutable audit records, of
    // which at least one is a sale, OR 3 separate copied stock/expense events.
    result.proven=(result.copiedSales>0&&
      result.copiedSales+result.copiedOther>=2)||
      result.copiedSales>=2||result.copiedOther>=3;
    return result;
  }
  /**
   * A report may suppress copied OLD receipts only if the genuine original
   * Main Branch is present in the same combined report. Otherwise do NOT
   * silently drop sales which have no other visible copy.
   */
  static Set<String> exactCopiedSales(SharedPreferences main,
      SharedPreferences branch){
    Result result=compare(main,branch);
    return result.proven?result.copies.get("stock_sales"):
      Collections.<String>emptySet();
  }

  /**
   * Owner-only, READ-ONLY provenance audit. Local clone checks use complete
   * JSON record signatures. Cloud is summarized separately: matching branch
   * totals are NOT proof that Cloud records should be moved or deleted.
   */
  static void show(MainActivity host){
    if(!host.branchAccounts.owner()){
      host.message("Audit cawangan hanya untuk pemilik.");return;
    }
    final String owner=host.branchAccounts.ownerUid();
    final SharedPreferences original=original(host);
    final FirebaseFirestore db=host.firestoreForStaff();
    db.collection("workspaces").document(owner).collection("branches")
      .get(Source.SERVER).addOnSuccessListener(snapshot->{
        if(!host.branchAccounts.owner()||
          !owner.equals(host.branchAccounts.ownerUid()))return;
        final ArrayList<QueryDocumentSnapshot> branches=new ArrayList<>();
        final ArrayList<com.google.android.gms.tasks.Task<
          com.google.firebase.firestore.QuerySnapshot>> dailyTasks=new ArrayList<>();
        final ArrayList<com.google.android.gms.tasks.Task<
          com.google.firebase.firestore.QuerySnapshot>> orderTasks=new ArrayList<>();
        final ArrayList<com.google.android.gms.tasks.Task<?>> allTasks=new ArrayList<>();
        boolean legacyExists=false;
        for(QueryDocumentSnapshot b:snapshot){
          branches.add(b);
          if(BranchAccounts.LEGACY_BRANCH.equals(b.getId()))legacyExists=true;
          // Query limited snapshots to avoid expensive historical downloads.
          // The record totals are labelled SAMPLES, never all-time figures.
          com.google.firebase.firestore.DocumentReference ref=b.getReference();
          com.google.android.gms.tasks.Task<com.google.firebase.firestore.QuerySnapshot>
            daily=ref.collection("daily").limit(35).get(Source.SERVER);
          com.google.android.gms.tasks.Task<com.google.firebase.firestore.QuerySnapshot>
            orders=ref.collection("liveOrders").limit(35).get(Source.SERVER);
          dailyTasks.add(daily);orderTasks.add(orders);
          allTasks.add(daily);allTasks.add(orders);
        }
        final boolean mainPresent=legacyExists;
        host.message("Sedang menyemak salinan data tempatan dan sampel rekod Cloud. Tiada data diubah.");
        com.google.android.gms.tasks.Tasks.whenAllComplete(allTasks)
          .addOnSuccessListener(ignored->{
            if(!host.branchAccounts.owner()||
              !owner.equals(host.branchAccounts.ownerUid()))return;
            StringBuilder report=new StringBuilder();
            report.append("AUDIT DATA CAWANGAN · BACA SAHAJA\n\n");
            report.append("Sumber asal: rekod Warisan POS lama pada telefon Owner.\n");
            report.append("Resit tempatan dibandingkan hingga ke setiap medan JSON.\n");
            report.append("Rekod Cloud disemak secara berasingan (maksimum 35 bagi setiap jenis).\n\n");
            report.append("REKOD ASAL BRANCH UTAMA\n");
            for(String ledger:LEDGERS)
              report.append(ledger).append(": ")
                .append(ledger(original,ledger).length()).append("\n");
            report.append("\n");
            if(!mainPresent){
              report.append("AMARAN: ID asal Branch Utama (utama_legacy) tidak ditemui "+
                "dalam Cloud. Rekod asal telefon tidak boleh dipautkan secara automatik "+
                "kepada mana-mana cawangan tanpa pemeriksaan lanjut.\n\n");
            }
            int flagged=0;
            for(int i=0;i<branches.size();i++){
              QueryDocumentSnapshot branchDoc=branches.get(i);
              String name=branchDoc.getString("name");
              if(name==null||name.trim().isEmpty())name=branchDoc.getId();
              report.append("CAWANGAN: ").append(name).append("\n");
              if(BranchAccounts.LEGACY_BRANCH.equals(branchDoc.getId())){
                report.append("  ID: ASAL (utama_legacy)\n");
              }else{
                Result audit=compare(original,branch(host,branchDoc.getId()));
                report.append(audit.summary(""));
                if(audit.proven)flagged++;
                // Report UNIQUE records too; their existence means wholesale
                // deletion/migration would destroy valid branch transactions.
                for(String key:LEDGERS){
                  int total=audit.total.containsKey(key)?audit.total.get(key):0;
                  int copied=audit.found.containsKey(key)?audit.found.get(key):0;
                  if(total>copied)report.append("  ").append(key)
                    .append(": ").append(total-copied)
                    .append(" rekod bukan salinan tepat\n");
                }
              }
              report.append("  CLOUD / FIRESTORE:\n");
              com.google.android.gms.tasks.Task<com.google.firebase.firestore.QuerySnapshot>
                orderTask=orderTasks.get(i),dailyTask=dailyTasks.get(i);
              if(orderTask.isSuccessful()){
                int paid=0,pending=0,cancelled=0;
                for(com.google.firebase.firestore.DocumentSnapshot order:
                    orderTask.getResult().getDocuments()){
                  String status=order.getString("status");
                  if("PAID".equals(status))paid++;
                  else if("PENDING".equals(status))pending++;
                  else if("CANCELLED".equals(status))cancelled++;
                }
                report.append("  Sampel order Cloud: ")
                  .append(orderTask.getResult().size())
                  .append(" (bayar ").append(paid)
                  .append(", belum bayar ").append(pending)
                  .append(", batal ").append(cancelled).append(")\n");
              }else report.append("  Order Cloud: TIDAK DAPAT DISAHKAN\n");
              if(dailyTask.isSuccessful())
                report.append("  Sampel rekod jualan harian Cloud: ")
                  .append(dailyTask.getResult().size()).append("\n");
              else report.append("  Jualan harian Cloud: TIDAK DAPAT DISAHKAN\n");
              report.append("\n");
            }
            if(flagged>0)
              report.append("AMARAN: Salinan rekod asal dikesan dalam cawangan "+
                "tambahan. Jualan, kos dan stok yang tersalin perlu "+
                "diasingkan daripada rekod tulen cawangan, satu rekod pada satu masa.\n\n");
            report.append("TIADA DATA DIPADAM, DIPINDAH ATAU DIUBAH.\n");
            report.append("Jangan padam cawangan, Clear Data atau pulih backup. "+
              "Sampel Cloud tidak mengesahkan asal setiap transaksi; "+
              "pemindahan Cloud memerlukan semakan ID resit/rekod yang lengkap.");
            host.darkBuilder().setTitle("SEMAK ASAL DATA CAWANGAN")
              .setMessage(report.toString())
              .setPositiveButton("FAHAM",null).show();
          }).addOnFailureListener(e->host.darkBuilder()
            .setTitle("AUDIT TIDAK LENGKAP")
            .setMessage("Cloud tidak dapat disemak. Tiada data diubah.\n"+e.getMessage())
            .setPositiveButton("OK",null).show());
      }).addOnFailureListener(e->host.message(
        "Audit gagal membaca senarai cawangan: "+e.getMessage()));
  }
}
