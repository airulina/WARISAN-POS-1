package my.warisan.pos;

import android.Manifest;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.Source;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Foreground-only GPS check for POS opening. No background tracking.
 * Branch is fixed by the authenticated staff profile, not the device's GPS.
 * Note: consumer phone location can be spoofed, so location alone is not proof.
 */
final class BranchPresence {
  static final int REQUEST_LOCATION=7720;
  static final int DEFAULT_RADIUS=250;
  interface LocationResult { void done(Location location); }
  final MainActivity host;
  private Runnable permissionRetry;
  private LocationListener listener;
  private final Handler handler=new Handler(Looper.getMainLooper());
  BranchPresence(MainActivity host){this.host=host;}
  boolean permitted(){
    return Build.VERSION.SDK_INT<23 ||
      host.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;
  }
  void requestGps(Runnable retry){
    if(permitted()){retry.run();return;}
    permissionRetry=retry;
    host.requestPermissions(new String[]{
      Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION
    },REQUEST_LOCATION);
  }
  void permissionAnswer(int[] results){
    Runnable again=permissionRetry;permissionRetry=null;
    if(permitted()&&again!=null)again.run();
    else host.message("Lokasi tepat diperlukan untuk semakan Opening. Benarkan Precise Location dalam tetapan Android.");
  }
  void location(LocationResult complete){
    if(!permitted()){complete.done(null);return;}
    LocationManager manager=(LocationManager)host.getSystemService(android.content.Context.LOCATION_SERVICE);
    if(manager==null){complete.done(null);return;}
    final boolean[] finished={false};
    LocationListener callback=new LocationListener(){
      public void onLocationChanged(Location l){
        if(finished[0])return;
        if(l==null||l.getAccuracy()>100f)return;
        finished[0]=true;
        try{manager.removeUpdates(this);}catch(Exception ignored){}
        complete.done(l);
      }
      public void onProviderDisabled(String provider){}
      public void onProviderEnabled(String provider){}
      public void onStatusChanged(String provider,int status,android.os.Bundle extras){}
    };
    listener=callback;
    handler.postDelayed(()->{
      if(finished[0])return;
      finished[0]=true;
      try{manager.removeUpdates(callback);}catch(Exception ignored){}
      complete.done(null);
    },18000);
    try{
      boolean started=false;
      if(manager.isProviderEnabled(LocationManager.GPS_PROVIDER)){
        manager.requestLocationUpdates(LocationManager.GPS_PROVIDER,0,0,callback,Looper.getMainLooper());
        started=true;
      }
      if(manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)){
        manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,0,0,callback,Looper.getMainLooper());
        started=true;
      }
      if(!started){
        finished[0]=true;complete.done(null);
      }
    }catch(SecurityException e){finished[0]=true;complete.done(null);}
      catch(Exception e){finished[0]=true;complete.done(null);}
  }
  boolean mocked(Location l){
    return Build.VERSION.SDK_INT>=31?l.isMock():l.isFromMockProvider();
  }
  void ownerSetBranchLocation(){
    if(!host.branchAccounts.owner()){host.message("Hanya Owner boleh tetapkan lokasi branch.");return;}
    String branchName=host.branchAccounts.branchName();
    host.darkBuilder().setTitle("LOKASI BRANCH · "+branchName)
      .setMessage("Owner perlu berada di kios yang betul. GPS lokasi tepat disimpan sebagai pusat kawasan. Radius 250 meter. Lokasi pekerja hanya diperiksa ketika Opening, bukan dipantau sepanjang hari.")
      .setPositiveButton("TETAPKAN GPS",(d,w)->requestGps(()->location(loc->{
        if(loc==null){host.message("Lokasi belum dapat dikesan. Hidupkan GPS dan cuba di kawasan terbuka.");return;}
        if(mocked(loc)){host.message("Lokasi simulasi ditolak.");return;}
        String owner=host.branchAccounts.ownerUid(),branch=host.branchAccounts.branchId();
        DocumentReference ref=host.firestoreForStaff().collection("workspaces").document(owner)
          .collection("branches").document(branch);
        ref.update("latitude",loc.getLatitude(),"longitude",loc.getLongitude(),
          "radiusMeters",DEFAULT_RADIUS,"geoUpdatedAt",FieldValue.serverTimestamp())
          .addOnSuccessListener(v->host.darkBuilder().setTitle("LOKASI BRANCH DISIMPAN")
            .setMessage(branchName+"\nRadius: "+DEFAULT_RADIUS+
              " meter. Pekerja mesti berada di kawasan ini semasa Opening.")
            .setPositiveButton("OK",null).show())
          .addOnFailureListener(e->host.message("Lokasi gagal disimpan: "+e.getMessage()));
      }))).setNegativeButton("BATAL",null).show();
  }
  void ownerSetBranchOpenState(){
    if(!host.branchAccounts.owner()){host.message("Hanya Owner boleh buka/tutup branch.");return;}
    final String owner=host.branchAccounts.ownerUid(),branch=host.branchAccounts.branchId();
    DocumentReference ref=host.firestoreForStaff().collection("workspaces")
      .document(owner).collection("branches").document(branch);
    ref.get(Source.SERVER).addOnSuccessListener(doc->{
      boolean enabled=!Boolean.FALSE.equals(doc.getBoolean("openingEnabled"));
      String title=enabled?"TUTUP BRANCH UNTUK OPENING?":"BENARKAN OPENING BRANCH?";
      host.darkBuilder().setTitle(title)
        .setMessage(host.branchAccounts.branchName()+"\n\n"+
          (enabled?"Semua Opening baharu pekerja di branch ini akan disekat. "+
            "Rekod jualan lama dan stok tidak dipadam.":
            "Pekerja boleh buat Opening selepas pengesahan lokasi dan peranti."))
        .setPositiveButton(enabled?"TUTUP BRANCH":"AKTIFKAN",(d,w)->
          ref.update("openingEnabled",!enabled,
            "branchStatusUpdatedAt",FieldValue.serverTimestamp())
          .addOnSuccessListener(ok->host.message("Status branch dikemas kini: "+
            (!enabled?"BENAR OPENING":"OPENING DITUTUP")))
          .addOnFailureListener(e->host.message("Gagal ubah status branch: "+e.getMessage())))
        .setNegativeButton("BATAL",null).show();
    }).addOnFailureListener(e->host.message("Status branch gagal diperiksa: "+e.getMessage()));
  }
  /** A one-time location check at sign-in; Owner sees an exception if
   *  a worker is outside their ASSIGNED branch or refuses GPS permission.
   *  Sign-in itself remains possible (Opening remains strictly GPS-gated). */
  void checkStaffLoginLocation(){
    if(!host.branchAccounts.staff()||host.branchAccounts.user()==null)return;
    final String worker=host.branchAccounts.user().getUid();
    final String owner=host.branchAccounts.ownerUid(),branch=host.branchAccounts.branchId();
    DocumentReference ref=host.firestoreForStaff().collection("workspaces")
      .document(owner).collection("branches").document(branch);
    ref.get(Source.SERVER).addOnSuccessListener(doc->{
      if(!host.branchAccounts.staff()||host.branchAccounts.user()==null||
        !worker.equals(host.branchAccounts.user().getUid())||
        !branch.equals(host.branchAccounts.branchId()))return;
      Double lat=doc.getDouble("latitude"),lon=doc.getDouble("longitude");
      Long radius=doc.getLong("radiusMeters");
      if(lat==null||lon==null||radius==null){
        recordLoginCheck("NOT_CONFIGURED",-1);return;
      }
      requestGps(()->location(loc->{
        if(loc==null){recordLoginCheck("GPS_UNAVAILABLE",-1);return;}
        if(mocked(loc)){recordLoginCheck("MOCK_LOCATION",-1);return;}
        float[] meters=new float[1];
        Location.distanceBetween(lat,lon,loc.getLatitude(),loc.getLongitude(),meters);
        int distance=Math.max(0,Math.round(meters[0]));
        String status=distance>Math.max(50,Math.min(1000,radius))?"OUTSIDE_BRANCH":"WITHIN_BRANCH";
        recordLoginCheck(status,distance);
        if(!"WITHIN_BRANCH".equals(status))
          host.message("Log In berjaya, tetapi lokasi telefon di luar branch "+
            host.branchAccounts.branchName()+". Owner boleh semak percubaan ini.");
      }));
    }).addOnFailureListener(e->host.message(
      "Lokasi Log In tidak dapat disahkan. Opening masih memerlukan GPS."));
  }
  void recordLoginCheck(String status,int distance){
    if(!host.branchAccounts.staff()||host.branchAccounts.user()==null)return;
    Map<String,Object> event=new HashMap<>();
    event.put("ownerUid",host.branchAccounts.ownerUid());
    event.put("branchId",host.branchAccounts.branchId());
    event.put("employeeUid",host.branchAccounts.user().getUid());
    event.put("email",host.branchAccounts.user().getEmail());
    event.put("status",status);event.put("distanceMeters",distance);
    event.put("day",host.businessDay());
    event.put("createdAt",FieldValue.serverTimestamp());
    host.firestoreForStaff().collection("workspaces").document(host.branchAccounts.ownerUid())
      .collection("branches").document(host.branchAccounts.branchId())
      .collection("loginChecks").add(event);
  }
  void verifyOpening(int amount,String day,Runnable allowed){
    if(host.branchAccounts.owner()){allowed.run();return;}
    if(!host.branchAccounts.staff()){host.message("Login diperlukan.");return;}
    final String owner=host.branchAccounts.ownerUid(),branch=host.branchAccounts.branchId();
    final String worker=host.branchAccounts.user().getUid();
    DocumentReference branchRef=host.firestoreForStaff().collection("workspaces").document(owner)
      .collection("branches").document(branch);
    branchRef.get(Source.SERVER).addOnSuccessListener(doc->{
      if(!host.branchAccounts.staff()||!worker.equals(host.branchAccounts.user().getUid())
        ||!branch.equals(host.branchAccounts.branchId()))return;
      if(Boolean.FALSE.equals(doc.getBoolean("openingEnabled"))){
        recordOpeningCheck("BRANCH_CLOSED",-1,day);
        host.darkBuilder().setTitle("BRANCH DITUTUP OLEH OWNER")
          .setMessage("Branch "+host.branchAccounts.branchName()+
            " tidak menerima Opening sekarang. Hubungi Owner.")
          .setPositiveButton("FAHAM",null).show();return;
      }
      Double lat=doc.getDouble("latitude"),lon=doc.getDouble("longitude");
      Long configuredRadius=doc.getLong("radiusMeters");
      if(lat==null||lon==null||configuredRadius==null){
        recordOpeningCheck("NOT_CONFIGURED",-1,day);
        host.darkBuilder().setTitle("LOKASI BRANCH BELUM DITETAPKAN")
          .setMessage(host.branchAccounts.branchName()+
            "\nMinta Owner pergi ke branch sebenar dan pilih Set Lokasi GPS. Opening tidak boleh dibuat sehingga lokasi disahkan.")
          .setPositiveButton("FAHAM",null).show();return;
      }
      requestGps(()->location(loc->{
        if(loc==null){recordOpeningCheck("GPS_UNAVAILABLE",-1,day);
          host.message("GPS tidak dapat mengesahkan lokasi. Opening dibatalkan, cuba di kawasan terbuka.");return;}
        if(mocked(loc)){recordOpeningCheck("MOCK_LOCATION",-1,day);
          host.message("Lokasi simulasi dikesan; Opening disekat.");return;}
        float[] meters=new float[1];
        Location.distanceBetween(lat,lon,loc.getLatitude(),loc.getLongitude(),meters);
        int distance=Math.max(0,Math.round(meters[0]));
        int radius=(int)Math.min(1000,Math.max(50,configuredRadius));
        if(distance>radius){
          recordOpeningCheck("OUTSIDE_BRANCH",distance,day);
          host.darkBuilder().setTitle("OPENING DISEKAT · LUAR KAWASAN")
            .setMessage("Email pekerja ini didaftarkan untuk "+host.branchAccounts.branchName()+
              ", bukan lokasi semasa. Anggaran jarak: "+distance+
              " meter dari kios. Owner boleh semak cubaan dalam Cashflow.")
            .setPositiveButton("FAHAM",null).show();return;
        }
        recordOpeningCheck("VERIFIED",distance,day);
        allowed.run();
      }));
    }).addOnFailureListener(e->host.message("Tidak dapat semak GPS branch di server. Opening tidak dibuat: "+e.getMessage()));
  }
  void recordOpeningCheck(String status,int distance,String day){
    if(!host.branchAccounts.staff()||host.branchAccounts.user()==null)return;
    Map<String,Object> event=new HashMap<>();
    event.put("ownerUid",host.branchAccounts.ownerUid());
    event.put("branchId",host.branchAccounts.branchId());
    event.put("employeeUid",host.branchAccounts.user().getUid());
    event.put("day",day);event.put("status",status);
    event.put("distanceMeters",distance);
    event.put("createdAt",FieldValue.serverTimestamp());
    host.firestoreForStaff().collection("workspaces").document(host.branchAccounts.ownerUid())
      .collection("branches").document(host.branchAccounts.branchId())
      .collection("openingChecks").add(event)
      .addOnFailureListener(e->host.message("Rekod semakan lokasi gagal dihantar: "+e.getMessage()));
  }
}
