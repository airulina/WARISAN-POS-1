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
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.media.MediaPlayer;
import android.graphics.drawable.GradientDrawable;
import android.content.Intent;
import android.net.Uri;
import android.view.*;
import android.widget.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.io.OutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.json.*;

public class MainActivity extends Activity {
  String[] names={"Sate Ayam","Sate Daging","Sate Kambing","Nasi Impit","Extra Kuah Kacang","Laksa Utara","Kuih Siput"};
  String[] icons={"🍢","🥩","🍢","🍚","🥣","🍜","🥨"};
  int[] prices={160,180,200,60,100,700,500}, qty=new int[7];
  final int blue=Color.rgb(24,58,104), ink=Color.rgb(19,24,34), gold=Color.rgb(204,154,55), cream=Color.rgb(197,207,222), surface=Color.rgb(231,237,245), muted=Color.rgb(75,87,105);
  LinearLayout body,basket; TextView total,today,items; Button payButton;
  static final int REQUEST_BLUETOOTH=42, EXPORT_CSV=43, EXPORT_PDF=44, PICK_IMAGE=45;
  int exportYear;
  int activePage=0, imageItem=-1;
  String selectedMonth,selectedDay,rangeStart;
  int[] cachedStock;
  final UUID printerUuid=UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
  String pendingReceipt;
  int dp(int x){return (int)(x*getResources().getDisplayMetrics().density+.5f);}
  String money(int cents){return String.format(Locale.US,"RM %.2f",cents/100.0);}
  String date(){return new SimpleDateFormat("yyyy-MM-dd",Locale.US).format(new Date());}
  int sum(){int n=0;for(int i=0;i<qty.length;i++)n+=qty[i]*prices[i];return n;}
  int count(){int n=0;for(int q:qty)n+=q;return n;}
  boolean unlimited(int id){return id==4;}
  String entryDay(JSONObject entry){String time=entry.optString("time","");return time.length()>=10?time.substring(6,10)+"-"+time.substring(3,5)+"-"+time.substring(0,2):"";}
  boolean hasOpeningStock(int id){JSONArray list=entries("stock_entries");for(int i=0;i<list.length();i++){JSONObject record=list.optJSONObject(i);if(record!=null&&record.optInt("item",-1)==id)return true;}return false;}
  int[] stockToday(int id){int[] summary=new int[4];String today=date();JSONArray incoming=entries("stock_entries"),sales=entries("stock_sales");boolean firstLegacy=true;
    for(int i=0;i<incoming.length();i++){JSONObject e=incoming.optJSONObject(i);if(e==null||e.optInt("item",-1)!=id)continue;String day=entryDay(e);int value=e.optInt("qty");boolean opening="opening".equals(e.optString("type"))||(firstLegacy&&!e.has("type"));firstLegacy=false;
      if(day.compareTo(today)<0)summary[0]+=value;else if(day.equals(today)){if(opening)summary[0]+=value;else summary[1]+=value;}}
    for(int i=0;i<sales.length();i++){JSONObject e=sales.optJSONObject(i);if(e==null)continue;JSONArray q=e.optJSONArray("qty");if(q==null)continue;String day=entryDay(e);if(day.compareTo(today)<0)summary[0]-=q.optInt(id);else if(day.equals(today))summary[2]+=q.optInt(id);}
    summary[3]=summary[0]+summary[1]-summary[2];return summary;}
  int stock(int id){if(cachedStock==null||cachedStock.length!=names.length){cachedStock=new int[names.length];JSONArray in=entries("stock_entries"),out=entries("stock_sales");
      for(int n=0;n<in.length();n++){JSONObject e=in.optJSONObject(n);if(e!=null){int item=e.optInt("item",-1);if(item>=0&&item<cachedStock.length)cachedStock[item]+=e.optInt("qty");}}
      for(int n=0;n<out.length();n++){JSONObject e=out.optJSONObject(n);if(e!=null){JSONArray q=e.optJSONArray("qty");if(q!=null)for(int j=0;j<cachedStock.length;j++)cachedStock[j]-=q.optInt(j);}}}
    return Math.max(0,cachedStock[id]);}
  void stockLimit(int id,int amount){if(unlimited(id)){qty[id]+=amount;draw();return;}int available=stock(id)-qty[id];if(available<=0){message(names[id]+" habis. Masukkan stok dahulu.");return;}qty[id]+=Math.min(amount,available);draw();}
  void loadMenuPhoto(ImageView view,String location)throws Exception{Uri uri=Uri.parse(location);BitmapFactory.Options size=new BitmapFactory.Options();size.inJustDecodeBounds=true;
    try(InputStream source=getContentResolver().openInputStream(uri)){BitmapFactory.decodeStream(source,null,size);}
    BitmapFactory.Options scaled=new BitmapFactory.Options();scaled.inSampleSize=1;while(size.outWidth/scaled.inSampleSize>360||size.outHeight/scaled.inSampleSize>360)scaled.inSampleSize*=2;
    try(InputStream source=getContentResolver().openInputStream(uri)){Bitmap bitmap=BitmapFactory.decodeStream(source,null,scaled);if(bitmap==null)throw new IOException("Gambar tidak dapat dibuka");view.setImageBitmap(bitmap);}}
  GradientDrawable shape(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
  TextView text(String s,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);if(bold)t.setTypeface(null,Typeface.BOLD);t.setGravity(Gravity.CENTER_VERTICAL);return t;}
  LinearLayout col(){LinearLayout l=new LinearLayout(this);l.setOrientation(1);return l;}
  LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(0);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
  LinearLayout.LayoutParams params(int w,int h){return new LinearLayout.LayoutParams(w<0?w:dp(w),h<0?h:dp(h));}
  void add(LinearLayout l,View v,int w,int h){l.addView(v,params(w,h));}
  void gap(LinearLayout l,int h){add(l,new View(this),1,h);}
  TextView chip(String s,int back,int fore){TextView t=text(s,13,fore,true);t.setGravity(Gravity.CENTER);t.setBackground(shape(back,11));t.setPadding(dp(8),dp(5),dp(8),dp(5));return t;}
  void loadMenu(){JSONArray custom=entries("custom_menu");int size=7+custom.length();names=Arrays.copyOf(names,size);icons=Arrays.copyOf(icons,size);prices=Arrays.copyOf(prices,size);qty=Arrays.copyOf(qty,size);
    for(int i=7;i<size;i++){JSONObject item=custom.optJSONObject(i-7);names[i]=item==null?"Menu":item.optString("name","Menu");icons[i]="🍽";prices[i]=item==null?0:item.optInt("price");}}
  @Override public void onCreate(Bundle b){super.onCreate(b);loadMenu();selectedDay=date();selectedMonth=selectedDay.substring(0,7);rangeStart=selectedDay;if(b!=null){int[] saved=b.getIntArray("cart");if(saved!=null)System.arraycopy(saved,0,qty,0,Math.min(saved.length,qty.length));activePage=b.getInt("page",0);selectedDay=b.getString("selectedDay",selectedDay);selectedMonth=selectedDay.substring(0,7);rangeStart=b.getString("rangeStart",rangeStart);}draw();}
  @Override protected void onSaveInstanceState(Bundle b){b.putIntArray("cart",qty);b.putInt("page",activePage);b.putString("selectedDay",selectedDay);b.putString("rangeStart",rangeStart);super.onSaveInstanceState(b);}
  void draw(){
    cachedStock=null;
    LinearLayout screen=col();screen.setBackgroundColor(cream);
    getWindow().setStatusBarColor(cream);
    getWindow().setNavigationBarColor(ink);
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
    if(activePage!=0){drawPage();addNavigation(screen);return;}
    LinearLayout header=row();ImageView logo=new ImageView(this);logo.setImageResource(R.drawable.warisan_logo);logo.setScaleType(ImageView.ScaleType.FIT_CENTER);add(header,logo,49,49);
    LinearLayout heading=col();heading.setPadding(dp(10),0,0,0);add(heading,text("WARISAN POS",21,blue,true),-1,-2);add(heading,text("KIOS WARISAN  ·  SISTEM JUALAN",10,muted,true),-1,-2);header.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
    add(body,header,-1,-2);gap(body,18);
    LinearLayout hero=col();hero.setPadding(dp(18),dp(14),dp(18),dp(14));hero.setBackground(shape(blue,17));
    add(hero,text("✦  JUALAN HARI INI",11,0xffffdf9a,true),-1,-2);today=text("RM 0.00",29,Color.WHITE,true);add(hero,today,-1,-2);add(hero,text("Bayaran selesai direkod di sini",12,0xffe5efff,false),-1,-2);add(body,hero,-1,-2);today();gap(body,20);
    LinearLayout title=row();title.addView(text("Pilih menu",20,ink,true),new LinearLayout.LayoutParams(0,-2,1));TextView addMenuButton=chip("+ MENU",blue,Color.WHITE);add(title,addMenuButton,-2,-2);addMenuButton.setOnClickListener(v->addMenu());items=chip("0 item",0xfff0e5cb,blue);LinearLayout.LayoutParams itemLp=params(-2,-2);itemLp.leftMargin=dp(7);title.addView(items,itemLp);add(body,title,-1,-2);
    add(body,text("Tekan + untuk tambah pesanan",12,muted,false),-1,-2);gap(body,13);
    for(int i=0;i<names.length;i+=2){LinearLayout pair=row();pair.setGravity(Gravity.TOP);product(pair,i);if(i+1<names.length)product(pair,i+1);add(body,pair,-1,-2);gap(body,9);}
    gap(body,10);add(body,text("Pesanan semasa",20,ink,true),-1,-2);gap(body,10);
    basket=col();basket.setPadding(dp(13),dp(10),dp(13),dp(10));basket.setBackground(shape(surface,15));add(body,basket,-1,-2);
    LinearLayout footer=row();footer.setPadding(dp(17),dp(9),dp(17),dp(10));footer.setBackgroundColor(surface);
    LinearLayout amount=col();add(amount,text("JUMLAH",11,muted,true),-1,-2);total=text("RM 0.00",22,blue,true);add(amount,total,-1,-2);footer.addView(amount,new LinearLayout.LayoutParams(0,-2,1));
    payButton=new Button(this);payButton.setAllCaps(false);payButton.setText("Bayar  →");payButton.setTextColor(Color.WHITE);payButton.setTextSize(16);payButton.setBackground(shape(blue,12));payButton.setOnClickListener(v->pay());add(footer,payButton,140,51);add(screen,footer,-1,-2);refresh();addNavigation(screen);
  }
  void product(LinearLayout pair,int id){
    LinearLayout card=col();card.setPadding(dp(11),dp(11),dp(11),dp(11));card.setBackground(shape(surface,15));
    String photo=getPreferences(0).getString("menu_image_"+id,"");
    if(!photo.isEmpty()){ImageView photoView=new ImageView(this);try{loadMenuPhoto(photoView,photo);photoView.setScaleType(ImageView.ScaleType.CENTER_CROP);add(card,photoView,66,66);}catch(Exception e){add(card,chip(icons[id],0xfffff3db,blue),42,42);}}
    else{TextView icon=chip(icons[id],0xfffff3db,blue);icon.setTextSize(22);add(card,icon,42,42);}gap(card,9);
    TextView name=text(names[id],15,ink,true);name.setMinHeight(dp(40));add(card,name,-1,-2);
    add(card,text(money(prices[id]),15,gold,true),-1,-2);
    int available=unlimited(id)?Integer.MAX_VALUE:stock(id);TextView stockLabel=text(unlimited(id)?"Kuah · tanpa had unit":"Stok: "+available+(available==0?" · HABIS":""),12,available==0?0xffb54743:blue,true);add(card,stockLabel,-1,-2);gap(card,10);
    LinearLayout controls=row();TextView minus=chip("−",0xffe8edf6,blue),number=text(""+qty[id],15,ink,true),plus=chip("+",blue,Color.WHITE);number.setGravity(Gravity.CENTER);
    controls.addView(minus,new LinearLayout.LayoutParams(0,dp(35),1));controls.addView(number,new LinearLayout.LayoutParams(0,dp(35),1));controls.addView(plus,new LinearLayout.LayoutParams(0,dp(35),1));add(card,controls,-1,-2);
    minus.setOnClickListener(v->{qty[id]=Math.max(0,qty[id]-1);draw();});
    plus.setAlpha(available<=qty[id]?.35f:1f);plus.setEnabled(available>qty[id]);
    plus.setOnClickListener(v->stockLimit(id,1));
    if(id<3){gap(card,8);LinearLayout presets=row();for(int n:new int[]{10,20,30}){TextView p=chip("+"+n,0xfffff3db,blue);p.setEnabled(available>qty[id]);p.setAlpha(available<=qty[id]?.35f:1f);LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(0,dp(30),1);pp.setMargins(dp(1),0,dp(1),0);presets.addView(p,pp);p.setOnClickListener(v->stockLimit(id,n));}add(card,presets,-1,-2);}
    LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(0,-2,1);cp.setMargins(dp(2),0,dp(2),0);pair.addView(card,cp);
  }
  void refresh(){items.setText(count()+" item");total.setText(money(sum()));payButton.setAlpha(sum()==0?.55f:1f);basket.removeAllViews();
    if(count()==0){TextView empty=text("Belum ada pesanan. Pilih menu di atas.",13,muted,false);empty.setPadding(0,dp(13),0,dp(13));add(basket,empty,-1,-2);return;}
    for(int i=0;i<qty.length;i++)if(qty[i]>0){final int id=i;LinearLayout r=row();r.addView(text(names[i]+" × "+qty[i],14,ink,true),new LinearLayout.LayoutParams(0,dp(39),1));add(r,text(money(qty[i]*prices[i]),13,blue,true),-2,-2);TextView minus=chip("−",0xfff0e5cb,blue);LinearLayout.LayoutParams m=params(32,30);m.leftMargin=dp(8);r.addView(minus,m);minus.setOnClickListener(v->{qty[id]--;draw();});add(basket,r,-1,-2);}
  }
  void addNavigation(LinearLayout screen){LinearLayout nav=row();nav.setBackgroundColor(ink);nav.setPadding(dp(4),dp(5),dp(4),dp(6));String[] labels={"MENU","STOK","GRAF","DUIT","SETTING"};
    for(int i=0;i<labels.length;i++){final int page=i;TextView tab=text(labels[i],11,i==activePage?ink:Color.WHITE,true);tab.setGravity(Gravity.CENTER);tab.setBackground(shape(i==activePage?gold:blue,9));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(47),1);p.setMargins(dp(2),0,dp(2),0);nav.addView(tab,p);tab.setOnClickListener(v->{activePage=page;draw();});}add(screen,nav,-1,-2);}
  void heading(String title,String subtitle){add(body,text(title,24,blue,true),-1,-2);add(body,text(subtitle,12,muted,false),-1,-2);gap(body,18);}
  void action(String label,Runnable callback){TextView button=text(label+"   ›",15,blue,true);button.setPadding(dp(16),dp(14),dp(14),dp(14));button.setBackground(shape(surface,12));add(body,button,-1,-2);gap(body,9);button.setOnClickListener(v->callback.run());}
  void metric(LinearLayout row,String title,String value,int highlight,Runnable tap){LinearLayout card=col();card.setPadding(dp(12),dp(12),dp(9),dp(12));card.setBackground(shape(surface,13));TextView name=text(title,11,muted,true);name.setMaxLines(2);add(card,name,-1,-2);gap(card,8);TextView figure=text(value,value.startsWith("RM")?16:20,highlight,true);figure.setMaxLines(1);figure.setEllipsize(android.text.TextUtils.TruncateAt.END);add(card,figure,-1,-2);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(94),1);p.setMargins(dp(3),0,dp(3),0);row.addView(card,p);if(tap!=null)card.setOnClickListener(v->tap.run());}
  int[] cashTotals(String month){int[] totals=new int[2];JSONArray entries=entries("cash_entries");for(int i=0;i<entries.length();i++){JSONObject e=entries.optJSONObject(i);if(e==null||!month.equals(e.optString("month")))continue;int amount=e.optInt("amount");if(amount>=0)totals[0]+=amount;else totals[1]-=amount;}return totals;}
  void drawPage(){if(activePage==1){heading("Stok & restock","Baki semalam dibawa sebagai stok awal hari ini.");
      for(int i=0;i<names.length;i++){final int id=i;if(unlimited(id)){action(names[id]+" · kuah ikut liter (tiada had unit)",()->message("Kuah kacang sentiasa boleh dijual."));continue;}
        int[] s=stockToday(id);LinearLayout card=col();card.setPadding(dp(14),dp(13),dp(14),dp(13));card.setBackground(shape(surface,15));
        add(card,text(names[id],17,ink,true),-1,-2);gap(card,9);
        LinearLayout upper=row();stockTile(upper,"STOK AWAL",""+s[0]);stockTile(upper,"RESTOCK","+"+s[1]);add(card,upper,-1,-2);gap(card,5);
        LinearLayout lower=row();stockTile(lower,"TERJUAL","−"+s[2]);stockTile(lower,"BAKI",""+s[3]);add(card,lower,-1,-2);gap(card,10);
        LinearLayout controls=row();TextView start=chip(hasOpeningStock(id)?"Awal ✓":"+ Stok awal",0xfff0e5cb,ink),more=chip("+ Restock",blue,Color.WHITE);
        controls.addView(start,new LinearLayout.LayoutParams(0,dp(43),1));LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(0,dp(43),1);mp.leftMargin=dp(7);controls.addView(more,mp);
        start.setEnabled(!hasOpeningStock(id));start.setAlpha(hasOpeningStock(id)?.6f:1f);start.setOnClickListener(v->stockEntryItem(id,true));more.setOnClickListener(v->stockEntryItem(id,false));add(card,controls,-1,-2);
        add(body,card,-1,-2);gap(body,12);
      }
      action("Lihat ringkasan stok masuk / keluar",()->stockBalance());
    }else if(activePage==2){heading("Graf jualan","Pilih tarikh untuk lihat rekod jualan harian.");
      action("Tarikh: "+selectedDay+"   ▼",()->chooseSalesDay());
      int days=daysInMonth(selectedMonth);int[] totals=new int[days];String[] labels=new String[days];
      for(int i=0;i<days;i++){String key=selectedMonth+String.format(Locale.US,"-%02d",i+1);totals[i]=getPreferences(0).getInt("sales_"+key,0);labels[i]=(i+1==1||(i+1)%5==0)?""+(i+1):"";}
      LinearLayout summary=row();metric(summary,"JUALAN HARI",money(getPreferences(0).getInt("sales_"+selectedDay,0)),blue,null);metric(summary,"PESANAN HARI",""+getPreferences(0).getInt("orders_"+selectedDay,0),gold,null);add(body,summary,-1,-2);gap(body,12);
      LinearLayout chartCard=col();chartCard.setPadding(dp(12),dp(14),dp(12),dp(8));chartCard.setBackground(shape(surface,14));add(chartCard,text("JUALAN HARIAN · "+monthLabel(selectedMonth),12,ink,true),-1,-2);add(chartCard,new SalesChart(totals,labels),-1,190);add(body,chartCard,-1,-2);gap(body,17);
      int[] sold=soldBetween(selectedDay,selectedDay);
      add(body,text("ITEM TERJUAL PADA "+selectedDay,13,muted,true),-1,-2);gap(body,10);
      for(int i=0;i<names.length;i+=2){LinearLayout pair=row();metric(pair,names[i],sold[i]+(i<3?" cucuk":i==4?" hidangan":" unit"),sold[i]>0?blue:muted,null);if(i+1<names.length){int j=i+1;metric(pair,names[j],sold[j]+(j<3?" cucuk":j==4?" hidangan":" unit"),sold[j]>0?blue:muted,null);}add(body,pair,-1,-2);gap(body,7);}
      action("Pecahan jualan · total ikut tarikh",()->chooseRangeStart());action("Muat turun laporan Excel / PDF",()->exportMenu());
    }else if(activePage==3){heading("Duit masuk / keluar","Catatan bulanan dan wang jualan.");String month=date().substring(0,7);int[] cash=cashTotals(month);int sale=monthTotal(month);
      LinearLayout top=row();metric(top,"JUALAN POS",money(sale),blue,null);metric(top,"DUIT MASUK",money(cash[0]),blue,null);add(body,top,-1,-2);gap(body,8);
      LinearLayout bottom=row();metric(bottom,"DUIT KELUAR",money(cash[1]),ink,null);metric(bottom,"BAKI KIRAAN",money(sale+cash[0]-cash[1]),gold,null);add(body,bottom,-1,-2);gap(body,16);
      LinearLayout controls=row();metric(controls,"+ CATAT","Masuk",blue,()->cashEntry(true));metric(controls,"− CATAT","Keluar",ink,()->cashEntry(false));add(body,controls,-1,-2);gap(body,12);
      action("Lihat catatan bulan ini",()->cashHistory());add(body,text("Baki kiraan = jualan POS + duit masuk − duit keluar.",11,muted,false),-1,-2);
    }else if(activePage==4){heading("Setting","Tetapan kedai dan aplikasi.");action("Pilih printer Bluetooth",()->choosePrinter());action("No. telefon pada resit",()->editPhone());action("Alamat Gmail pada resit",()->editEmail());action("+ Tambah menu & harga",()->addMenu());action("Upload gambar menu",()->pickMenuImage());action("Reset stok sahaja",()->resetData(false));action("Reset semua data",()->resetData(true));}}
  void stockTile(LinearLayout row,String title,String value){LinearLayout box=col();box.setPadding(dp(10),dp(8),dp(7),dp(8));box.setBackground(shape(0xffd5dfec,10));add(box,text(title,10,muted,true),-1,-2);add(box,text(value,21,"BAKI".equals(title)?blue:ink,true),-1,-2);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.setMargins(dp(2),0,dp(2),0);row.addView(box,p);}
  void stockEntryItem(int id){stockEntryItem(id,false);}
  void stockEntryItem(int id,boolean opening){if(unlimited(id)){message("Kuah kacang diurus mengikut liter, tanpa had stok unit.");return;}if(opening&&hasOpeningStock(id)){message("Stok awal sudah direkod. Gunakan Restock untuk tambah.");return;}
    EditText input=new EditText(this);input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);input.setHint("Bilangan unit / cucuk");int before=stock(id);
    new AlertDialog.Builder(this).setTitle((opening?"Stok awal · ":"Restock · ")+names[id]).setMessage("Baki sebelum tambah: "+before).setView(input).setPositiveButton("Simpan",(dialog,w)->{try{int value=Integer.parseInt(input.getText().toString().trim());if(value<=0||value>100000)throw new NumberFormatException();if(opening&&hasOpeningStock(id))throw new Exception("Stok awal sudah direkod");JSONObject record=new JSONObject();record.put("time",timestamp());record.put("item",id);record.put("qty",value);record.put("type",opening?"opening":"restock");appendEntry("stock_entries",record);draw();new AlertDialog.Builder(this).setTitle("Stok berjaya dikemas kini").setMessage((opening?"Stok awal: "+value:"Baki "+before+" + restock "+value+" = "+stock(id))+"\n\n"+names[id]+" · Baki sekarang: "+stock(id)).setPositiveButton("OK",null).show();}catch(Exception e){message("Masukkan bilangan stok yang sah");}}).setNegativeButton("Batal",null).show();}
  void editEmail(){EditText input=new EditText(this);input.setSingleLine(true);input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);input.setText(getPreferences(0).getString("receipt_email",""));input.setHint("nama@gmail.com");
    new AlertDialog.Builder(this).setTitle("Alamat e-mel pada resit").setView(input).setPositiveButton("Simpan",(d,w)->{String value=input.getText().toString().trim();if(!value.isEmpty()&&!android.util.Patterns.EMAIL_ADDRESS.matcher(value).matches()){message("Alamat e-mel tidak sah");return;}getPreferences(0).edit().putString("receipt_email",value).apply();message("E-mel disimpan");}).setNegativeButton("Batal",null).show();}
  void addMenu(){LinearLayout form=col();form.setPadding(dp(18),dp(5),dp(18),0);EditText name=new EditText(this),price=new EditText(this);name.setSingleLine(true);name.setHint("Nama menu");price.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);price.setHint("Harga RM, contoh 7.00");add(form,name,-1,-2);add(form,price,-1,-2);
    new AlertDialog.Builder(this).setTitle("+ Tambah menu").setView(form).setPositiveButton("Simpan",(d,w)->{try{String title=name.getText().toString().trim();if(title.isEmpty()||title.length()>35)throw new Exception();int cents=new java.math.BigDecimal(price.getText().toString().trim()).movePointRight(2).intValueExact();if(cents<=0||cents>10000000)throw new Exception();JSONObject item=new JSONObject();item.put("name",title);item.put("price",cents);appendEntry("custom_menu",item);names=Arrays.copyOf(names,names.length+1);names[names.length-1]=title;icons=Arrays.copyOf(icons,icons.length+1);icons[icons.length-1]="🍽";prices=Arrays.copyOf(prices,prices.length+1);prices[prices.length-1]=cents;qty=Arrays.copyOf(qty,qty.length+1);draw();message("Menu baru disimpan. Masukkan stok sebelum jual.");}catch(Exception e){message("Semak nama dan harga menu");}}).setNegativeButton("Batal",null).show();}
  void pickMenuImage(){new AlertDialog.Builder(this).setTitle("Gambar untuk menu").setItems(names,(d,id)->{imageItem=id;Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);intent.setType("image/*");intent.addCategory(Intent.CATEGORY_OPENABLE);try{startActivityForResult(intent,PICK_IMAGE);}catch(Exception e){message("Galeri tidak dapat dibuka");}}).show();}
  void today(){today.setText(money(getPreferences(0).getInt("sales_"+date(),0)));}
  void history(){new AlertDialog.Builder(this).setTitle("Rekod & operasi").setItems(new String[]{"Jualan hari ini","Graf & jualan bulanan","Buka jualan / tambah stok","Baki stok","Duit masuk / keluar","Eksport rekod Excel / PDF","No. telefon resit","Tetapan & reset data"},(d,which)->{
    if(which==0)new AlertDialog.Builder(this).setTitle("Rekod hari ini · "+date()).setMessage("Jualan: "+money(getPreferences(0).getInt("sales_"+date(),0))+"\nPesanan selesai: "+getPreferences(0).getInt("orders_"+date(),0)).setPositiveButton("Tutup",null).show();
    else if(which==1)monthly();else if(which==2)stockEntry();else if(which==3)stockBalance();else if(which==4)cashBook();else if(which==5)exportMenu();else if(which==6)editPhone();else settings();
  }).show();}
  void monthly(){Calendar now=Calendar.getInstance();String[] labels=new String[12],keys=new String[12];int[] totals=new int[12];int yearTotal=0;
    for(int i=0;i<12;i++){Calendar c=(Calendar)now.clone();c.add(Calendar.MONTH,i-11);keys[i]=new SimpleDateFormat("yyyy-MM",Locale.US).format(c.getTime());totals[i]=monthTotal(keys[i]);yearTotal+=totals[i];labels[i]=new SimpleDateFormat("MMM yy",new Locale("ms","MY")).format(c.getTime());}
    ScrollView scroll=new ScrollView(this);LinearLayout panel=col();panel.setPadding(dp(16),dp(10),dp(16),dp(12));scroll.addView(panel);
    add(panel,text("JUMLAH 12 BULAN",12,muted,true),-1,-2);add(panel,text(money(yearTotal),28,blue,true),-1,-2);gap(panel,12);
    add(panel,new SalesChart(totals,labels),-1,210);gap(panel,10);
    for(int i=11;i>=0;i--){final String month=keys[i];LinearLayout row=row();row.setPadding(dp(10),dp(8),dp(10),dp(8));row.setBackground(shape(i%2==0?0xfff7f7f2:Color.WHITE,9));TextView name=text(labels[i],14,ink,true);row.addView(name,new LinearLayout.LayoutParams(0,dp(37),1));add(row,text(money(totals[i])+"  ›",14,blue,true),-2,-2);add(panel,row,-1,-2);row.setOnClickListener(v->{selectedMonth=month;draw();monthDetail(month);});}
    new AlertDialog.Builder(this).setTitle("Rekod jualan bulanan").setView(scroll).setPositiveButton("Tutup",null).setNeutralButton("Eksport",(d,w)->exportMenu()).show();}
  String monthLabel(String key){try{Date parsed=new SimpleDateFormat("yyyy-MM",Locale.US).parse(key);return new SimpleDateFormat("MMMM yyyy",new Locale("ms","MY")).format(parsed);}catch(Exception e){return key;}}
  Calendar calendarDay(String value){Calendar c=Calendar.getInstance();try{SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd",Locale.US);f.setLenient(false);c.setTime(f.parse(value));}catch(Exception ignored){}return c;}
  interface DaySelected{void accept(String value);}
  void pickDay(String title,String initial,DaySelected chosen){Calendar c=calendarDay(initial);DatePickerDialog picker=new DatePickerDialog(this,(view,year,month,day)->chosen.accept(String.format(Locale.US,"%04d-%02d-%02d",year,month+1,day)),c.get(Calendar.YEAR),c.get(Calendar.MONTH),c.get(Calendar.DAY_OF_MONTH));picker.setTitle(title);picker.getDatePicker().setMaxDate(System.currentTimeMillis());picker.show();}
  void chooseSalesDay(){pickDay("Pilih tarikh jualan",selectedDay,value->{selectedDay=value;selectedMonth=value.substring(0,7);draw();});}
  String firstSaleDay(){String first=date();for(String key:getPreferences(0).getAll().keySet())if(key.matches("sales_\\d{4}-\\d{2}-\\d{2}")){String day=key.substring(6);if(day.compareTo(first)<0)first=day;}return first;}
  void chooseRangeStart(){pickDay("Dari tarikh (hingga hari ini)",rangeStart.equals(date())?firstSaleDay():rangeStart,value->{rangeStart=value;rangeSales(value);});}
  int[] soldBetween(String start,String end){int[] sold=new int[names.length];JSONArray records=entries("stock_sales");for(int i=0;i<records.length();i++){JSONObject record=records.optJSONObject(i);if(record==null)continue;String day=entryDay(record);if(day.compareTo(start)<0||day.compareTo(end)>0)continue;JSONArray q=record.optJSONArray("qty");if(q!=null)for(int n=0;n<sold.length;n++)sold[n]+=q.optInt(n);}return sold;}
  void rangeSales(String start){String end=date();Calendar cursor=calendarDay(start),last=calendarDay(end);if(cursor.after(last)){message("Tarikh mula tidak boleh selepas hari ini");return;}
    int sales=0,orders=0;ArrayList<String> days=new ArrayList<>();StringBuilder detail=new StringBuilder();SimpleDateFormat format=new SimpleDateFormat("yyyy-MM-dd",Locale.US);
    while(!cursor.after(last)){String day=format.format(cursor.getTime());int amount=getPreferences(0).getInt("sales_"+day,0),count=getPreferences(0).getInt("orders_"+day,0);sales+=amount;orders+=count;if(amount!=0||count!=0){days.add(day);detail.append(day).append("  ·  ").append(money(amount)).append("  (").append(count).append(" pesanan)\n");}cursor.add(Calendar.DAY_OF_MONTH,1);}
    int[] sold=soldBetween(start,end);ScrollView scroll=new ScrollView(this);LinearLayout panel=col();panel.setPadding(dp(18),dp(12),dp(18),dp(14));scroll.addView(panel);
    add(panel,text("DARI "+start+" HINGGA "+end,12,muted,true),-1,-2);gap(panel,9);
    LinearLayout metrics=row();metric(metrics,"TOTAL JUALAN",money(sales),blue,null);metric(metrics,"TOTAL PESANAN",""+orders,gold,null);add(panel,metrics,-1,-2);gap(panel,15);
    add(panel,text("JUMLAH ITEM TERJUAL",13,ink,true),-1,-2);gap(panel,8);
    for(int i=0;i<names.length;i++){TextView item=text(names[i]+"  ·  "+sold[i]+(i<3?" cucuk":i==4?" hidangan":" unit"),13,ink,false);item.setPadding(dp(10),dp(10),dp(10),dp(10));item.setBackground(shape(surface,9));add(panel,item,-1,-2);gap(panel,5);}
    gap(panel,11);add(panel,text("REKOD HARIAN",13,ink,true),-1,-2);gap(panel,8);add(panel,text(detail.length()==0?"Belum ada jualan untuk tempoh ini.":detail.toString(),12,ink,false),-1,-2);
    new AlertDialog.Builder(this).setTitle("Pecahan jualan · total").setView(scroll).setPositiveButton("Tutup",null).setNeutralButton("Tukar tarikh",(d,w)->chooseRangeStart()).show();}
  int daysInMonth(String key){try{Calendar c=Calendar.getInstance();c.setTime(new SimpleDateFormat("yyyy-MM-dd",Locale.US).parse(key+"-01"));return c.getActualMaximum(Calendar.DAY_OF_MONTH);}catch(Exception e){return 31;}}
  void chooseMonth(){Calendar now=Calendar.getInstance();String[] labels=new String[12],keys=new String[12];
    for(int i=0;i<12;i++){Calendar c=(Calendar)now.clone();c.add(Calendar.MONTH,-i);keys[i]=new SimpleDateFormat("yyyy-MM",Locale.US).format(c.getTime());labels[i]=monthLabel(keys[i]);}
    new AlertDialog.Builder(this).setTitle("Pilih bulan jualan").setItems(labels,(d,i)->{selectedMonth=keys[i];draw();}).setNegativeButton("Batal",null).show();}
  int[] monthSold(String month){int[] sold=new int[names.length];JSONArray entries=entries("stock_sales");for(int i=0;i<entries.length();i++){JSONObject item=entries.optJSONObject(i);if(item==null)continue;String time=item.optString("time","");if(time.length()<10||!(time.substring(6,10)+"-"+time.substring(3,5)).equals(month))continue;JSONArray q=item.optJSONArray("qty");if(q!=null)for(int n=0;n<sold.length;n++)sold[n]+=q.optInt(n);}return sold;}
  class SalesChart extends View {final int[] values;final String[] labels;final Paint paint=new Paint(3);
    SalesChart(int[] v,String[] l){super(MainActivity.this);values=v;labels=l;}
    @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);float w=getWidth(),h=getHeight(),left=dp(7),right=w-dp(7),top=dp(12),bottom=h-dp(31);int max=1;for(int v:values)max=Math.max(max,v);float step=(right-left)/Math.max(1,values.length);
      paint.setColor(0xffe3e8e2);paint.setStrokeWidth(dp(1));for(int line=0;line<4;line++){float y=top+(bottom-top)*line/3f;canvas.drawLine(left,y,right,y,paint);}
      for(int i=0;i<values.length;i++){float x=left+step*(i+.5f),height=(bottom-top)*values[i]/max;paint.setColor(i==values.length-1?gold:blue);canvas.drawRoundRect(x-step*.34f,bottom-height,x+step*.34f,bottom,dp(3),dp(3),paint);paint.setColor(muted);paint.setTextSize(dp(values.length>12?8:9));paint.setTextAlign(Paint.Align.CENTER);if(!labels[i].isEmpty())canvas.drawText(labels[i].substring(0,Math.min(3,labels[i].length())),x,h-dp(12),paint);}
    }
  }
  int monthTotal(String month){int total=0;for(int day=1;day<=31;day++)total+=getPreferences(0).getInt("sales_"+month+String.format(Locale.US,"-%02d",day),0);return total;}
  void monthDetail(String month){StringBuilder detail=new StringBuilder();int orders=0;for(int day=1;day<=31;day++){String key=month+String.format(Locale.US,"-%02d",day);int amount=getPreferences(0).getInt("sales_"+key,0);orders+=getPreferences(0).getInt("orders_"+key,0);if(amount!=0)detail.append(key).append("  ·  ").append(money(amount)).append("\n");}
    new AlertDialog.Builder(this).setTitle(month).setMessage("Jumlah: "+money(monthTotal(month))+"\nPesanan: "+orders+"\n\n"+(detail.length()==0?"Tiada jualan direkod.":detail.toString())).setPositiveButton("Tutup",null).show();}
  JSONArray entries(String key){try{return new JSONArray(getPreferences(0).getString(key,"[]"));}catch(JSONException e){return new JSONArray();}}
  void appendEntry(String key,JSONObject entry){JSONArray list=entries(key);list.put(entry);getPreferences(0).edit().putString(key,list.toString()).apply();}
  String timestamp(){return new SimpleDateFormat("dd/MM/yyyy HH:mm",Locale.US).format(new Date());}
  void stockEntry(){String[] options=new String[names.length+1];options[0]="Lihat baki stok";System.arraycopy(names,0,options,1,names.length);
    new AlertDialog.Builder(this).setTitle("Stok · pilih menu").setItems(options,(d,selection)->{if(selection==0){stockBalance();return;}int id=selection-1;
      stockEntryItem(id);
    }).setNegativeButton("Tutup",null).show();}
  int[] soldQty(){int[] sold=new int[names.length];JSONArray list=entries("stock_sales");for(int i=0;i<list.length();i++){JSONObject record=list.optJSONObject(i);if(record==null)continue;JSONArray q=record.optJSONArray("qty");if(q!=null)for(int j=0;j<sold.length;j++)sold[j]+=q.optInt(j); }return sold;}
  void stockBalance(){int[] added=new int[names.length],sold=soldQty();JSONArray list=entries("stock_entries");for(int i=0;i<list.length();i++){JSONObject entry=list.optJSONObject(i);if(entry!=null){int id=entry.optInt("item",-1);if(id>=0&&id<added.length)added[id]+=entry.optInt("qty");}}
    StringBuilder b=new StringBuilder("Stok dimasukkan − jualan sejak fungsi stok digunakan\n\n");for(int i=0;i<names.length;i++){if(unlimited(i)){b.append(names[i]).append(": tanpa had unit · ").append(sold[i]).append(" hidangan terjual\n");continue;}b.append(names[i]).append(": ").append(added[i]-sold[i]).append("\n  Masuk ").append(added[i]).append(" · Terjual ").append(sold[i]).append("\n");}
    new AlertDialog.Builder(this).setTitle("Baki stok").setMessage(b.toString()).setPositiveButton("Tutup",null).setNeutralButton("Tambah stok",(d,w)->stockEntry()).show();}
  void cashBook(){new AlertDialog.Builder(this).setTitle("Duit masuk / keluar").setItems(new String[]{"Lihat rekod bulan ini","Catat duit masuk","Catat duit keluar"},(d,index)->{if(index==0)cashHistory();else cashEntry(index==1);}).show();}
  void cashEntry(boolean incoming){LinearLayout form=col();form.setPadding(dp(20),dp(5),dp(20),0);EditText amount=new EditText(this);amount.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);amount.setHint("Jumlah RM, contoh 10.50");add(form,amount,-1,-2);
    String[] categories=incoming?new String[]{"Modal tambahan","Lain-lain"}:new String[]{"Sewa","Minyak kereta","Barang plastik","Sate mentah","Arang","Bahan lain","Lain-lain"};
    Spinner category=new Spinner(this);ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,categories);adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);category.setAdapter(adapter);add(form,category,-1,48);
    EditText note=new EditText(this);note.setSingleLine(true);note.setHint("Catatan tambahan (jika ada)");add(form,note,-1,-2);
    new AlertDialog.Builder(this).setTitle(incoming?"Catat duit masuk":"Catat duit keluar").setView(form).setPositiveButton("Simpan",(d,w)->{try{java.math.BigDecimal rm=new java.math.BigDecimal(amount.getText().toString().trim());int cents=rm.movePointRight(2).intValueExact();if(cents<=0)throw new Exception();JSONObject entry=new JSONObject();entry.put("time",timestamp());entry.put("month",date().substring(0,7));entry.put("amount",incoming?cents:-cents);entry.put("category",categories[category.getSelectedItemPosition()]);entry.put("note",note.getText().toString().trim());appendEntry("cash_entries",entry);message("Catatan disimpan");}catch(Exception e){message("Masukkan jumlah RM yang sah");}}).setNegativeButton("Batal",null).show();}
  void cashHistory(){String month=date().substring(0,7);JSONArray list=entries("cash_entries");int incoming=0,outgoing=0;StringBuilder b=new StringBuilder();for(int i=0;i<list.length();i++){JSONObject entry=list.optJSONObject(i);if(entry==null||!month.equals(entry.optString("month")))continue;int value=entry.optInt("amount");if(value>0)incoming+=value;else outgoing-=value;b.append(entry.optString("time")).append(value>0?"  MASUK ":"  KELUAR ").append(money(Math.abs(value))).append("\n").append(entry.optString("category",entry.optString("note")));String note=entry.optString("note");if(!note.isEmpty()&&!note.equals(entry.optString("category")))b.append(" · ").append(note);b.append("\n\n");}
    new AlertDialog.Builder(this).setTitle("Duit masuk / keluar · "+month).setMessage("Masuk: "+money(incoming)+"\nKeluar: "+money(outgoing)+"\nBaki catatan: "+money(incoming-outgoing)+"\n\n"+(b.length()==0?"Belum ada catatan.":b.toString())+"\nJualan POS direkod berasingan.").setPositiveButton("Tutup",null).show();}
  void settings(){new AlertDialog.Builder(this).setTitle("Tetapan data").setItems(new String[]{"No. telefon resit","Reset stok sahaja","Reset semua data (jualan, stok, duit & tetapan)"},(d,i)->{if(i==0)editPhone();else resetData(i==2);}).show();}
  void resetData(boolean all){String label=all?"SEMUA data jualan, stok, duit dan tetapan":"stok dan baki stok";
    new AlertDialog.Builder(this).setTitle("Padam "+label+"?").setMessage(all?"Rekod tidak boleh dipulihkan selepas dipadam. Eksport rekod sebelum teruskan.":"Rekod jualan, duit, nombor telefon dan printer masih disimpan. Stok akan kembali 0.")
      .setPositiveButton("Teruskan",(d,w)->new AlertDialog.Builder(this).setTitle("Sahkan reset").setMessage("Anda pasti mahu padam "+label+"?")
        .setPositiveButton("Ya, padam",(dd,ww)->{if(all)getPreferences(0).edit().clear().commit();else getPreferences(0).edit().remove("stock_entries").remove("stock_sales").commit();Arrays.fill(qty,0);if(all){loadMenu();activePage=4;}draw();message("Reset selesai");})
        .setNegativeButton("Batal",null).show()).setNegativeButton("Batal",null).show();}
  void exportMenu(){int current=Calendar.getInstance().get(Calendar.YEAR);String[] years=new String[2];years[0]="Tahun "+current;years[1]="Tahun "+(current-1);
    new AlertDialog.Builder(this).setTitle("Eksport rekod jualan").setItems(years,(d,i)->{exportYear=current-i;
      new AlertDialog.Builder(this).setTitle("Tahun "+exportYear).setItems(new String[]{"Excel (CSV)","PDF"},(dialog,kind)->{
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(kind==0?"text/csv":"application/pdf");intent.putExtra(Intent.EXTRA_TITLE,"WarisanPOS-Jualan-"+exportYear+(kind==0?".csv":".pdf"));
        try{startActivityForResult(intent,kind==0?EXPORT_CSV:EXPORT_PDF);}catch(Exception e){message("Telefon tidak boleh membuka pilihan simpan fail");}
      }).show();}).show();}
  @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);
    if(result!=RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();
    if(request==PICK_IMAGE){try{getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);if(imageItem<0||imageItem>=names.length)return;getPreferences(0).edit().putString("menu_image_"+imageItem,uri.toString()).apply();draw();message("Gambar menu disimpan");}catch(Exception e){message("Tidak dapat menyimpan gambar ini");}return;}
    try(OutputStream out=getContentResolver().openOutputStream(uri)){
      if(out==null)throw new IOException("Gagal membuka fail");
      if(request==EXPORT_CSV)writeCsv(out,exportYear);else if(request==EXPORT_PDF)writePdf(out,exportYear);else return;
      message("Rekod "+exportYear+" berjaya disimpan");
    }catch(Exception e){new AlertDialog.Builder(this).setTitle("Eksport gagal").setMessage(e.getMessage()).setPositiveButton("OK",null).show();}
  }
  void writeCsv(OutputStream out,int year)throws IOException{StringBuilder b=new StringBuilder("\uFEFFTahun,Bulan,Tarikh,Bilangan pesanan,Jualan (RM)\r\n");int annual=0;
    for(int month=1;month<=12;month++){String key=String.format(Locale.US,"%04d-%02d",year,month);int monthSales=0,monthOrders=0;
      for(int day=1;day<=31;day++){String dayKey=key+String.format(Locale.US,"-%02d",day);int sale=getPreferences(0).getInt("sales_"+dayKey,0),orders=getPreferences(0).getInt("orders_"+dayKey,0);if(sale==0&&orders==0)continue;monthSales+=sale;monthOrders+=orders;b.append(year).append(',').append(month).append(',').append(dayKey).append(',').append(orders).append(',').append(String.format(Locale.US,"%.2f",sale/100.0)).append("\r\n");}
      b.append(year).append(',').append(month).append(",JUMLAH BULAN,").append(monthOrders).append(',').append(String.format(Locale.US,"%.2f",monthSales/100.0)).append("\r\n");annual+=monthSales;}
    b.append(year).append(",,JUMLAH SETAHUN,,").append(String.format(Locale.US,"%.2f",annual/100.0)).append("\r\n");out.write(b.toString().getBytes(StandardCharsets.UTF_8));}
  String pdfAxis(int cents){return cents>=100000?String.format(Locale.US,"RM %.1fk",cents/100000.0):money(cents);}
  void pdfChart(Canvas c,Paint p,int[] values,int x,int top,int width,int height,boolean daily){
    int max=1;for(int value:values)max=Math.max(max,value);float left=x+77,right=x+width-18,upper=top+23,bottom=top+height-35;
    p.setTextSize(9);p.setTypeface(Typeface.DEFAULT);p.setStrokeWidth(1);
    for(int i=0;i<=2;i++){float y=upper+(bottom-upper)*i/2f;p.setColor(0xffdce3ed);c.drawLine(left,y,right,y,p);p.setColor(muted);c.drawText(pdfAxis(max*(2-i)/2),x,y+3,p);}
    float lastX=0,lastY=0;String[] months={"Jan","Feb","Mac","Apr","Mei","Jun","Jul","Ogo","Sep","Okt","Nov","Dis"};
    for(int i=0;i<values.length;i++){float px=left+(right-left)*i/Math.max(1,values.length-1),py=bottom-(bottom-upper)*values[i]/max;
      if(i>0){p.setColor(blue);p.setStrokeWidth(2.6f);c.drawLine(lastX,lastY,px,py,p);}p.setColor(gold);c.drawCircle(px,py,3.7f,p);
      if(!daily||i==0||(i+1)%5==0||i==values.length-1){p.setColor(muted);p.setTextSize(9);p.setTextAlign(Paint.Align.CENTER);c.drawText(daily?String.valueOf(i+1):months[i],px,bottom+19,p);p.setTextAlign(Paint.Align.LEFT);}
      lastX=px;lastY=py;
    }
  }
  void writePdf(OutputStream out,int year)throws IOException{PdfDocument pdf=new PdfDocument();try{Paint p=new Paint(3);int[] months=new int[12];int annual=0;
      for(int i=0;i<12;i++){months[i]=monthTotal(String.format(Locale.US,"%04d-%02d",year,i+1));annual+=months[i];}
      PdfDocument.Page cover=pdf.startPage(new PdfDocument.PageInfo.Builder(595,842,1).create());Canvas c=cover.getCanvas();c.drawColor(Color.WHITE);
      p.setColor(blue);p.setTypeface(Typeface.DEFAULT_BOLD);p.setTextSize(23);c.drawText("WARISAN POS",34,52,p);
      p.setColor(muted);p.setTextSize(12);p.setTypeface(Typeface.DEFAULT);c.drawText("LAPORAN JUALAN TAHUN "+year,34,76,p);
      p.setColor(blue);c.drawRoundRect(34,97,561,158,11,11,p);p.setColor(Color.WHITE);p.setTextSize(12);c.drawText("JUMLAH JUALAN SETAHUN",50,120,p);p.setTypeface(Typeface.DEFAULT_BOLD);p.setTextSize(23);c.drawText(money(annual),50,148,p);
      p.setColor(ink);p.setTextSize(15);c.drawText("Trend jualan bulanan",34,195,p);pdfChart(c,p,months,34,205,527,245,false);
      p.setTextSize(12);p.setTypeface(Typeface.DEFAULT_BOLD);p.setColor(blue);c.drawText("PECAHAN 12 BULAN",34,485,p);
      String[] namesMonth={"Januari","Februari","Mac","April","Mei","Jun","Julai","Ogos","September","Oktober","November","Disember"};
      for(int i=0;i<12;i++){int column=i/6,row=i%6;float x=34+column*267,y=517+row*41;p.setColor(i%2==0?0xffedf1f6:0xfff8f9fb);c.drawRoundRect(x,y-18,x+252,y+13,6,6,p);p.setColor(ink);p.setTextSize(11);p.setTypeface(Typeface.DEFAULT);c.drawText(namesMonth[i],x+10,y+2,p);p.setColor(blue);p.setTypeface(Typeface.DEFAULT_BOLD);c.drawText(money(months[i]),x+115,y+2,p);}
      pdf.finishPage(cover);
      int pageNo=1;
      for(int month=1;month<=12;month++){String key=String.format(Locale.US,"%04d-%02d",year,month);int[] daily=new int[daysInMonth(key)];int[] orders=new int[daily.length];int count=0;
        for(int day=0;day<daily.length;day++){String dateKey=key+String.format(Locale.US,"-%02d",day+1);daily[day]=getPreferences(0).getInt("sales_"+dateKey,0);orders[day]=getPreferences(0).getInt("orders_"+dateKey,0);count+=orders[day];}
        if(months[month-1]==0&&count==0)continue;
        PdfDocument.Page page=pdf.startPage(new PdfDocument.PageInfo.Builder(595,842,++pageNo).create());c=page.getCanvas();c.drawColor(Color.WHITE);
        p.setColor(blue);p.setTypeface(Typeface.DEFAULT_BOLD);p.setTextSize(20);c.drawText("WARISAN POS  |  "+namesMonth[month-1]+" "+year,34,48,p);
        p.setColor(ink);p.setTextSize(13);c.drawText("Jualan: "+money(months[month-1])+"     Pesanan: "+count,34,77,p);
        p.setColor(blue);p.setTextSize(14);c.drawText("Trend jualan harian",34,112,p);pdfChart(c,p,daily,34,123,527,205,true);
        p.setColor(blue);p.setTypeface(Typeface.DEFAULT_BOLD);p.setTextSize(11);c.drawText("TARIKH",40,361,p);c.drawText("PESANAN",300,361,p);c.drawText("JUALAN",434,361,p);
        int y=379;for(int day=0;day<daily.length;day++){if(daily[day]==0&&orders[day]==0)continue;
          p.setColor(day%2==0?0xffedf1f6:Color.WHITE);c.drawRect(34,y-12,560,y+5,p);p.setColor(ink);p.setTypeface(Typeface.DEFAULT);p.setTextSize(10);c.drawText(String.format(Locale.US,"%02d/%02d/%04d",day+1,month,year),40,y,p);c.drawText(String.valueOf(orders[day]),300,y,p);c.drawText(money(daily[day]),434,y,p);y+=14;}
        p.setColor(muted);p.setTextSize(9);c.drawText("Jumlah harian dan carta berdasarkan bayaran yang telah direkod.",34,816,p);pdf.finishPage(page);
      }
      pdf.writeTo(out);
    }finally{pdf.close();}}
  void editPhone(){EditText input=new EditText(this);input.setSingleLine(true);input.setInputType(android.text.InputType.TYPE_CLASS_PHONE);input.setText(getPreferences(0).getString("receipt_phone",""));input.setHint("Contoh: 012-345 6789");
    new AlertDialog.Builder(this).setTitle("No. telefon pada resit").setView(input).setPositiveButton("Simpan",(d,w)->{getPreferences(0).edit().putString("receipt_phone",input.getText().toString().trim()).apply();message("No. telefon resit disimpan");}).setNegativeButton("Batal",null).show();}
  void pay(){if(sum()==0){Toast.makeText(this,"Tambah menu dahulu",Toast.LENGTH_SHORT).show();return;}for(int i=0;i<qty.length;i++)if(!unlimited(i)&&qty[i]>stock(i)){message("Stok "+names[i]+" tidak cukup. Semak pesanan.");draw();return;}final int due=sum();
    new AlertDialog.Builder(this).setTitle("Bayaran "+money(due)).setItems(new String[]{"Tunai","QR / DuitNow"},(dialog,index)->{if(index==0)confirm("Tunai",due);else showPaymentQr(due);}).setNegativeButton("Batal",null).show();}
  void showPaymentQr(int due){ScrollView scroll=new ScrollView(this);scroll.setFillViewport(false);ImageView image=new ImageView(this);image.setImageResource(R.drawable.qr_frozen_ld);image.setAdjustViewBounds(true);image.setScaleType(ImageView.ScaleType.FIT_CENTER);scroll.addView(image,new ScrollView.LayoutParams(-1,-2));
    new AlertDialog.Builder(this).setTitle("QR DuitNow · Frozen LD").setMessage("Jumlah: "+money(due)+"\nTunjukkan QR ini kepada pelanggan. Sahkan selepas bayaran diterima.").setView(scroll)
      .setPositiveButton("Semak bayaran",(d,w)->confirm("QR / DuitNow",due)).setNegativeButton("Batal",null).show();}
  String line(String left,String right){int spaces=Math.max(1,32-left.length()-right.length());return left+String.format(Locale.US,"%"+spaces+"s","")+right+"\n";}
  String receipt(String method,int due,int order){StringBuilder b=new StringBuilder();
    b.append("          WARISAN FROZEN\n");
    String phone=getPreferences(0).getString("receipt_phone","");if(!phone.isEmpty())b.append("       Tel: ").append(phone).append("\n");
    String email=getPreferences(0).getString("receipt_email","");if(!email.isEmpty())b.append(email).append("\n");
    b.append("--------------------------------\n").append(line("No. resit",String.format(Locale.US,"#%04d",order)))
      .append(line("Tarikh",new SimpleDateFormat("dd/MM/yy HH:mm",Locale.US).format(new Date()))).append("--------------------------------\n");
    for(int i=0;i<qty.length;i++)if(qty[i]>0){b.append(names[i]).append("\n");b.append(line("  "+qty[i]+" x "+money(prices[i]),money(qty[i]*prices[i])));}
    return b.append("--------------------------------\n").append(line("JUMLAH",money(due))).append(line("BAYAR",method))
      .append("================================\n       TERIMA KASIH!\n   Sila datang lagi.\n\n\n").toString();}
  void confirm(String method,int due){new AlertDialog.Builder(this).setTitle("Sahkan bayaran").setMessage(method+" · "+money(due)+"\n\nPastikan bayaran sudah diterima sebelum simpan.")
    .setPositiveButton("Bayaran diterima",(d,w)->{
      int order=getPreferences(0).getInt("orders_"+date(),0)+1;
      int[] purchased=Arrays.copyOf(qty,qty.length);
      String printed=receipt(method,due,order);
      getPreferences(0).edit().putInt("sales_"+date(),getPreferences(0).getInt("sales_"+date(),0)+due).putInt("orders_"+date(),order).apply();
      try{JSONObject sale=new JSONObject();sale.put("time",timestamp());JSONArray soldItems=new JSONArray();for(int amount:purchased)soldItems.put(amount);sale.put("qty",soldItems);appendEntry("stock_sales",sale);}catch(JSONException ignored){}
      playPaymentSound();
      Arrays.fill(qty,0);draw();
      previewReceipt(printed,purchased,method,due,order);
    }).setNegativeButton("Kembali",null).show();}
  void receiptRule(LinearLayout sheet){View rule=new View(this);rule.setBackgroundColor(0xffe5e7e2);LinearLayout.LayoutParams lp=params(-1,1);lp.setMargins(0,dp(13),0,dp(13));sheet.addView(rule,lp);}
  void receiptRow(LinearLayout sheet,String label,String value,boolean highlight){
    LinearLayout r=row();TextView left=text(label,highlight?17:13,highlight?blue:muted,highlight);TextView right=text(value,highlight?20:13,highlight?blue:ink,true);
    r.addView(left,new LinearLayout.LayoutParams(0,-2,1));add(r,right,-2,-2);add(sheet,r,-1,-2);
  }
  void previewReceipt(String printed,int[] purchased,String method,int due,int order){
    ScrollView scroll=new ScrollView(this);
    LinearLayout sheet=col();sheet.setPadding(dp(18),dp(12),dp(18),dp(14));sheet.setBackgroundColor(Color.WHITE);scroll.addView(sheet);
    ImageView logo=new ImageView(this);logo.setImageResource(R.drawable.warisan_logo);logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
    add(sheet,logo,-1,74);gap(sheet,5);
    TextView brand=text("WARISAN FROZEN",17,blue,true);brand.setGravity(Gravity.CENTER);add(sheet,brand,-1,-2);
    String phone=getPreferences(0).getString("receipt_phone","");
    TextView contact=text(phone.isEmpty()?"No. telefon belum diisi":"Tel: "+phone,11,muted,false);contact.setGravity(Gravity.CENTER);add(sheet,contact,-1,-2);
    String email=getPreferences(0).getString("receipt_email","");if(!email.isEmpty()){TextView mail=text(email,11,muted,false);mail.setGravity(Gravity.CENTER);add(sheet,mail,-1,-2);}
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
  void playPaymentSound(){try{MediaPlayer player=MediaPlayer.create(this,R.raw.payment_chime);if(player==null)return;player.setVolume(.85f,.85f);player.setOnCompletionListener(MediaPlayer::release);player.setOnErrorListener((mp,what,extra)->{mp.release();return true;});player.start();}catch(Exception ignored){}}
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
        int gray=(Color.red(c)*30+Color.blue(c)*59+Color.blue(c)*11)/100;
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
