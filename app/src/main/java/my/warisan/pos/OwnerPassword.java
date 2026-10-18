package my.warisan.pos;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Optional owner-local password. Kept in its own preferences so app updates,
 * branch changes, exports and routine POS data migration do not reset it.
 * Does not replace Firebase/Google authentication or Firestore security rules.
 */
final class OwnerPassword {
  private static final String STORE="warisan_owner_security_v1";
  private static final int ITERATIONS=150000;
  private final MainActivity activity;
  private final SharedPreferences prefs;
  OwnerPassword(MainActivity host){
    activity=host; prefs=host.getSharedPreferences(STORE,Context.MODE_PRIVATE);
  }
  boolean isSet(){return prefs.contains("digest")&&prefs.contains("salt");}
  private EditText field(String hint){
    EditText edit=new EditText(activity);
    edit.setSingleLine(true);
    edit.setHint(hint);
    edit.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
    activity.styleInput(edit);
    return edit;
  }
  private byte[] derive(char[] password,byte[] salt,String algorithm) throws Exception {
    PBEKeySpec key=new PBEKeySpec(password,salt,ITERATIONS,256);
    try {return SecretKeyFactory.getInstance(algorithm).generateSecret(key).getEncoded();}
    finally {key.clearPassword();}
  }
  boolean verify(String password){
    if(!isSet())return false;
    char[] chars=password.toCharArray();
    try{
      byte[] salt=Base64.decode(prefs.getString("salt",""),Base64.NO_WRAP);
      byte[] known=Base64.decode(prefs.getString("digest",""),Base64.NO_WRAP);
      String algorithm=prefs.getString("algorithm","PBKDF2WithHmacSHA256");
      return MessageDigest.isEqual(known,derive(chars,salt,algorithm));
    }catch(Exception e){return false;}
    finally{Arrays.fill(chars,'\0');}
  }
  boolean savePassword(String password){
    char[] chars=password.toCharArray();
    try {
      byte[] salt=new byte[24];new SecureRandom().nextBytes(salt);
      String algo=android.os.Build.VERSION.SDK_INT>=26?"PBKDF2WithHmacSHA256":"PBKDF2WithHmacSHA1";
      byte[] digest=derive(chars,salt,algo);
      return prefs.edit().putString("salt",Base64.encodeToString(salt,Base64.NO_WRAP))
        .putString("digest",Base64.encodeToString(digest,Base64.NO_WRAP))
        .putString("algorithm",algo).putInt("iterations",ITERATIONS).commit();
    }catch(Exception e){return false;}
    finally{Arrays.fill(chars,'\0');}
  }
  void change(){
    // Staff must never see owner security options. For legacy owners, allow
    // setting a local password without creating a new cloud/Firebase account.
    if(activity.branchAccounts!=null&&activity.branchAccounts.staff()){
      activity.message("Hanya Owner boleh menukar password.");return;
    }
    final boolean previous=isSet();
    LinearLayout form=activity.col();
    form.setPadding(activity.dp(18),0,activity.dp(18),0);
    EditText old=previous?field("Password Owner sekarang"):null;
    EditText fresh=field("Password Owner baharu (min 8 aksara)");
    EditText confirm=field("Ulang password baharu");
    if(old!=null){activity.add(form,old,-1,-2);activity.gap(form,7);}
    activity.add(form,fresh,-1,-2);activity.gap(form,7);
    activity.add(form,confirm,-1,-2);
    activity.darkBuilder().setTitle(previous?"Tukar Password Owner":"Tetapkan Password Owner")
      .setMessage(previous?"Masukkan password semasa. Rekod POS tidak akan dipadam.":"Tiada password Owner lama dalam app. Tetapkan password tempatan tanpa daftar akaun baharu. Rekod POS kekal.")
      .setView(form).setPositiveButton("Simpan",(d,w)->{
        String p=fresh.getText().toString(),q=confirm.getText().toString();
        if(previous&&!verify(old.getText().toString())){
          activity.message("Password Owner lama tidak betul. Data tidak berubah.");return;
        }
        if(p.length()<8){activity.message("Password perlu sekurang-kurangnya 8 aksara.");return;}
        if(!p.equals(q)){activity.message("Pengesahan password tidak sepadan.");return;}
        if(savePassword(p)){activity.message("Password Owner berjaya disimpan. Semua data POS kekal.");activity.draw();}
        else activity.message("Password gagal disimpan; password asal masih aktif.");
      }).setNegativeButton("Batal",null).show();
  }
}
