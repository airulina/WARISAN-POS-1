package my.warisan.pos;

import android.app.*;
import android.os.*;
import android.bluetooth.*;
import android.content.pm.PackageManager;
import android.Manifest;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.json.*;

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
    LinearLayout header=row();ImageView logo=new ImageView(this);logo.setImageResource(R.drawable.warisan_logo);logo.setScaleType(ImageView.ScaleType.FIT_CENTER);add(header,logo,49,49);
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
  void history(){new AlertDialog.Builder(this).setTitle("Rekod & operasi").setItems(new String[]{"Jualan hari ini","Jualan bulanan (12 bulan)","Stok awal / tambah stok","Baki stok","Duit masuk / keluar","No. telefon resit"},(d,which)->{
    if(which==0)new AlertDialog.Builder(this).setTitle("Rekod hari ini · "+date()).setMessage("Jualan: "+money(getPreferences(0).getInt("sales_"+date(),0))+"\nPesanan selesai: "+getPreferences(0).getInt("orders_"+date(),0)).setPositiveButton("Tutup",null).show();
    else if(which==1)monthly();else if(which==2)stockEntry();else if(which==3)stockBalance();else if(which==4)cashBook();else editPhone();
  }).show();}
  void monthly(){Calendar now=Calendar.getInstance();String[] labels=new String[12];String[] keys=new String[12];
    for(int i=0;i<12;i++){Calendar c=(Calendar)now.clone();c.add(Calendar.MONTH,-i);keys[i]=new SimpleDateFormat("yyyy-MM",Locale.US).format(c.getTime());labels[i]=new SimpleDateFormat("MMMM yyyy",new Locale("ms","MY")).format(c.getTime())+"  ·  "+money(monthTotal(keys[i]));}
    new AlertDialog.Builder(this).setTitle("Jualan 12 bulan terakhir").setItems(labels,(d,i)->monthDetail(keys[i])).setPositiveButton("Tutup",null).show();}
  int monthTotal(String month){int total=0;for(int day=1;day<=31;day++)total+=getPreferences(0).getInt("sales_"+month+String.format(Locale.US,"-%02d",day),0);return total;}
  void monthDetail(String month){StringBuilder detail=new StringBuilder();int orders=0;for(int day=1;day<=31;day++){String key=month+String.format(Locale.US,"-%02d",day);int amount=getPreferences(0).getInt("sales_"+key,0);orders+=getPreferences(0).getInt("orders_"+key,0);if(amount!=0)detail.append(key).append("  ·  ").append(money(amount)).append("\n");}
    new AlertDialog.Builder(this).setTitle(month).setMessage("Jumlah: "+money(monthTotal(month))+"\nPesanan: "+orders+"\n\n"+(detail.length()==0?"Tiada jualan direkod.":detail.toString())).setPositiveButton("Tutup",null).show();}
  JSONArray entries(String key){try{return new JSONArray(getPreferences(0).getString(key,"[]"));}catch(JSONException e){return new JSONArray();}}
  void appendEntry(String key,JSONObject entry){JSONArray list=entries(key);list.put(entry);getPreferences(0).edit().putString(key,list.toString()).apply();}
  String timestamp(){return new SimpleDateFormat("dd/MM/yyyy HH:mm",Locale.US).format(new Date());}
  void stockEntry(){String[] options=new String[names.length+1];options[0]="Lihat baki stok";System.arraycopy(names,0,options,1,names.length);
    new AlertDialog.Builder(this).setTitle("Stok · pilih menu").setItems(options,(d,selection)->{if(selection==0){stockBalance();return;}int id=selection-1;
      EditText input=new EditText(this);input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);input.setHint("Bilangan unit / cucuk");
      new AlertDialog.Builder(this).setTitle("Tambah stok: "+names[id]).setView(input).setPositiveButton("Simpan",(dialog,w)->{try{int value=Integer.parseInt(input.getText().toString().trim());if(value<=0||value>100000)throw new NumberFormatException();JSONObject record=new JSONObject();record.put("time",timestamp());record.put("item",id);record.put("qty",value);appendEntry("stock_entries",record);message("Stok ditambah: "+value+" · "+names[id]);}catch(Exception e){message("Masukkan bilangan stok yang sah");}}).setNegativeButton("Batal",null).show();
    }).setNegativeButton("Tutup",null).show();}
  int[] soldQty(){int[] sold=new int[names.length];JSONArray list=entries("stock_sales");for(int i=0;i<list.length();i++){JSONObject record=list.optJSONObject(i);if(record==null)continue;JSONArray q=record.optJSONArray("qty");if(q!=null)for(int j=0;j<sold.length;j++)sold[j]+=q.optInt(j); }return sold;}
  void stockBalance(){int[] added=new int[names.length],sold=soldQty();JSONArray list=entries("stock_entries");for(int i=0;i<list.length();i++){JSONObject entry=list.optJSONObject(i);if(entry!=null){int id=entry.optInt("item",-1);if(id>=0&&id<added.length)added[id]+=entry.optInt("qty");}}
    StringBuilder b=new StringBuilder("Stok dimasukkan − jualan sejak fungsi stok digunakan\n\n");for(int i=0;i<names.length;i++)b.append(names[i]).append(": ").append(added[i]-sold[i]).append("\n  Masuk ").append(added[i]).append(" · Terjual ").append(sold[i]).append("\n");
    new AlertDialog.Builder(this).setTitle("Baki stok").setMessage(b.toString()).setPositiveButton("Tutup",null).setNeutralButton("Tambah stok",(d,w)->stockEntry()).show();}
  void cashBook(){new AlertDialog.Builder(this).setTitle("Duit masuk / keluar").setItems(new String[]{"Lihat rekod bulan ini","Catat duit masuk","Catat duit keluar"},(d,index)->{if(index==0)cashHistory();else cashEntry(index==1);}).show();}
  void cashEntry(boolean incoming){LinearLayout form=col();form.setPadding(dp(20),dp(5),dp(20),0);EditText amount=new EditText(this);amount.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);amount.setHint("Jumlah RM, contoh 10.50");add(form,amount,-1,-2);EditText note=new EditText(this);note.setSingleLine(true);note.setHint("Catatan, contoh modal / beli arang");add(form,note,-1,-2);
    new AlertDialog.Builder(this).setTitle(incoming?"Catat duit masuk":"Catat duit keluar").setView(form).setPositiveButton("Simpan",(d,w)->{try{java.math.BigDecimal rm=new java.math.BigDecimal(amount.getText().toString().trim());int cents=rm.movePointRight(2).intValueExact();if(cents<=0)throw new Exception();JSONObject entry=new JSONObject();entry.put("time",timestamp());entry.put("month",date().substring(0,7));entry.put("amount",incoming?cents:-cents);entry.put("note",note.getText().toString().trim());appendEntry("cash_entries",entry);message("Catatan disimpan");}catch(Exception e){message("Masukkan jumlah RM yang sah");}}).setNegativeButton("Batal",null).show();}
  void cashHistory(){String month=date().substring(0,7);JSONArray list=entries("cash_entries");int incoming=0,outgoing=0;StringBuilder b=new StringBuilder();for(int i=0;i<list.length();i++){JSONObject entry=list.optJSONObject(i);if(entry==null||!month.equals(entry.optString("month")))continue;int value=entry.optInt("amount");if(value>0)incoming+=value;else outgoing-=value;b.append(entry.optString("time")).append(value>0?"  MASUK ":"  KELUAR ").append(money(Math.abs(value))).append("\n").append(entry.optString("note")).append("\n\n");}
    new AlertDialog.Builder(this).setTitle("Duit masuk / keluar · "+month).setMessage("Masuk: "+money(incoming)+"\nKeluar: "+money(outgoing)+"\nBaki catatan: "+money(incoming-outgoing)+"\n\n"+(b.length()==0?"Belum ada catatan.":b.toString())+"\nJualan POS direkod berasingan.").setPositiveButton("Tutup",null).show();}
  void editPhone(){EditText input=new EditText(this);input.setSingleLine(true);input.setInputType(android.text.InputType.TYPE_CLASS_PHONE);input.setText(getPreferences(0).getString("receipt_phone",""));input.setHint("Contoh: 012-345 6789");
    new AlertDialog.Builder(this).setTitle("No. telefon pada resit").setView(input).setPositiveButton("Simpan",(d,w)->{getPreferences(0).edit().putString("receipt_phone",input.getText().toString().trim()).apply();message("No. telefon resit disimpan");}).setNegativeButton("Batal",null).show();}
  void pay(){if(sum()==0){Toast.makeText(this,"Tambah menu dahulu",Toast.LENGTH_SHORT).show();return;}final int due=sum();
    new AlertDialog.Builder(this).setTitle("Bayaran "+money(due)).setItems(new String[]{"Tunai","QR / DuitNow"},(dialog,index)->confirm(index==0?"Tunai":"QR / DuitNow",due)).setNegativeButton("Batal",null).show();}
  String line(String left,String right){int spaces=Math.max(1,32-left.length()-right.length());return left+String.format(Locale.US,"%"+spaces+"s","")+right+"\n";}
  String receipt(String method,int due,int order){StringBuilder b=new StringBuilder();
    b.append("          WARISAN FROZEN\n");
    String phone=getPreferences(0).getString("receipt_phone","");if(!phone.isEmpty())b.append("       Tel: ").append(phone).append("\n");
    b.append("--------------------------------\n").append(line("No. resit",String.format(Locale.US,"#%04d",order)))
      .append(line("Tarikh",new SimpleDateFormat("dd/MM/yy HH:mm",Locale.US).format(new Date()))).append("--------------------------------\n");
    for(int i=0;i<7;i++)if(qty[i]>0){b.append(names[i]).append("\n");b.append(line("  "+qty[i]+" x "+money(prices[i]),money(qty[i]*prices[i])));}
    return b.append("--------------------------------\n").append(line("JUMLAH",money(due))).append(line("BAYAR",method))
      .append("================================\n       TERIMA KASIH!\n   Sila datang lagi.\n\n\n").toString();}
  void confirm(String method,int due){new AlertDialog.Builder(this).setTitle("Sahkan bayaran").setMessage(method+" · "+money(due)+"\n\nPastikan bayaran sudah diterima sebelum simpan.")
    .setPositiveButton("Bayaran diterima",(d,w)->{
      int order=getPreferences(0).getInt("orders_"+date(),0)+1;
      int[] purchased=Arrays.copyOf(qty,qty.length);
      String printed=receipt(method,due,order);
      getPreferences(0).edit().putInt("sales_"+date(),getPreferences(0).getInt("sales_"+date(),0)+due).putInt("orders_"+date(),order).apply();
      try{JSONObject sale=new JSONObject();sale.put("time",timestamp());sale.put("qty",new JSONArray(purchased));appendEntry("stock_sales",sale);}catch(JSONException ignored){}
      Arrays.fill(qty,0);draw();
      previewReceipt(printed,purchased,method,due,order);
    }).setNegativeButton("Kembali",null).show();}
  void receiptRule(LinearLayout sheet){View rule=new View(this);rule.setBackgroundColor(0xffe5e7e2);LinearLayout.LayoutParams lp=params(-1,1);lp.setMargins(0,dp(13),0,dp(13));sheet.addView(rule,lp);}
  void receiptRow(LinearLayout sheet,String label,String value,boolean highlight){
    LinearLayout r=row();TextView left=text(label,highlight?17:13,highlight?green:muted,highlight);TextView right=text(value,highlight?20:13,highlight?green:ink,true);
    r.addView(left,new LinearLayout.LayoutParams(0,-2,1));add(r,right,-2,-2);add(sheet,r,-1,-2);
  }
  void previewReceipt(String printed,int[] purchased,String method,int due,int order){
    ScrollView scroll=new ScrollView(this);
    LinearLayout sheet=col();sheet.setPadding(dp(18),dp(12),dp(18),dp(14));sheet.setBackgroundColor(Color.WHITE);scroll.addView(sheet);
    ImageView logo=new ImageView(this);logo.setImageResource(R.drawable.warisan_logo);logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
    add(sheet,logo,-1,74);gap(sheet,5);
    TextView brand=text("WARISAN FROZEN",17,green,true);brand.setGravity(Gravity.CENTER);add(sheet,brand,-1,-2);
    String phone=getPreferences(0).getString("receipt_phone","");
    TextView contact=text(phone.isEmpty()?"No. telefon belum diisi":"Tel: "+phone,11,muted,false);contact.setGravity(Gravity.CENTER);add(sheet,contact,-1,-2);
    receiptRule(sheet);
    receiptRow(sheet,"RESIT BAYARAN",String.format(Locale.US,"#%04d",order),false);gap(sheet,4);
    receiptRow(sheet,"Tarikh",new SimpleDateFormat("dd/MM/yyyy HH:mm",Locale.US).format(new Date()),false);
    receiptRule(sheet);
    for(int i=0;i<purchased.length;i++)if(purchased[i]>0){receiptRow(sheet,names[i],money(prices[i]*purchased[i]),false);
      TextView details=text(purchased[i]+" × "+money(prices[i]),11,muted,false);add(sheet,details,-1,-2);gap(sheet,10);}
    receiptRule(sheet);receiptRow(sheet,"JUMLAH",money(due),true);gap(sheet,8);
    receiptRow(sheet,"Kaedah bayaran",method,false);receiptRule(sheet);
    TextView thanks=text("Terima kasih! Sila datang lagi.",12,muted,false);thanks.setGravity(Gravity.CENTER);add(sheet,thanks,-1,-2);
    new AlertDialog.Builder(this).setTitle("Semak resit").setView(scroll)
      .setPositiveButton("Cetak resit",(dialog,button)->print(printed))
      .setNegativeButton("Tutup",null).show();
  }
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
  void printLogo(OutputStream out)throws Exception{
    Bitmap source=BitmapFactory.decodeResource(getResources(),R.drawable.warisan_logo);
    if(source==null)return;
    int side=Math.min(source.getHeight(),source.getWidth());
    Bitmap square=Bitmap.createBitmap(source,(source.getWidth()-side)/2,0,side,side);
    Bitmap image=Bitmap.createScaledBitmap(square,192,192,true);
    out.write(new byte[]{27,97,1}); // pusatkan logo
    out.write(new byte[]{29,118,48,0,24,0,(byte)192,0}); // ESC/POS 192 x 192
    int[][] dither={{0,8,2,10},{12,4,14,6},{3,11,1,9},{15,7,13,5}};
    for(int y=0;y<192;y++)for(int bx=0;bx<24;bx++){
      int bits=0;
      for(int bit=0;bit<8;bit++){
        int x=bx*8+bit,c=image.getPixel(x,y);
        int gray=(Color.red(c)*30+Color.green(c)*59+Color.blue(c)*11)/100;
        int dx=x-96,dy=y-96;
        if(dx*dx+dy*dy<90*90&&gray<115+dither[y%4][x%4]*6)bits|=128>>bit;
      }
      out.write(bits);
    }
    out.write(new byte[]{10,27,97,0});
    image.recycle();square.recycle();source.recycle();
  }
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
        out.write(new byte[]{27,64});printLogo(out);
        int split=receipt.indexOf('\n');
        out.write(new byte[]{27,97,1,27,69,1}); // tajuk di tengah dan tebal
        out.write(receipt.substring(0,split+1).getBytes(StandardCharsets.US_ASCII));
        out.write(new byte[]{27,69,0,27,97,0});
        out.write(receipt.substring(split+1).getBytes(StandardCharsets.US_ASCII));
        out.flush();
        runOnUiThread(()->message("Resit berjaya dihantar"));
      }catch(Exception e){String error=e.getMessage();runOnUiThread(()->new AlertDialog.Builder(this).setTitle("Cetakan gagal").setMessage(error==null?"Semak printer dan cuba lagi.":error).setPositiveButton("OK",null).show());}
      finally{if(socket!=null)try{socket.close();}catch(Exception ignored){}}
    }).start();
  }
}
