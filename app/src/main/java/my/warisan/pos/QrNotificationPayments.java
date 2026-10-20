package my.warisan.pos;

import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Owner opt-in. Only one QR dialog on the same device can be armed at a time.
 * Listener receives notifications via Android's permission gateway; does not
 * store notifications, account details, passwords, sender names or message body.
 * A bank notification is a heuristic, NOT a bank-confirmed settlement API.
 */
public final class QrNotificationPayments extends NotificationListenerService {
  private static final String STORE="warisan_qr_notification";
  private static final String ENABLED="enabled",SOURCE="source_package",PRINT="auto_print",USED="used_fingerprints";
  private static final long VALID_FOR_MS=180000L;
  private static final Object LOCK=new Object();
  private static Session current;
  private static Capture capture;

  static final class Session {
    final WeakReference<MainActivity> activity;
    final WeakReference<AlertDialog> dialog;
    final String ownerUid,branchId,userId,day,orderToken;
    final int due,index;
    final boolean pending;
    final int[] cart;
    final long opened;
    Session(MainActivity host,AlertDialog popup,int index,int amount,boolean pending){
      activity=new WeakReference<>(host);dialog=new WeakReference<>(popup);
      ownerUid=host.branchAccounts.ownerUid();branchId=host.branchAccounts.branchId();
      userId=host.branchAccounts.user()==null?"":host.branchAccounts.user().getUid();
      day=host.businessDay();this.index=index;due=amount;this.pending=pending;
      if(pending){
        org.json.JSONObject o=host.entries("pending_orders").optJSONObject(index);
        orderToken=o==null?"":o.optString("liveId","")+"|"+o.optInt("order")+"|"+o.optString("time","");
      }else orderToken="";
      cart=Arrays.copyOf(host.qty,host.qty.length);
      opened=System.currentTimeMillis();
    }
  }
  static final class Capture {
    final WeakReference<MainActivity> host;
    final WeakReference<AlertDialog> dialog;
    Capture(MainActivity h,AlertDialog d){host=new WeakReference<>(h);dialog=new WeakReference<>(d);}
  }
  private static SharedPreferences prefs(Context context){
    return context.getSharedPreferences(STORE,Context.MODE_PRIVATE);
  }
  static boolean enabled(Context ctx){
    return prefs(ctx).getBoolean(ENABLED,false);
  }
  static boolean autoPrint(Context ctx){
    return prefs(ctx).getBoolean(PRINT,true);
  }
  static String source(Context ctx){
    return prefs(ctx).getString(SOURCE,"");
  }
  static boolean hasPermission(Context ctx){
    if(Build.VERSION.SDK_INT>=27){
      NotificationManager nm=(NotificationManager)ctx.getSystemService(Context.NOTIFICATION_SERVICE);
      return nm!=null&&nm.isNotificationListenerAccessGranted(
        new ComponentName(ctx,QrNotificationPayments.class));
    }
    String enabled=Settings.Secure.getString(ctx.getContentResolver(),"enabled_notification_listeners");
    return enabled!=null&&enabled.contains(ctx.getPackageName()+"/");
  }
  static void settings(MainActivity host){
    if(!host.branchAccounts.owner()){
      host.message("Tetapan QR automatik hanya untuk Owner.");return;
    }
    String src=source(host);
    String label=src.isEmpty()?"Belum dipilih":src;
    String status=enabled(host)?"ON":"OFF";
    String[] choices={
      "Auto sahkan notifikasi QR · "+status,
      "Pilih aplikasi notifikasi bank/TNG · "+label,
      "Kebenaran Notification Access · "+(hasPermission(host)?"DIBENARKAN":"BELUM DIBENARKAN"),
      "Auto cetak resit thermal · "+(autoPrint(host)?"ON":"OFF"),
      "Cara pengesahan & risiko"
    };
    host.darkBuilder().setTitle("QR AUTOMATIK · "+status)
      .setItems(choices,(d,i)->{
        if(i==0){
          if(enabled(host)){
            prefs(host).edit().putBoolean(ENABLED,false).commit();
            disarmAll(host);host.message("Auto pengesahan QR dimatikan.");settings(host);return;
          }
          if(!hasPermission(host)){showPermission(host);return;}
          if(source(host).isEmpty()){host.message("Pilih sumber notifikasi bank dahulu.");settings(host);return;}
          host.darkBuilder().setTitle("AKTIFKAN AUTO PENGESAHAN?")
            .setMessage("Ini membaca notifikasi daripada aplikasi yang dipilih pada telefon INI SAHAJA. "+
              "Jumlah RM mesti tepat, mesej jelas menyatakan DUIT DITERIMA, dan QR order masih terbuka. "+
              "Notifikasi bukan bukti muktamad bank dan boleh tersalah padan apabila dua bayaran sama jumlah. "+
              "Untuk ketepatan penuh, guna QR dinamik + API bank yang mengesahkan transaksi.")
            .setPositiveButton("AKTIFKAN",(dd,w)->{
              prefs(host).edit().putBoolean(ENABLED,true).commit();settings(host);
            }).setNegativeButton("BATAL",null).show();
        }else if(i==1)chooseSource(host);
        else if(i==2)showPermission(host);
        else if(i==3){
          prefs(host).edit().putBoolean(PRINT,!autoPrint(host)).commit();settings(host);
        }else host.darkBuilder().setTitle("NOTIFIKASI QR")
          .setMessage("1. Buka QR order di telefon yang menerima notifikasi.\n"+
            "2. Hanya sumber aplikasi yang Owner pilih diterima.\n"+
            "3. Jumlah mesti tepat dan hanya satu angka RM dalam notifikasi.\n"+
            "4. Satu notifikasi hanya boleh dipakai sekali. Notifikasi lama/terlambat diabaikan.\n"+
            "5. Jika notifikasi tidak jelas, semak bank dan tekan BAYARAN DITERIMA manual.\n"+
            "6. Thermal yang dikonfigurasi akan dicuba untuk auto-print. Jika tiada printer, cetakan dilangkau. "+
            "A4/Wi-Fi memerlukan dialog sistem dan tidak auto-print.")
          .setPositiveButton("FAHAM",null).show();
      }).setNegativeButton("TUTUP",null).show();
  }
  static void showPermission(MainActivity host){
    host.darkBuilder().setTitle("BENARKAN BACA NOTIFIKASI")
      .setMessage("Android mesti memberikan akses notifikasi kepada Warisan POS. "+
        "Aplikasi hanya memproses notifikasi daripada sumber yang dipilih dan tidak menyimpan teks notifikasi.")
      .setPositiveButton("BUKA TETAPAN",(d,w)->{
        try{host.startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));}
        catch(Exception e){host.message("Buka tetapan Notification Access telefon secara manual.");}
      }).setNegativeButton("BATAL",null).show();
  }
  static void chooseSource(MainActivity host){
    if(!hasPermission(host)){showPermission(host);return;}
    AlertDialog popup=host.darkBuilder().setTitle("PILIH SUMBER NOTIFIKASI")
      .setMessage("Biarkan halaman ini terbuka. Hantar satu notifikasi baharu daripada aplikasi bank/TNG yang "+
        "menerima bayaran. Nama aplikasi sahaja akan dipaparkan; kandungan transaksi tidak disimpan. "+
        "Jika muncul aplikasi lain, tekan BATAL dan ulang semula.")
      .setNegativeButton("BATAL",null).create();
    synchronized(LOCK){capture=new Capture(host,popup);}
    popup.setOnDismissListener(d->{
      synchronized(LOCK){if(capture!=null&&capture.dialog.get()==popup)capture=null;}
    });
    popup.show();
  }
  static void arm(MainActivity host,AlertDialog dialog,int index,int due,boolean pending){
    if(!enabled(host)||!hasPermission(host)||source(host).isEmpty())return;
    if(due<=0||host.branchAccounts.user()==null)return;
    synchronized(LOCK){current=new Session(host,dialog,index,due,pending);}
  }
  static void disarm(AlertDialog dialog){
    synchronized(LOCK){if(current!=null&&current.dialog.get()==dialog)current=null;}
  }
  static void disarmAll(MainActivity host){
    synchronized(LOCK){if(current!=null&&current.activity.get()==host)current=null;}
  }
  @Override public void onNotificationPosted(StatusBarNotification sbn){
    if(sbn==null||sbn.getNotification()==null)return;
    final String pkg=sbn.getPackageName();
    if(pkg==null||pkg.equals(getPackageName()))return;
    Capture listening;
    synchronized(LOCK){listening=capture;}
    if(listening!=null){
      MainActivity h=listening.host.get();
      AlertDialog d=listening.dialog.get();
      if(h!=null&&d!=null&&d.isShowing()){
        h.runOnUiThread(()->{
          synchronized(LOCK){if(capture!=listening)return;capture=null;}
          d.dismiss();
          String appName=pkg;
          try{appName=h.getPackageManager().getApplicationLabel(
            h.getPackageManager().getApplicationInfo(pkg,0)).toString();}
          catch(Exception ignored){}
          final String chosenName=appName;
          h.darkBuilder().setTitle("GUNA NOTIFIKASI DARIPADA "+chosenName+"?")
            .setMessage("Android package: "+pkg+"\n\nPastikan ini aplikasi bank/merchant rasmi yang menerima duit pelanggan.")
            .setPositiveButton("GUNA SUMBER",(dd,w)->{
              prefs(h).edit().putString(SOURCE,pkg).commit();settings(h);
            }).setNegativeButton("CUBA LAGI",(dd,w)->chooseSource(h)).show();
        });
        return;
      }
    }
    Session s;
    synchronized(LOCK){s=current;}
    if(s==null)return;
    MainActivity host=s.activity.get();AlertDialog dialog=s.dialog.get();
    if(host==null||dialog==null||!dialog.isShowing())return;
    if(!enabled(host)||!hasPermission(host)||!pkg.equals(source(host)))return;
    long now=System.currentTimeMillis(),posted=sbn.getPostTime();
    if(posted<s.opened||posted>now+10000L||now-s.opened>VALID_FOR_MS||
       now-posted>VALID_FOR_MS)return;
    Bundle extras=sbn.getNotification().extras;
    if(extras==null)return;
    String title=String.valueOf(extras.getCharSequence(Notification.EXTRA_TITLE,""));
    CharSequence big=extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
    CharSequence body=big==null?extras.getCharSequence(Notification.EXTRA_TEXT):big;
    String text=body==null?"":body.toString();
    int cents=QrNotificationParser.uniqueIncomingCents(title,text);
    if(cents!=s.due)return;
    String fingerprint=hash(pkg+"|"+sbn.getKey()+"|"+posted+"|"+title+"|"+text);
    if(fingerprint==null)return;
    synchronized(LOCK){
      if(current!=s)return;
      if(!reserveFingerprint(host,fingerprint))return;
      current=null;
    }
    host.runOnUiThread(()->{
      if(!dialog.isShowing())return;
      if(host.branchAccounts.user()==null||
         !s.userId.equals(host.branchAccounts.user().getUid())||
         !s.ownerUid.equals(host.branchAccounts.ownerUid())||
         !s.branchId.equals(host.branchAccounts.branchId())||
         !s.day.equals(host.businessDay()))return;
      if(s.pending){
        org.json.JSONObject o=host.entries("pending_orders").optJSONObject(s.index);
        String token=o==null?"":o.optString("liveId","")+"|"+o.optInt("order")+"|"+o.optString("time","");
        if(o==null||o.optBoolean("paid",false)||o.optBoolean("cancelled",false)||
          o.optInt("total")!=s.due||!token.equals(s.orderToken))return;
      }else if(host.sum()!=s.due||!Arrays.equals(host.qty,s.cart))return;
      dialog.dismiss();
      if(s.pending)host.completePendingPayment(s.index,"QR / DuitNow",s.due,true);
      else host.completePayment("QR / DuitNow",s.due,s.due,true);
    });
  }
  private static String hash(String content){
    try{
      byte[] data=MessageDigest.getInstance("SHA-256").digest(
        content.getBytes(StandardCharsets.UTF_8));
      StringBuilder b=new StringBuilder();
      for(byte v:data)b.append(String.format(Locale.US,"%02x",v&0xff));
      return b.toString();
    }catch(Exception e){return null;}
  }
  private static boolean reserveFingerprint(Context ctx,String key){
    SharedPreferences pref=prefs(ctx);
    Set<String> found=new HashSet<>(pref.getStringSet(USED,new HashSet<>()));
    if(found.contains(key))return false;
    found.add(key);
    // Retain a bounded window of anonymous event hashes, not notification text.
    if(found.size()>150){found.clear();found.add(key);}
    return pref.edit().putStringSet(USED,found).commit();
  }
}
