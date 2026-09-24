package my.warisan.pos;

import android.app.*;
import android.os.*;
import android.bluetooth.*;
import android.content.pm.PackageManager;
import android.Manifest;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
  final String[] names={"Sate Ayam","Sate Daging","Sate Kambing","Nasi Impit","Extra Kuah Kacang","Laksa Utara","Kuih Siput"};
  final String[] icons={"🍢","🥩","🍢","🍚","🥣","🍜","🥨"};
  final int[] prices={160,180,200,60,100,700,500}, qty=new int[7];
  final int green=Color.rgb(21,57,46), ink=Color.rgb(32,49,43), gold=Color.rgb(189,137,47), cream=Color.rgb(249,247,240), muted=Color.rgb(105,113,104);
  LinearLayout body,basket; TextView total,today,items; Button payButton;
  static final int REQUEST_BLUETOOTH=42;
  final UUID printerUuid=UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
  String pendingReceipt;
  int dp(int x){return (int)(x*getResources().getDisplayMetrics().density+.5f);}
  String money(int cents){return String.format(Locale.US,"RM %.2f",cents/100.0);}
  String date(){return new SimpleDateFormat("yyyy-MM-dd",Locale.US).format(new Date());}
  int sum(){int n=0;for(int i=0;i<7;i++)n+=qty[i]*prices[i];return n;}
  int count(){int n=0;for(int q:qty)n+=q;return n;}
  GradientDrawable shape(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
  TextView text(String s,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);if(bold)t.setTypeface(null,Typeface.BOLD);t.setGravity(Gravity.CENTER_VERTICAL);return t;}
  LinearLayout col(){LinearLayout l=new LinearLayout(this);l.setOrientation(1);return l;}
  LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(0);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
  LinearLayout.LayoutParams params(int w,int h){return new LinearLayout.LayoutParams(w<0?w:dp(w),h<0?h:dp(h));}
  void add(LinearLayout l,View v,int w,int h){l.addView(v,params(w,h));}
  void gap(LinearLayout l,int h){add(l,new View(this),1,h);}
  TextView chip(String s,int back,int fore){TextView t=text(s,13,fore,true);t.setGravity(Gravity.CENTER);t.setBackground(shape(back,11));t.setPadding(dp(8),dp(5),dp(8),dp(5));return t;}
  @Override public void onCreate(Bundle b){super.onCreate(b);if(b!=null){int[] saved=b.getIntArray("cart");if(saved!=null&&saved.length==7)System.arraycopy(saved,0,qty,0,7);}draw();}
  @Override protected void onSaveInstanceState(Bundle b){b.putIntArray("cart",qty);super.onSaveInstanceState(b);}
  void draw(){
    LinearLayout screen=col();screen.setBackgroundColor(cream);
    getWindow().setStatusBarColor(cream);
    getWindow().setNavigationBarColor(green);
    getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
    if(Build.VERSION.SDK_INT>=35){
      screen.setOnApplyWindowInsetsListener((view,insets)->{
        view.setPadding(0,insets.getSystemWindowInsetTop(),0,insets.getSystemWindowInsetBottom());
        return insets;
      });
    }
    setContentView(screen);
    ScrollView scroll=new ScrollView(this);screen.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
    body=col();body.setPadding(dp(17),dp(15),dp(17),dp(25));scroll.addView(body);
    LinearLayout header=row();add(header,chip("W",gold,Color.WHITE),45,45);
    LinearLayout heading=col();heading.setPadding(dp(10),0,0,0);add(heading,text("WARISAN POS",21,green,true),-1,-2);add(heading,text("KIOS WARISAN  ·  SISTEM JUALAN",10,muted,true),-1,-2);header.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
    TextView history=chip("REKOD",green,Color.WHITE);history.setOnClickListener(v->history());add(header,history,-2,-2);
    TextView printer=chip("🖨",0xffeee8d9,green);LinearLayout.LayoutParams printerLp=params(41,41);printerLp.leftMargin=dp(5);header.addView(printer,printerLp);printer.setOnClickListener(v->choosePrinter());
    add(body,header,-1,-2);gap(body,18);
    LinearLayout hero=col();hero.setPadding(dp(18),dp(14),dp(18),dp(14));hero.setBackground(shape(green,17));
    add(hero,text("JUALAN HARI INI",11,0xffdce5d9,true),-1,-2);today=text("RM 0.00",29,Color.WHITE,true);add(hero,today,-1,-2);add(hero,text("Bayaran selesai direkod di sini",12,0xffdce5d9,false),-1,-2);add(body,hero,-1,-2);today();gap(body,20);
    LinearLayout title=row();title.addView(text("Pilih menu",20,ink,true),new LinearLayout.LayoutParams(0,-2,1));items=chip("0 item",0xffeee8d9,green);add(title,items,-2,-2);add(body,title,-1,-2);
    add(body,text("Tekan + untuk tambah pesanan",12,muted,false),-1,-2);gap(body,13);
    for(int i=0;i<7;i+=2){LinearLayout pair=row();pair.setGravity(Gravity.TOP);product(pair,i);if(i+1<7)product(pair,i+1);add(body,pair,-1,-2);gap(body,9);}
    gap(body,10);add(body,text("Pesanan semasa",20,ink,true),-1,-2);gap(body,10);
    basket=col();basket.setPadding(dp(13),dp(10),dp(13),dp(10));basket.setBackground(shape(Color.WHITE,15));add(body,basket,-1,-2);
    LinearLayout footer=row();footer.setPadding(dp(17),dp(9),dp(17),dp(10));footer.setBackgroundColor(Color.WHITE);
    LinearLayout amount=col();add(amount,text("JUMLAH",11,muted,true),-1,-2);total=text("RM 0.00",22,green,true);add(amount,total,-1,-2);footer.addView(amount,new LinearLayout.LayoutParams(0,-2,1));
    payButton=new Button(this);payButton.setAllCaps(false);payButton.setText("Bayar  →");payButton.setTextColor(Color.WHITE);payButton.setTextSize(16);payButton.setBackground(shape(green,12));payButton.setOnClickListener(v->pay());add(footer,payButton,140,51);add(screen,footer,-1,-2);refresh();
  }
  void product(LinearLayout pair,int id){
    LinearLayout card=col();card.setPadding(dp(11),dp(11),dp(11),dp(11));card.setBackground(shape(Color.WHITE,15));
    TextView icon=chip(icons[id],0xfffbf2de,green);icon.setTextSize(22);add(card,icon,42,42);gap(card,9);
    TextView name=text(names[id],15,ink,true);name.setMinHeight(dp(40));add(card,name,-1,-2);
    add(card,text(money(prices[id]),15,gold,true),-1,-2);gap(card,10);
    LinearLayout controls=row();TextView minus=chip("−",0xffedf1ea,green),number=text(""+qty[id],15,ink,true),plus=chip("+",green,Color.WHITE);number.setGravity(Gravity.CENTER);
    controls.addView(minus,new LinearLayout.LayoutParams(0,dp(35),1));controls.addView(number,new LinearLayout.LayoutParams(0,dp(35),1));controls.addView(plus,new LinearLayout.LayoutParams(0,dp(35),1));add(card,controls,-1,-2);
    minus.setOnClickListener(v->{qty[id]=Math.max(0,qty[id]-1);number.setText(""+qty[id]);refresh();});
    plus.setOnClickListener(v->{qty[id]++;number.setText(""+qty[id]);refresh();});
    if(id<3){gap(card,8);LinearLayout presets=row();for(int n:new int[]{10,20,30}){TextView p=chip("+"+n,0xfffbf2de,green);LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(0,dp(30),1);pp.setMargins(dp(1),0,dp(1),0);presets.addView(p,pp);p.setOnClickListener(v->{qty[id]+=n;number.setText(""+qty[id]);refresh();});}add(card,presets,-1,-2);}
    LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(0,-2,1);cp.setMargins(dp(2),0,dp(2),0);pair.addView(card,cp);
  }
  void refresh(){items.setText(count()+" item");total.setText(money(sum()));payButton.setAlpha(sum()==0?.55f:1f);basket.removeAllViews();
    if(count()==0){TextView empty=text("Belum ada pesanan. Pilih menu di atas.",13,muted,false);empty.setPadding(0,dp(13),0,dp(13));add(basket,empty,-1,-2);return;}
    for(int i=0;i<7;i++)if(qty[i]>0){final int id=i;LinearLayout r=row();r.addView(text(names[i]+" × "+qty[i],14,ink,true),new LinearLayout.LayoutParams(0,dp(39),1));add(r,text(money(qty[i]*prices[i]),13,green,true),-2,-2);TextView minus=chip("−",0xfff5eee1,green);LinearLayout.LayoutParams m=params(32,30);m.leftMargin=dp(8);r.addView(minus,m);minus.setOnClickListener(v->{qty[id]--;draw();});add(basket,r,-1,-2);}
  }
  void today(){today.setText(money(getPreferences(0).getInt("sales_"+date(),0)));}
  void history(){new AlertDialog.Builder(this).setTitle("Rekod hari ini · "+date()).setMessage("Jualan: "+money(getPreferences(0).getInt("sales_"+date(),0))+"\nPesanan selesai: "+getPreferences(0).getInt("orders_"+date(),0)+"\n\nRekod disimpan pada telefon ini.").setPositiveButton("Tutup",null).show();}
  void pay(){if(sum()==0){Toast.makeText(this,"Tambah menu dahulu",Toast.LENGTH_SHORT).show();return;}final int due=sum();
    new AlertDialog.Builder(this).setTitle("Bayaran "+money(due)).setItems(new String[]{"Tunai","QR / DuitNow"},(dialog,index)->confirm(index==0?"Tunai":"QR / DuitNow",due)).setNegativeButton("Batal",null).show();}
  String receipt(String method,int due,int order){StringBuilder b=new StringBuilder();
    b.append("WARISAN POS\nKIOS WARISAN\n").append(new SimpleDateFormat("dd/MM/yyyy HH:mm",Locale.US).format(new Date())).append("  #").append(order).append("\n--------------------------------\n");
    for(int i=0;i<7;i++)if(qty[i]>0)b.append(names[i]).append(" x").append(qty[i]).append("  ").append(money(qty[i]*prices[i])).append("\n");
    return b.append("--------------------------------\nJUMLAH  ").append(money(due)).append("\nBAYARAN  ").append(method).append("\nTERIMA KASIH\n\n\n").toString();}
  void confirm(String method,int due){new AlertDialog.Builder(this).setTitle("Sahkan bayaran").setMessage(method+" · "+money(due)+"\n\nPastikan bayaran sudah diterima sebelum simpan.")
    .setPositiveButton("Bayaran diterima",(d,w)->{
      int order=getPreferences(0).getInt("orders_"+date(),0)+1;
      String printed=receipt(method,due,order);
      getPreferences(0).edit().putInt("sales_"+date(),getPreferences(0).getInt("sales_"+date(),0)+due).putInt("orders_"+date(),order).apply();
      Arrays.fill(qty,0);draw();
      new AlertDialog.Builder(this).setTitle("Pesanan selesai ✓").setMessage(money(due)+" diterima melalui "+method+".")
        .setPositiveButton("Cetak resit",(dialog,button)->print(printed)).setNegativeButton("Pesanan baharu",null).show();
    }).setNegativeButton("Kembali",null).show();}
  void choosePrinter(){
    if(android.os.Build.VERSION.SDK_INT>=31&&checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED){
      requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT},REQUEST_BLUETOOTH);return;
    }
    try{
      BluetoothAdapter adapter=BluetoothAdapter.getDefaultAdapter();
      if(adapter==null){message("Telefon ini tiada Bluetooth");return;}
      if(!adapter.isEnabled()){message("Hidupkan Bluetooth dan pair printer dalam Settings dahulu");return;}
      ArrayList<BluetoothDevice> devices=new ArrayList<>(adapter.getBondedDevices());
      if(devices.isEmpty()){message("Pair printer Bluetooth dalam Settings dahulu");return;}
      String[] labels=new String[devices.size()];
      for(int i=0;i<devices.size();i++)labels[i]=devices.get(i).getName()+" · "+devices.get(i).getAddress();
      new AlertDialog.Builder(this).setTitle("Pilih printer yang sudah dipair").setItems(labels,(d,index)->{
        getPreferences(0).edit().putString("printer",devices.get(index).getAddress()).apply();message("Printer dipilih: "+devices.get(index).getName());
        if(pendingReceipt!=null){String saved=pendingReceipt;pendingReceipt=null;print(saved);}
      }).setNegativeButton("Batal",null).show();
    }catch(SecurityException e){message("Benarkan akses Bluetooth untuk memilih printer");}
  }
  @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){
    super.onRequestPermissionsResult(request,permissions,results);
    if(request==REQUEST_BLUETOOTH&&results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)choosePrinter();
  }
  void message(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
  void print(String receipt){
    String address=getPreferences(0).getString("printer","");
    if(address.isEmpty()){pendingReceipt=receipt;new AlertDialog.Builder(this).setTitle("Printer belum dipilih").setMessage("Pair printer dalam tetapan Bluetooth telefon, kemudian pilih ikon printer di atas.").setPositiveButton("Pilih printer",(d,w)->choosePrinter()).setNegativeButton("Tutup",null).show();return;}
    new Thread(()->{
      BluetoothSocket socket=null;
      try{
        BluetoothAdapter adapter=BluetoothAdapter.getDefaultAdapter();
        if(adapter==null||!adapter.isEnabled())throw new Exception("Bluetooth belum dihidupkan");
        socket=adapter.getRemoteDevice(address).createRfcommSocketToServiceRecord(printerUuid);
        socket.connect();OutputStream out=socket.getOutputStream();
        out.write(new byte[]{27,64});out.write(receipt.getBytes(StandardCharsets.US_ASCII));out.write(new byte[]{10,10,10});out.flush();
        runOnUiThread(()->message("Resit berjaya dihantar"));
      }catch(Exception e){String error=e.getMessage();runOnUiThread(()->new AlertDialog.Builder(this).setTitle("Cetakan gagal").setMessage(error==null?"Semak printer dan cuba lagi.":error).setPositiveButton("OK",null).show());}
      finally{if(socket!=null)try{socket.close();}catch(Exception ignored){}}
    }).start();
  }
}
