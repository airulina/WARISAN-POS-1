package my.warisan.pos;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Base64;
import android.widget.ImageView;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.FieldValue;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Owner-managed static QR shared across branches.
 *
 * One Firestore document belongs to the Owner workspace, NOT to the
 * selected branch's local SharedPreferences. Staff can read (never write).
 * No payment API secrets or credentials are stored in this public QR doc.
 */
final class SharedPaymentQr {
  private static final int MAX_ENCODED_CHARS = 650000;
  private final MainActivity host;
  private final FirebaseFirestore db;
  private ListenerRegistration subscription;
  private String listenerKey="";
  private byte[] image;
  private boolean fetched=false;
  private boolean stale=false;
  private boolean uploadBusy=false;
  SharedPaymentQr(MainActivity activity){
    host=activity;
    db=FirebaseFirestore.getInstance();
  }
  private DocumentReference reference(){
    return db.collection("workspaces").document(host.branchAccounts.ownerUid())
      .collection("paymentConfig").document("mainQr");
  }
  void attach(){
    BranchAccounts acc=host.branchAccounts;
    if(!acc.active()||acc.user()==null){stop();return;}
    String key=acc.ownerUid()+":"+acc.user().getUid()+":"+acc.branchId();
    if(key.equals(listenerKey)&&subscription!=null)return;
    stop();
    listenerKey=key;
    subscription=reference().addSnapshotListener((doc,error)->{
      if(!key.equals(listenerKey))return;
      if(error!=null){
        fetched=false;
        // Never silently use an old branch's QR when permissions are denied.
        image=null;
        if(host.activePage==4)
          host.message("QR Cloud tidak dapat dibaca: "+error.getMessage());
        return;
      }
      if(doc==null)return;
      fetched=true;
      stale=doc.getMetadata().isFromCache();
      if(!doc.exists()){
        image=null;
      }else{
        String encoded=doc.getString("imageBase64");
        try{
          byte[] candidate=encoded==null?null:Base64.decode(encoded,Base64.DEFAULT);
          if(candidate!=null&&candidate.length>0&&
            BitmapFactory.decodeByteArray(candidate,0,candidate.length)!=null){
            image=candidate;
          }else image=null;
        }catch(Exception e){image=null;}
      }
    });
  }
  void stop(){
    if(subscription!=null){subscription.remove();subscription=null;}
    listenerKey="";fetched=false;stale=false;image=null;
  }
  boolean hasQr(){return image!=null&&image.length>0;}
  boolean loaded(){return fetched;}
  boolean fromCache(){return stale;}
  boolean display(ImageView target){
    if(!hasQr())return false;
    Bitmap bitmap=BitmapFactory.decodeByteArray(image,0,image.length);
    if(bitmap==null)return false;
    target.setImageBitmap(bitmap);
    target.setAdjustViewBounds(true);
    target.setScaleType(ImageView.ScaleType.FIT_CENTER);
    return true;
  }
  void upload(Uri source){
    BranchAccounts acc=host.branchAccounts;
    if(!acc.owner()||acc.user()==null){
      host.message("Owner sahaja boleh upload QR ke database.");return;
    }
    if(uploadBusy){host.message("QR sedang dihantar ke Firebase. Tunggu sebentar.");return;}
    final String ownerId=acc.ownerUid();
    uploadBusy=true;
    new Thread(()->{
      try{
        byte[] png=encodePng(source);
        String encoded=Base64.encodeToString(png,Base64.NO_WRAP);
        if(encoded.length()>MAX_ENCODED_CHARS)
          throw new IllegalArgumentException("QR melebihi saiz selamat Firestore.");
        host.runOnUiThread(()->{
          if(!acc.owner()||!ownerId.equals(acc.ownerUid())){
            uploadBusy=false;host.message("Akaun Owner berubah. Upload QR dibatalkan.");return;
          }
          Map<String,Object> doc=new HashMap<>();
          doc.put("imageBase64",encoded);
          doc.put("contentType","image/png");
          doc.put("updatedAt",FieldValue.serverTimestamp());
          // Do not replace the active QR until Firestore confirms upload.
          reference().set(doc).addOnSuccessListener(v->{
            uploadBusy=false;
            image=png;
            fetched=true;stale=false;
            host.message("QR berjaya disimpan dalam Firebase. Semua branch menggunakan QR sama.");
          }).addOnFailureListener(e->{
            uploadBusy=false;
            host.message("QR CLOUD GAGAL. QR lama dikekalkan: "+e.getMessage());
          });
        });
      }catch(Exception error){
        host.runOnUiThread(()->{
          uploadBusy=false;
          host.message("QR tidak dapat disimpan: "+error.getMessage());
        });
      }
    }).start();
  }
  private byte[] encodePng(Uri source)throws Exception{
    BitmapFactory.Options bounds=new BitmapFactory.Options();
    bounds.inJustDecodeBounds=true;
    try(InputStream in=host.getContentResolver().openInputStream(source)){
      if(in==null)throw new IllegalArgumentException("Fail gambar tidak boleh dibaca.");
      BitmapFactory.decodeStream(in,null,bounds);
    }
    if(bounds.outWidth<=0||bounds.outHeight<=0)
      throw new IllegalArgumentException("Gambar QR tidak sah.");
    BitmapFactory.Options options=new BitmapFactory.Options();
    options.inScaled=false;options.inSampleSize=1;
    while(bounds.outWidth/options.inSampleSize>1400||
      bounds.outHeight/options.inSampleSize>1400)options.inSampleSize*=2;
    Bitmap original;
    try(InputStream in=host.getContentResolver().openInputStream(source)){
      if(in==null)throw new IllegalArgumentException("Fail gambar tidak boleh dibaca.");
      original=BitmapFactory.decodeStream(in,null,options);
    }
    if(original==null)throw new IllegalArgumentException("Tidak boleh baca gambar QR.");
    Bitmap current=original;
    for(int max=1050;max>=320;max=max>=600?max-150:max-70){
      float factor=Math.min(1f,Math.min(max/(float)current.getWidth(),max/(float)current.getHeight()));
      int width=Math.max(1,Math.round(current.getWidth()*factor));
      int height=Math.max(1,Math.round(current.getHeight()*factor));
      Bitmap scaled=width==current.getWidth()&&height==current.getHeight()
        ?current:Bitmap.createScaledBitmap(current,width,height,false);
      ByteArrayOutputStream bytes=new ByteArrayOutputStream();
      scaled.compress(Bitmap.CompressFormat.PNG,100,bytes);
      byte[] data=bytes.toByteArray();
      if(data.length*4L/3+8<=MAX_ENCODED_CHARS)return data;
      current=scaled;
    }
    throw new IllegalArgumentException("Gambar terlalu besar untuk Firestore. Crop kepada kawasan QR sahaja dan upload semula.");
  }
}
