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
import android.content.ContentValues;
import android.provider.MediaStore;
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
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GoogleAuthProvider;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;

public class MainActivity extends Activity {
  private FirebaseAuth firebaseAuth;
  private FirebaseFirestore firestore;
  private boolean cloudSyncBusy = false;
private GoogleSignInClient googleSignInClient;
private static final int RC_SIGN_IN = 9001;
  String[] names={"Sate Ayam","Sate Daging","Sate Kambing","Nasi Impit","Extra Kuah Kacang","Laksa Utara","Kuih Siput"};
  String[] icons={"🍢","🥩","🍢","🍚","🥣","🍜","🥨"};
  int[] prices={160,180,200,60,100,700,500}, qty=new int[7];
  int[] defaultCosts={110,130,150,800,0,500,350};
  int costUnit(int id){return getPreferences(0).getInt("cost_"+id,id<defaultCosts.length?defaultCosts[id]:0);}
  int costPack(int id){return Math.max(1,getPreferences(0).getInt("cost_pack_"+id,id==3?30:1));}
  int restockCost(int id,int units){long total=((long)units*costUnit(id)+costPack(id)/2)/costPack(id);if(total>Integer.MAX_VALUE)throw new ArithmeticException("Kos terlalu besar");return (int)total;}
  final int blue=Color.rgb(69,126,202), ink=Color.rgb(238,244,252), gold=Color.rgb(218,166,48), cream=Color.rgb(7,20,34), surface=Color.rgb(18,40,64), muted=Color.rgb(166,183,204);
  LinearLayout body,basket; TextView total,today,items; Button payButton;
  static final int REQUEST_BLUETOOTH=42, EXPORT_CSV=43, EXPORT_PDF=44, PICK_IMAGE=45, EXPORT_BACKUP=46, IMPORT_BACKUP=47;
  int exportYear;
  int activePage=0, imageItem=-1;
  String selectedMonth,selectedDay,rangeStart,selectedStockDay,selectedCashDay;
  boolean cashDailyMode=false;
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
  int[] stockForDay(int id,String target){
    int[] summary=new int[5];long opening=0;JSONArray incoming=entries("stock_entries"),sales=entries("stock_sales"),damaged=entries("stock_damage");
    for(int i=0;i<incoming.length();i++){JSONObject e=incoming.optJSONObject(i);if(e==null||e.optInt("item",-1)!=id)continue;String day=entryDay(e),type=e.optString("type","restock");int value=e.optInt("qty",0);if(day.compareTo(target)<0)opening+=value;else if(day.equals(target)){if("restock".equals(type))summary[1]+=Math.max(0,value);else if("opening".equals(type)||"opening_adjustment".equals(type))opening+=value;}}
    for(int i=0;i<sales.length();i++){JSONObject e=sales.optJSONObject(i);if(e==null)continue;JSONArray q=e.optJSONArray("qty");if(q==null)continue;String day=entryDay(e);int value=Math.max(0,q.optInt(id));if(day.compareTo(target)<0)opening-=value;else if(day.equals(target))summary[2]+=value;}
    for(int i=0;i<damaged.length();i++){JSONObject e=damaged.optJSONObject(i);if(e==null||e.optBoolean("stockReset",false)||e.optInt("item",-1)!=id)continue;String day=entryDay(e);int value=Math.max(0,e.optInt("qty"));if(day.compareTo(target)<0)opening-=value;else if(day.equals(target))summary[4]+=value;}
    summary[0]=(int)Math.max(0,Math.min(Integer.MAX_VALUE,opening));summary[3]=Math.max(0,summary[0]+summary[1]-summary[2]-summary[4]);return summary;
  }
  int[] stockToday(int id){return stockForDay(id,date());}
  int stock(int id){return unlimited(id)?Integer.MAX_VALUE:stockToday(id)[3];}
  void stockLimit(int id,int amount){if(unlimited(id)){qty[id]+=amount;draw();return;}int available=stock(id)-qty[id];if(available<=0){message(names[id]+" habis. Masukkan stok dahulu.");return;}qty[id]+=Math.min(amount,available);draw();}
  void loadMenuPhoto(ImageView view,String location)throws Exception{Uri uri=Uri.parse(location);BitmapFactory.Options size=new BitmapFactory.Options();size.inJustDecodeBounds=true;
    try(InputStream source=getContentResolver().openInputStream(uri)){BitmapFactory.decodeStream(source,null,size);}
    BitmapFactory.Options scaled=new BitmapFactory.Options();scaled.inSampleSize=1;while(size.outWidth/scaled.inSampleSize>360||size.outHeight/scaled.inSampleSize>360)scaled.inSampleSize*=2;
    try(InputStream source=getContentResolver().openInputStream(uri)){Bitmap bitmap=BitmapFactory.decodeStream(source,null,scaled);if(bitmap==null)throw new IOException("Gambar tidak dapat dibuka");view.setImageBitmap(bitmap);}}
  GradientDrawable shape(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
  void makeCircle(ImageView view){
    GradientDrawable oval=new GradientDrawable();oval.setShape(GradientDrawable.OVAL);oval.setColor(Color.TRANSPARENT);view.setBackground(oval);view.setClipToOutline(true);view.setScaleType(ImageView.ScaleType.CENTER_CROP);
  }
  TextView text(String s,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);if(bold)t.setTypeface(null,Typeface.BOLD);t.setGravity(Gravity.CENTER_VERTICAL);return t;}
  LinearLayout col(){LinearLayout l=new LinearLayout(this);l.setOrientation(1);return l;}
  LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(0);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
  LinearLayout.LayoutParams params(int w,int h){return new LinearLayout.LayoutParams(w<0?w:dp(w),h<0?h:dp(h));}
  void add(LinearLayout l,View v,int w,int h){l.addView(v,params(w,h));}
  void gap(LinearLayout l,int h){add(l,new View(this),1,h);}
  TextView chip(String s,int back,int fore){TextView t=text(s,13,fore,true);t.setGravity(Gravity.CENTER);t.setBackground(shape(back,11));t.setPadding(dp(8),dp(5),dp(8),dp(5));return t;}
  void loadMenu(){JSONArray custom=entries("custom_menu");int size=7+custom.length();names=Arrays.copyOf(new String[]{"Sate Ayam","Sate Daging","Sate Kambing","Nasi Impit","Extra Kuah Kacang","Laksa Utara","Kuih Siput"},size);icons=Arrays.copyOf(new String[]{"🍢","🥩","🍢","🍚","🥣","🍜","🥨"},size);prices=Arrays.copyOf(new int[]{160,180,200,60,100,700,500},size);qty=new int[size];
    for(int i=7;i<size;i++){JSONObject item=custom.optJSONObject(i-7);names[i]=item==null?"Menu":item.optString("name","Menu");icons[i]="🍽";prices[i]=item==null?0:item.optInt("price");}for(int i=0;i<size;i++)prices[i]=getPreferences(0).getInt("price_"+i,prices[i]);}
  void clearCurrentNasiDamageOnce(){
    android.content.SharedPreferences p=getPreferences(0);if(p.getBoolean("fix_nasi_damage_20260926",false))return;try{JSONArray old=entries("stock_damage"),keep=new JSONArray();String today=date();for(int i=0;i<old.length();i++){JSONObject e=old.optJSONObject(i);if(e==null)continue;boolean remove=e.optInt("item",-1)==3&&today.equals(entryDay(e));if(!remove)keep.put(e);}p.edit().putString("stock_damage",keep.toString()).putBoolean("fix_nasi_damage_20260926",true).commit();}catch(Exception ignored){}
  }
  void applyCorrection20260926(){
    android.content.SharedPreferences p=getPreferences(0);if(p.getBoolean("fix_stock_supplier_20260926_v2",false))return;
    try{
      String today=date();JSONArray old=entries("stock_entries"),keep=new JSONArray();
      // Sate Daging: keadaan semasa diminta = stok awal 71, restock hari ini 0.
      for(int i=0;i<old.length();i++){JSONObject e=old.optJSONObject(i);if(e==null)continue;boolean remove=e.optInt("item",-1)==1&&today.equals(entryDay(e));if(!remove)keep.put(e);}
      JSONObject opening=new JSONObject();opening.put("time",timestamp());opening.put("item",1);opening.put("qty",71);opening.put("type","opening");opening.put("cost",0);opening.put("note","Pembetulan stok awal kepada 71");keep.put(opening);
      android.content.SharedPreferences.Editor ed=p.edit().putString("stock_entries",keep.toString())
        .putInt("supplier_due_sate",17500).remove("supplier_due")
        .putBoolean("menu_supplier_5",true).putBoolean("menu_supplier_6",true)
        .putBoolean("fix_stock_supplier_20260926_v2",true);
      if(!ed.commit())throw new Exception();
    }catch(Exception ignored){}
  }
  void applySupplierReceiptDetailFix20260926(){
    android.content.SharedPreferences p=getPreferences(0);if(p.getBoolean("fix_supplier_receipt_detail_20260926",false))return;
    try{
      if(supplierDue(-1)==17500 && supplierItems(-1).length()==0){
        JSONArray lines=new JSONArray();addSupplierItem(lines,0,100,11000);addSupplierItem(lines,1,50,6500);
        p.edit().putString(supplierItemsKey(-1),lines.toString()).putBoolean("fix_supplier_receipt_detail_20260926",true).commit();
      }else p.edit().putBoolean("fix_supplier_receipt_detail_20260926",true).commit();
    }catch(Exception ignored){}
  }
  @Override public void onCreate(Bundle b){super.onCreate(b);firebaseAuth = FirebaseAuth.getInstance();firestore = FirebaseFirestore.getInstance();
GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
        .requestIdToken(getString(R.string.default_web_client_id))
        .requestEmail()
        .build();
googleSignInClient = GoogleSignIn.getClient(this, gso);snapshotBeforeUpdate();loadMenu();clearCurrentNasiDamageOnce();applyCorrection20260926();applySupplierReceiptDetailFix20260926();selectedDay=date();selectedMonth=selectedDay.substring(0,7);rangeStart=selectedDay;selectedStockDay=selectedDay;selectedCashDay=selectedDay;if(b!=null){int[] saved=b.getIntArray("cart");if(saved!=null)System.arraycopy(saved,0,qty,0,Math.min(saved.length,qty.length));activePage=b.getInt("page",0);selectedDay=b.getString("selectedDay",selectedDay);selectedMonth=selectedDay.substring(0,7);rangeStart=b.getString("rangeStart",rangeStart);selectedStockDay=b.getString("selectedStockDay",selectedStockDay);selectedCashDay=b.getString("selectedCashDay",selectedCashDay);cashDailyMode=b.getBoolean("cashDailyMode",cashDailyMode);}draw();}
  @Override protected void onSaveInstanceState(Bundle b){b.putIntArray("cart",qty);b.putInt("page",activePage);b.putString("selectedDay",selectedDay);b.putString("rangeStart",rangeStart);b.putString("selectedStockDay",selectedStockDay);b.putString("selectedCashDay",selectedCashDay);b.putBoolean("cashDailyMode",cashDailyMode);super.onSaveInstanceState(b);}
  void draw(){
    cachedStock=null;
    LinearLayout screen=col();screen.setBackgroundColor(cream);
    getWindow().setStatusBarColor(cream);
    getWindow().setNavigationBarColor(ink);
    getWindow().getDecorView().setSystemUiVisibility(0);
    if(Build.VERSION.SDK_INT>=35){
      screen.setOnApplyWindowInsetsListener((view,insets)->{
        view.setPadding(0,insets.getSystemWindowInsetTop(),0,insets.getSystemWindowInsetBottom());
        return insets;
      });
    }
    setContentView(screen);
    ScrollView scroll=new ScrollView(this);scroll.setVerticalScrollBarEnabled(false);screen.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
    body=col();body.setPadding(dp(17),dp(15),dp(17),dp(25));scroll.addView(body);
    if(activePage!=0){drawPage();addNavigation(screen);return;}
    LinearLayout header=row();ImageView logo=new ImageView(this);logo.setImageResource(R.drawable.warisan_logo);makeCircle(logo);add(header,logo,49,49);
    LinearLayout heading=col();heading.setPadding(dp(10),0,0,0);add(heading,text("WARISAN POS",21,blue,true),-1,-2);add(heading,text("KIOS WARISAN  ·  SISTEM JUALAN",10,muted,true),-1,-2);header.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
    add(body,header,-1,-2);gap(body,18);
    LinearLayout hero=col();hero.setPadding(dp(18),dp(14),dp(18),dp(14));hero.setBackground(shape(blue,17));
    add(hero,text("✦  JUALAN HARI INI",11,0xffffdf9a,true),-1,-2);today=text("RM 0.00",29,Color.WHITE,true);add(hero,today,-1,-2);add(hero,text("Bayaran selesai direkod di sini",12,0xffe5efff,false),-1,-2);add(body,hero,-1,-2);today();gap(body,20);
    LinearLayout title=row();title.addView(text("Pilih menu",20,ink,true),new LinearLayout.LayoutParams(0,-2,1));TextView addMenuButton=chip("+ MENU",blue,Color.WHITE);add(title,addMenuButton,-2,-2);addMenuButton.setOnClickListener(v->addMenu());items=chip("0 item",0xfff0e5cb,blue);LinearLayout.LayoutParams itemLp=params(-2,-2);itemLp.leftMargin=dp(7);title.addView(items,itemLp);add(body,title,-1,-2);
    add(body,text("Tekan + untuk tambah pesanan",12,muted,false),-1,-2);gap(body,13);
    ArrayList<Integer> visibleMenu=new ArrayList<>();for(int i=0;i<names.length;i++)if(!getPreferences(0).getBoolean("menu_hidden_"+i,false))visibleMenu.add(i);for(int k=0;k<visibleMenu.size();k+=2){LinearLayout pair=row();pair.setGravity(Gravity.TOP);product(pair,visibleMenu.get(k));if(k+1<visibleMenu.size())product(pair,visibleMenu.get(k+1));add(body,pair,-1,-2);gap(body,9);}
    gap(body,10);add(body,text("Pesanan semasa",20,ink,true),-1,-2);gap(body,10);
    basket=col();basket.setPadding(dp(13),dp(10),dp(13),dp(10));basket.setBackground(shape(surface,15));add(body,basket,-1,-2);
    LinearLayout footer=row();footer.setPadding(dp(17),dp(9),dp(17),dp(10));footer.setBackgroundColor(surface);
    LinearLayout amount=col();add(amount,text("JUMLAH",11,muted,true),-1,-2);total=text("RM 0.00",22,blue,true);add(amount,total,-1,-2);footer.addView(amount,new LinearLayout.LayoutParams(0,-2,1));
    payButton=new Button(this);payButton.setAllCaps(false);payButton.setText("Bayar  →");payButton.setTextColor(Color.WHITE);payButton.setTextSize(16);payButton.setBackground(shape(blue,12));payButton.setOnClickListener(v->pay());add(footer,payButton,140,51);add(screen,footer,-1,-2);refresh();addNavigation(screen);
  }
  void product(LinearLayout pair,int id){
    LinearLayout card=col();card.setPadding(dp(9),dp(8),dp(9),dp(8));card.setBackground(shape(surface,15));
    String photo=getPreferences(0).getString("menu_image_"+id,"");
    if(!photo.isEmpty()){ImageView photoView=new ImageView(this);try{loadMenuPhoto(photoView,photo);photoView.setScaleType(ImageView.ScaleType.CENTER_CROP);add(card,photoView,40,40);}catch(Exception e){add(card,chip(icons[id],0xfffff3db,blue),35,35);}}
    else{TextView icon=chip(icons[id],0xfffff3db,blue);icon.setTextSize(18);add(card,icon,35,35);}gap(card,5);
    TextView name=text(names[id],14,ink,true);name.setMaxLines(2);name.setMinHeight(dp(34));add(card,name,-1,-2);
    add(card,text(money(prices[id]),14,gold,true),-1,-2);
    int available=unlimited(id)?Integer.MAX_VALUE:stock(id);TextView stockLabel=text(unlimited(id)?"Kuah · tanpa had unit":"Stok: "+available+(available==0?" · HABIS":""),11,available==0?0xffb54743:blue,true);add(card,stockLabel,-1,-2);gap(card,5);
    LinearLayout controls=row();TextView minus=chip("−",0xffe8edf6,blue),number=text(""+qty[id],15,ink,true),plus=chip("+",blue,Color.WHITE);number.setGravity(Gravity.CENTER);
    controls.addView(minus,new LinearLayout.LayoutParams(0,dp(32),1));controls.addView(number,new LinearLayout.LayoutParams(0,dp(32),1));controls.addView(plus,new LinearLayout.LayoutParams(0,dp(32),1));add(card,controls,-1,-2);
    minus.setOnClickListener(v->{qty[id]=Math.max(0,qty[id]-1);draw();});
    plus.setAlpha(available<=qty[id]?.35f:1f);plus.setEnabled(available>qty[id]);
    plus.setOnClickListener(v->stockLimit(id,1));
    if(id<3){gap(card,5);LinearLayout presets=row();for(int n:new int[]{10,20,30}){TextView p=chip("+"+n,0xfffff3db,blue);p.setEnabled(available>qty[id]);p.setAlpha(available<=qty[id]?.35f:1f);LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(0,dp(26),1);pp.setMargins(dp(1),0,dp(1),0);presets.addView(p,pp);p.setOnClickListener(v->stockLimit(id,n));}add(card,presets,-1,-2);}
    LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(0,-2,1);cp.setMargins(dp(2),0,dp(2),0);pair.addView(card,cp);
  }
  void refresh(){items.setText(count()+" item");total.setText(money(sum()));payButton.setAlpha(sum()==0?.55f:1f);basket.removeAllViews();
    if(count()==0){TextView empty=text("Belum ada pesanan. Pilih menu di atas.",13,muted,false);empty.setPadding(0,dp(13),0,dp(13));add(basket,empty,-1,-2);return;}
    for(int i=0;i<qty.length;i++)if(qty[i]>0){final int id=i;LinearLayout r=row();r.addView(text(names[i]+" × "+qty[i],14,ink,true),new LinearLayout.LayoutParams(0,dp(39),1));add(r,text(money(qty[i]*prices[i]),13,blue,true),-2,-2);TextView minus=chip("−",0xfff0e5cb,blue);LinearLayout.LayoutParams m=params(32,30);m.leftMargin=dp(8);r.addView(minus,m);minus.setOnClickListener(v->{qty[id]--;draw();});add(basket,r,-1,-2);}
  }
  void addNavigation(LinearLayout screen){LinearLayout nav=row();nav.setBackgroundColor(ink);nav.setPadding(dp(4),dp(5),dp(4),dp(6));String[] labels={"MENU","STOK","GRAF","DUIT","SETTING"};
    for(int i=0;i<labels.length;i++){final int page=i;boolean selected=(i==activePage)||(i==4&&activePage>=4);TextView tab=text(labels[i],11,selected?Color.rgb(19,24,34):Color.WHITE,true);tab.setGravity(Gravity.CENTER);tab.setBackground(shape(selected?gold:Color.rgb(20,58,101),9));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(47),1);p.setMargins(dp(2),0,dp(2),0);nav.addView(tab,p);tab.setOnClickListener(v->{activePage=page;draw();});}add(screen,nav,-1,-2);}
  void heading(String title,String subtitle){add(body,text(title,24,blue,true),-1,-2);add(body,text(subtitle,12,muted,false),-1,-2);gap(body,18);}
  void action(String label,Runnable callback){TextView button=text(label+"   ›",15,blue,true);button.setPadding(dp(16),dp(14),dp(14),dp(14));button.setBackground(shape(surface,12));add(body,button,-1,-2);gap(body,9);button.setOnClickListener(v->callback.run());}
  void metric(LinearLayout row,String title,String value,int highlight,Runnable tap){LinearLayout card=col();card.setPadding(dp(12),dp(12),dp(9),dp(12));card.setBackground(shape(surface,13));TextView name=text(title,11,muted,true);name.setMaxLines(2);add(card,name,-1,-2);gap(card,8);TextView figure=text(value,value.startsWith("RM")?16:20,highlight,true);figure.setMaxLines(1);figure.setEllipsize(android.text.TextUtils.TruncateAt.END);add(card,figure,-1,-2);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(94),1);p.setMargins(dp(3),0,dp(3),0);row.addView(card,p);if(tap!=null)card.setOnClickListener(v->tap.run());}
  boolean legacySupplierRestock(JSONObject e){
    String c=e==null?"":e.optString("category","");if(!c.startsWith("Restock · "))return false;String item=c.substring("Restock · ".length()).trim();for(int id=0;id<names.length;id++)if(names[id].equals(item)&&supplierItem(id))return true;return false;
  }
  int[] cashTotals(String month){int[] totals=new int[2];JSONArray entries=entries("cash_entries");for(int i=0;i<entries.length();i++){JSONObject e=entries.optJSONObject(i);if(e==null||legacySupplierRestock(e)||!month.equals(e.optString("month")))continue;int amount=e.optInt("amount");if(amount>=0)totals[0]+=amount;else totals[1]-=amount;}return totals;}
  int[] cashTotalsDay(String day){int[] totals=new int[2];JSONArray list=entries("cash_entries");for(int i=0;i<list.length();i++){JSONObject e=list.optJSONObject(i);if(e==null||legacySupplierRestock(e)||!day.equals(entryDay(e)))continue;int amount=e.optInt("amount");if(amount>=0)totals[0]+=amount;else totals[1]-=amount;}return totals;}
  int damageTotalDay(String day){int n=0;JSONArray list=entries("stock_damage");for(int i=0;i<list.length();i++){JSONObject e=list.optJSONObject(i);if(e!=null&&!e.optBoolean("stockReset",false)&&day.equals(entryDay(e)))n+=e.optInt("cost");}return n;}
  void chooseStockDay(){pickDay("Pilih tarikh stok",selectedStockDay,value->{selectedStockDay=value;draw();});}
  void chooseCashDay(){pickDay("Pilih tarikh duit",selectedCashDay,value->{selectedCashDay=value;selectedMonth=value.substring(0,7);draw();});}
  String supplierDueKey(int group){return group<0?"supplier_due_sate":group==5?"supplier_due_laksa":group==6?"supplier_due_kuih":"supplier_due_item_"+group;}
  int supplierDue(int group){return Math.max(0,getPreferences(0).getInt(supplierDueKey(group),0));}
  int supplierDue(){int total=supplierDue(-1);for(int id=3;id<names.length;id++)if(id!=4&&supplierItem(id))total+=supplierDue(id);return total;}
  String supplierItemsKey(int group){return group<0?"supplier_items_sate":"supplier_items_"+group;}
  JSONArray supplierItems(int group){try{return new JSONArray(getPreferences(0).getString(supplierItemsKey(group),"[]"));}catch(Exception e){return new JSONArray();}}
  void addSupplierItem(JSONArray list,int id,int units,int cost)throws Exception{
    JSONObject x=new JSONObject();x.put("item",id);x.put("name",names[id]);x.put("qty",units);x.put("unitCost",costUnit(id));x.put("pack",costPack(id));x.put("subtotal",cost);list.put(x);
  }
  String supplierItemsText(int group){
    JSONArray list=supplierItems(group);StringBuilder b=new StringBuilder();
    for(int i=0;i<list.length();i++){JSONObject x=list.optJSONObject(i);if(x==null)continue;int q=x.optInt("qty"),unit=x.optInt("unitCost"),pack=Math.max(1,x.optInt("pack",1)),sub=x.optInt("subtotal");
      if(b.length()>0)b.append("\n");b.append(x.optString("name","Item")).append(": ").append(q).append(q>0&&(x.optInt("item",-1)<3)?" cucuk":" unit").append(" × ").append(money(unit));
      if(pack>1)b.append(" / ").append(pack).append(" unit");b.append(" = ").append(money(sub));
    }return b.toString();
  }
  boolean sateItem(int id){return id>=0&&id<3;}
  boolean supplierItem(int id){return sateItem(id)||getPreferences(0).getBoolean("menu_supplier_"+id,id==5||id==6);}
  int totalMoneyBalance(){long total=0;for(String key:getPreferences(0).getAll().keySet())if(key.matches("sales_\\d{4}-\\d{2}-\\d{2}"))total+=getPreferences(0).getInt(key,0);JSONArray list=entries("cash_entries");for(int i=0;i<list.length();i++){JSONObject e=list.optJSONObject(i);if(e!=null&&!legacySupplierRestock(e))total+=e.optInt("amount");}return (int)Math.max(Integer.MIN_VALUE,Math.min(Integer.MAX_VALUE,total));}
  String supplierReceipt(int group,int original,int deduction,int paid,String remark){StringBuilder b=new StringBuilder();
    String shopName=getPreferences(0).getString("shop_name","WARISAN FROZEN");b.append(centerReceipt(shopName));
    String phone=getPreferences(0).getString("receipt_phone","");if(!phone.isEmpty())b.append(centerReceipt("Tel: "+phone));
    String email=getPreferences(0).getString("receipt_email","");if(!email.isEmpty())b.append(centerReceipt(email));
    b.append("--------------------------------\n").append(centerReceipt("RESIT BAYARAN PEMBEKAL"))
      .append(line("Tarikh",new SimpleDateFormat("dd/MM/yy HH:mm",Locale.US).format(new Date()))).append("--------------------------------\n");
    String details=supplierItemsText(group);if(!details.isEmpty())b.append("PECAHAN STOK\n").append(details).append("\n--------------------------------\n");
    b.append(line("Jumlah asal",money(original))).append(line("Jumlah tolakan",money(deduction)))
      .append("--------------------------------\n").append(line("JUMLAH DIBAYAR",money(paid)));
    if(!remark.trim().isEmpty())b.append("--------------------------------\nRemark:\n").append(remark.trim()).append("\n");
    b.append("================================\n").append(centerReceipt("TELAH DIBAYAR")).append(ownerReceipt()).append("\n");return b.toString();}
  void showSupplierReceipt(String printed,int group,int original,int deduction,int paid,String remark){
    ScrollView scroll=new ScrollView(this);scroll.setVerticalScrollBarEnabled(false);int receiptText=0xff26384c,receiptMuted=0xff66788a;
    LinearLayout sheet=col();sheet.setPadding(dp(18),dp(12),dp(18),dp(14));sheet.setBackgroundColor(Color.WHITE);scroll.addView(sheet);
    ImageView logo=new ImageView(this);logo.setImageResource(R.drawable.warisan_logo);makeCircle(logo);LinearLayout logoRow=row();logoRow.setGravity(Gravity.CENTER);add(logoRow,logo,74,74);add(sheet,logoRow,-1,-2);gap(sheet,5);
    TextView brand=text(getPreferences(0).getString("shop_name","WARISAN FROZEN"),17,blue,true);brand.setGravity(Gravity.CENTER);add(sheet,brand,-1,-2);
    String phone=getPreferences(0).getString("receipt_phone","");TextView contact=text(phone.isEmpty()?"No. telefon belum diisi":"Tel: "+phone,11,receiptMuted,false);contact.setGravity(Gravity.CENTER);add(sheet,contact,-1,-2);
    String email=getPreferences(0).getString("receipt_email","");if(!email.isEmpty()){TextView mail=text(email,11,receiptMuted,false);mail.setGravity(Gravity.CENTER);add(sheet,mail,-1,-2);}
    receiptRule(sheet);TextView title=text("RESIT BAYARAN PEMBEKAL",14,blue,true);title.setGravity(Gravity.CENTER);add(sheet,title,-1,-2);gap(sheet,9);
    receiptRow(sheet,"Tarikh",new SimpleDateFormat("dd/MM/yyyy HH:mm",Locale.US).format(new Date()),false);receiptRule(sheet);
    String details=supplierItemsText(group);if(!details.isEmpty()){TextView dl=text("PECAHAN STOK",11,receiptMuted,true);add(sheet,dl,-1,-2);gap(sheet,4);for(String lineText:details.split("\\n")){TextView dv=text(lineText,12,receiptText,false);add(sheet,dv,-1,-2);gap(sheet,3);}receiptRule(sheet);}
    receiptRow(sheet,"Jumlah asal",money(original),false);gap(sheet,7);receiptRow(sheet,"Jumlah tolakan",money(deduction),false);receiptRule(sheet);receiptRow(sheet,"JUMLAH DIBAYAR",money(paid),true);
    if(!remark.trim().isEmpty()){receiptRule(sheet);TextView rl=text("REMARK",11,receiptMuted,true);add(sheet,rl,-1,-2);gap(sheet,4);TextView rv=text(remark.trim(),13,receiptText,false);add(sheet,rv,-1,-2);}
    receiptRule(sheet);TextView status=text("TELAH DIBAYAR",13,blue,true);status.setGravity(Gravity.CENTER);add(sheet,status,-1,-2);
    String owner=ownerDisplay();if(!owner.isEmpty()){gap(sheet,8);TextView owned=text("DIMILIKI OLEH : "+owner,10,receiptMuted,true);owned.setGravity(Gravity.CENTER);add(sheet,owned,-1,-2);}
    new AlertDialog.Builder(this).setTitle("Semak resit pembekal").setView(scroll).setPositiveButton("Cetak resit",(d,w)->print(printed)).setNeutralButton("Share",(d,w)->shareReceiptImage(sheet,"resit-pembekal")).setNegativeButton("Tutup",null).show();}
  void previewSupplierReceipt(){int original=17500,deduction=2500,paid=15000;String remark="Contoh remark / alasan tolakan";showSupplierReceipt(supplierReceipt(-1,original,deduction,paid,remark),-1,original,deduction,paid,remark);}
  void paySupplier(){paySupplier(-1,"Sate");}
  void paySupplier(int group,String label){int due=supplierDue(group);if(due<=0){message("Tiada bayaran pembekal "+label+" tertunggak.");return;}LinearLayout form=col();form.setPadding(dp(18),dp(5),dp(18),0);add(form,text("Jumlah perlu dibayar: "+money(due),15,ink,true),-1,-2);EditText deduction=priceField("Jumlah tolakan RM","0.00");EditText remark=new EditText(this);remark.setHint("Remark / alasan tolakan");add(form,text("Jumlah tolakan",12,muted,true),-1,-2);add(form,deduction,-1,-2);add(form,text("Remark",12,muted,true),-1,-2);add(form,remark,-1,-2);AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Telah dibayar · "+label).setView(form).setPositiveButton("CONFIRM",null).setNegativeButton("Batal",null).create();dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(x->{try{int cut=parseCents(deduction);String note=remark.getText().toString().trim();if(cut<0||cut>due){message("Jumlah tolakan tidak sah");return;}if(cut>0&&note.isEmpty()){message("Isi remark / alasan untuk jumlah tolakan");return;}int paid=due-cut;JSONObject expense=new JSONObject();expense.put("time",timestamp());expense.put("month",date().substring(0,7));expense.put("amount",-paid);expense.put("category","Bayaran Pembekal · "+label);expense.put("note",note);expense.put("supplierOriginal",due);expense.put("supplierDeduction",cut);expense.put("supplierPaid",paid);JSONArray cash=entries("cash_entries");cash.put(expense);String key=supplierDueKey(group);String detailSnapshot=supplierItems(group).toString();getPreferences(0).edit().putString("supplier_receipt_snapshot_"+group,detailSnapshot).commit();String printed=supplierReceipt(group,due,cut,paid,note);android.content.SharedPreferences.Editor editor=getPreferences(0).edit().putString("cash_entries",cash.toString()).putInt(key,0).remove(supplierItemsKey(group));if(!editor.commit())throw new Exception();dialog.dismiss();draw();getPreferences(0).edit().putString(supplierItemsKey(group),detailSnapshot).commit();showSupplierReceipt(printed,group,due,cut,paid,note);getPreferences(0).edit().remove(supplierItemsKey(group)).apply();}catch(Exception e){message("Semak jumlah tolakan dan cuba lagi.");}}));dialog.show();}


  void addSupplierPaymentCard(String label,int group){int due=supplierDue(group);LinearLayout supplier=col();supplier.setPadding(dp(15),dp(13),dp(15),dp(13));supplier.setBackground(shape(surface,15));add(supplier,text("BAYARAN PEMBEKAL · "+label,11,muted,true),-1,-2);gap(supplier,7);add(supplier,text("Jumlah perlu dibayar",12,ink,true),-1,-2);add(supplier,text(money(due),23,due>0?gold:blue,true),-1,-2);gap(supplier,10);TextView paid=chip("TELAH DIBAYAR",due>0?blue:0xff526273,Color.WHITE);paid.setGravity(Gravity.CENTER);paid.setEnabled(due>0);paid.setAlpha(due>0?1f:.55f);add(supplier,paid,-1,43);paid.setOnClickListener(v->paySupplier(group,label));add(body,supplier,-1,-2);gap(body,10);}
  void drawPage(){if(activePage==1){heading("Stok & restock","Pilih tarikh untuk semak rekod stok. Baki hari sebelumnya dibawa ke hari seterusnya.");
      action("Tarikh stok: "+selectedStockDay+"   ▼",()->chooseStockDay());boolean stockTodayView=date().equals(selectedStockDay);
      for(int i=0;i<names.length;i++){final int id=i;if(getPreferences(0).getBoolean("menu_hidden_"+id,false))continue;if(unlimited(id)){action(names[id]+" · kuah ikut liter (tiada had unit)",()->message("Kuah kacang sentiasa boleh dijual."));continue;}
        int[] st=stockForDay(id,selectedStockDay);LinearLayout card=col();card.setPadding(dp(14),dp(13),dp(14),dp(13));card.setBackground(shape(surface,15));LinearLayout nr=row();nr.addView(text(names[id],17,ink,true),new LinearLayout.LayoutParams(0,-2,1));add(nr,chip(selectedStockDay,0xff203f61,muted),-2,-2);add(card,nr,-1,-2);gap(card,9);
        LinearLayout upper=row();stockTile(upper,"STOK AWAL",""+st[0]);stockTile(upper,"RESTOCK","+"+st[1]);add(card,upper,-1,-2);gap(card,5);LinearLayout lower=row();stockTile(lower,"TERJUAL","−"+st[2]);stockTile(lower,"BAKI",""+st[3]);add(card,lower,-1,-2);
        TextView damaged=text("Rosak pada tarikh ini: "+st[4]+" unit",12,st[4]>0?0xffff4d4d:muted,true);damaged.setPadding(dp(4),dp(7),0,0);add(card,damaged,-1,-2);gap(card,10);
        if(stockTodayView){LinearLayout controls=row();TextView startBtn=chip(hasOpeningStock(id)?"Edit stok awal":"+ Stok awal",0xffffd54f,0xff6d3b16),more=chip("+ Restock",blue,Color.WHITE);controls.addView(startBtn,new LinearLayout.LayoutParams(0,dp(43),1));LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(0,dp(43),1);mp.leftMargin=dp(7);controls.addView(more,mp);startBtn.setOnClickListener(v->{if(hasOpeningStock(id))editOpeningStock(id);else stockEntryItem(id,true);});more.setOnClickListener(v->stockEntryItem(id,false));add(card,controls,-1,-2);gap(card,7);TextView damage=chip("− Stok rosak",0xffff2d2d,Color.WHITE);add(card,damage,-1,40);damage.setOnClickListener(v->damageEntry(id));}
        else{TextView old=chip("REKOD LAMA · PAPARAN SAHAJA",0xff31485f,muted);old.setGravity(Gravity.CENTER);add(card,old,-1,38);}
        add(body,card,-1,-2);gap(body,12);}
      action("Lihat ringkasan stok tarikh dipilih",()->stockBalance());action("Rekod stok rosak",()->damageHistory());
    }else if(activePage==2){heading("Graf jualan","Pilih tarikh untuk lihat rekod jualan harian.");
      action("Tarikh: "+selectedDay+"   ▼",()->chooseSalesDay());
      int days=daysInMonth(selectedMonth);int[] totals=new int[days];String[] labels=new String[days];
      for(int i=0;i<days;i++){String key=selectedMonth+String.format(Locale.US,"-%02d",i+1);totals[i]=getPreferences(0).getInt("sales_"+key,0);labels[i]=(i+1==1||(i+1)%5==0)?""+(i+1):"";}
      LinearLayout summary=row();metric(summary,"JUALAN HARI",money(getPreferences(0).getInt("sales_"+selectedDay,0)),blue,null);metric(summary,"PESANAN HARI",""+getPreferences(0).getInt("orders_"+selectedDay,0),gold,null);add(body,summary,-1,-2);gap(body,12);
      LinearLayout chartCard=col();chartCard.setPadding(dp(12),dp(14),dp(12),dp(8));chartCard.setBackground(shape(surface,14));add(chartCard,text("JUALAN HARIAN · "+monthLabel(selectedMonth),12,ink,true),-1,-2);add(chartCard,new SalesChart(totals,labels),-1,190);add(body,chartCard,-1,-2);gap(body,17);
      int[] sold=soldBetween(selectedDay,selectedDay);
      add(body,text("ITEM TERJUAL PADA "+selectedDay,13,muted,true),-1,-2);gap(body,10);
      ArrayList<Integer> visibleGraphItems=new ArrayList<>();for(int i=0;i<names.length;i++)if(!getPreferences(0).getBoolean("menu_hidden_"+i,false))visibleGraphItems.add(i);for(int p=0;p<visibleGraphItems.size();p+=2){LinearLayout pair=row();int i=visibleGraphItems.get(p);metric(pair,names[i],sold[i]+(i<3?" cucuk":i==4?" hidangan":" unit"),sold[i]>0?blue:muted,null);if(p+1<visibleGraphItems.size()){int j=visibleGraphItems.get(p+1);metric(pair,names[j],sold[j]+(j<3?" cucuk":j==4?" hidangan":" unit"),sold[j]>0?blue:muted,null);}add(body,pair,-1,-2);gap(body,7);}
      action("Pecahan jualan · total ikut tarikh",()->chooseRangeStart());action("Muat turun laporan Excel / PDF",()->exportMenu());
    }else if(activePage==3){heading("Duit masuk / keluar","Aliran wang ikut harian atau bulanan. Baki duit kekal keseluruhan sebagai duit rolling.");
      LinearLayout mode=row();TextView daily=chip("HARIAN",cashDailyMode?gold:0xff294563,cashDailyMode?0xff17202c:Color.WHITE),monthlyBtn=chip("BULANAN",!cashDailyMode?gold:0xff294563,!cashDailyMode?0xff17202c:Color.WHITE);daily.setGravity(Gravity.CENTER);monthlyBtn.setGravity(Gravity.CENTER);mode.addView(daily,new LinearLayout.LayoutParams(0,dp(42),1));LinearLayout.LayoutParams mlp=new LinearLayout.LayoutParams(0,dp(42),1);mlp.leftMargin=dp(7);mode.addView(monthlyBtn,mlp);add(body,mode,-1,-2);gap(body,9);daily.setOnClickListener(v->{cashDailyMode=true;draw();});monthlyBtn.setOnClickListener(v->{cashDailyMode=false;draw();});
      if(cashDailyMode)action("Tarikh: "+selectedCashDay+"   ▼",()->chooseCashDay());else action("Bulan: "+monthLabel(selectedMonth)+"   ▼",()->chooseMonth());
      String pm=cashDailyMode?selectedCashDay.substring(0,7):selectedMonth;int[] cash=cashDailyMode?cashTotalsDay(selectedCashDay):cashTotals(pm);int sale=cashDailyMode?getPreferences(0).getInt("sales_"+selectedCashDay,0):monthTotal(pm);int lossValue=cashDailyMode?damageTotalDay(selectedCashDay):damageTotal(pm);
      LinearLayout top=row();metric(top,cashDailyMode?"JUALAN HARI":"JUALAN POS",money(sale),blue,null);metric(top,"DUIT MASUK",money(cash[0]),blue,null);add(body,top,-1,-2);gap(body,8);LinearLayout bottom=row();metric(bottom,"DUIT KELUAR",money(cash[1]),ink,null);metric(bottom,"BAKI DUIT · OVERALL",money(totalMoneyBalance()),gold,null);add(body,bottom,-1,-2);gap(body,8);LinearLayout loss=row();metric(loss,cashDailyMode?"STOK ROSAK · HARI":"STOK ROSAK · BULAN",money(lossValue),0xffb54743,()->damageHistory());add(body,loss,-1,-2);gap(body,12);
      addSupplierPaymentCard("SATE",-1);for(int supplierId=3;supplierId<names.length;supplierId++){if(supplierId==4||getPreferences(0).getBoolean("menu_hidden_"+supplierId,false)||!supplierItem(supplierId))continue;addSupplierPaymentCard(names[supplierId].toUpperCase(new Locale("ms","MY")),supplierId);}
      add(body,text("Baki Duit tidak reset bila tukar tarikh/bulan. Ia ikut semua jualan + duit masuk − duit keluar yang telah dibayar.",11,muted,false),-1,-2);gap(body,8);if(!cashDailyMode){CashDonutChart cashChart=new CashDonutChart(pm);add(body,cashChart,-1,270);gap(body,8);action("Pecahan harian graf",()->cashDaily(pm));}
      LinearLayout controls=row();metric(controls,"+ CATAT","Masuk",blue,()->cashEntry(true));metric(controls,"− CATAT","Keluar",ink,()->cashEntry(false));add(body,controls,-1,-2);gap(body,12);action(cashDailyMode?"Lihat catatan tarikh dipilih":"Lihat catatan bulan dipilih",()->cashHistory());
    }else if(activePage==4){
      heading("Setting","WarisanPOS 3.15 · Tetapan kedai dan data.");
      action("👤  Account",()->{activePage=5;draw();});
      action("🧾  Bill / Resit",()->{activePage=7;draw();});
      action("🍽  Pengurusan Menu",()->{activePage=6;draw();});
      action("🏪  Maklumat Kedai",()->{activePage=8;draw();});
      action("⚠  Reset",()->{activePage=9;draw();});
    }else if(activePage==5){
      heading("Account","Google, backup dan pemulihan data.");
      action("‹  Kembali ke Setting",()->{activePage=4;draw();});
      FirebaseUser user=firebaseAuth.getCurrentUser();
      if(user==null){
        action("🔐  SIGN IN GOOGLE",()->{Intent signInIntent=googleSignInClient.getSignInIntent();startActivityForResult(signInIntent,RC_SIGN_IN);});
      }else{
        String email=user.getEmail()==null?"Google Account":user.getEmail();
        action("☁  GOOGLE: "+email,()->message("Google account telah disambungkan."));
        action("LOG OUT GOOGLE",()->{firebaseAuth.signOut();googleSignInClient.signOut().addOnCompleteListener(task->draw());});
        action("☁  Backup / Sync ke Google",()->syncToCloud());
        action("Pulihkan dari Google",()->restoreFromCloud());
      }
      action("Backup ke telefon",()->backupPicker(false));
      action("Restore dari device",()->backupPicker(true));
      action("Pulihkan salinan sebelum update / pulih",()->recoverInternal());
    }else if(activePage==6){
      heading("Pengurusan Menu","Tambah, edit, gambar dan padam menu.");
      action("‹  Kembali ke Setting",()->{activePage=4;draw();});
      action("+ Tambah menu & harga",()->addMenu());
      action("Edit harga jualan & harga mentah",()->chooseMenuPrice());
      action("Pilih sumber menu · Pembekal / Sendiri",()->chooseMenuSource());
      action("Upload / tukar gambar menu",()->pickMenuImage());
      action("− Padam menu",()->deleteMenu());
    }else if(activePage==7){
      heading("Bill / Resit","Printer dan maklumat pada resit.");
      action("‹  Kembali ke Setting",()->{activePage=4;draw();});
      action("Pilih printer Bluetooth",()->choosePrinter());
      action("No. telefon pada resit",()->editPhone());
      action("Alamat Gmail pada resit",()->editEmail());
      action("Preview resit",()->previewTestReceipt(false));
      action("Preview resit pembekal",()->previewSupplierReceipt());
      action("Test Print",()->previewTestReceipt(true));
    }else if(activePage==8){
      heading("Maklumat Kedai","Butiran kedai dan syarikat.");
      action("‹  Kembali ke Setting",()->{activePage=4;draw();});
      action("Nama kedai",()->editShopInfo("shop_name","Nama kedai","WARISAN FROZEN"));
      action("Nama syarikat",()->editShopInfo("company_name","Nama syarikat",""));
      action("No. lesen / pendaftaran",()->editShopInfo("company_reg","No. lesen / pendaftaran perniagaan",""));
      action("Alamat HQ",()->editShopInfo("company_hq","Alamat HQ",""));
    }else if(activePage==9){
      heading("Reset","Pilihan reset data WarisanPOS.");
      action("‹  Kembali ke Setting",()->{activePage=4;draw();});
      action("Reset stok sahaja",()->resetData(false));
      action("Reset semua data",()->resetData(true));
    }}
  void stockTile(LinearLayout row,String title,String value){LinearLayout box=col();box.setPadding(dp(10),dp(8),dp(7),dp(8));box.setBackground(shape(0xffffd54f,10));int tileLabel=0xff6d3b16;int tileValue=0xff15263a;add(box,text(title,10,tileLabel,true),-1,-2);add(box,text(value,21,"BAKI".equals(title)?blue:tileValue,true),-1,-2);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.setMargins(dp(2),0,dp(2),0);row.addView(box,p);}
  void stockEntryItem(int id){stockEntryItem(id,false);}
  void stockEntryItem(int id,boolean opening){if(unlimited(id)){message("Kuah kacang diurus mengikut liter, tanpa had stok unit.");return;}if(opening&&hasOpeningStock(id)){editOpeningStock(id);return;}
    if(!opening&&costUnit(id)<=0){new AlertDialog.Builder(this).setTitle("Harga mentah belum ditetapkan").setMessage("Tetapkan harga mentah "+names[id]+" sebelum restock supaya duit keluar dikira dengan betul.").setPositiveButton("Edit harga",(d,w)->editMenuPrice(id)).setNegativeButton("Batal",null).show();return;}
    EditText input=new EditText(this);input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);input.setHint("Bilangan unit / cucuk");int before=stock(id);
    new AlertDialog.Builder(this).setTitle((opening?"Stok awal · ":"Restock · ")+names[id]).setMessage("Baki sebelum tambah: "+before+(opening?"\nStok awal tidak dikira sebagai belian baru.":"\nKos: "+money(costUnit(id))+" / "+costPack(id)+" unit. "+(supplierItem(id)?"Restock ini akan masuk ke Bayaran Pembekal sehingga ditanda Telah Dibayar.":"Restock akan dicatat sebagai duit keluar.")))
      .setView(input).setPositiveButton("Simpan",(dialog,w)->{try{int value=Integer.parseInt(input.getText().toString().trim());if(value<0||(!opening&&value==0)||value>100000)throw new NumberFormatException();if(opening&&hasOpeningStock(id))throw new Exception("Stok awal sudah direkod");int cost=opening?0:restockCost(id,value);JSONObject record=new JSONObject();record.put("time",timestamp());record.put("item",id);record.put("qty",value);record.put("type",opening?"opening":"restock");record.put("cost",cost);
        JSONArray stocks=entries("stock_entries");stocks.put(record);android.content.SharedPreferences.Editor editor=getPreferences(0).edit().putString("stock_entries",stocks.toString());
        if(!opening){if(supplierItem(id)){int group=sateItem(id)?-1:id;int due=supplierDue(group);long next=(long)due+cost;if(next>Integer.MAX_VALUE)throw new Exception("Jumlah pembekal terlalu besar");String key=supplierDueKey(group);editor.putInt(key,(int)next);JSONArray supplierLines=supplierItems(group);addSupplierItem(supplierLines,id,value,cost);editor.putString(supplierItemsKey(group),supplierLines.toString());}else{JSONObject expense=new JSONObject();expense.put("time",timestamp());expense.put("month",date().substring(0,7));expense.put("amount",-cost);expense.put("category","Restock · "+names[id]);expense.put("note",value+" unit × "+money(costUnit(id))+" / "+costPack(id)+" unit");JSONArray cash=entries("cash_entries");cash.put(expense);editor.putString("cash_entries",cash.toString());}}
        if(!editor.commit())throw new Exception("Gagal simpan rekod");draw();String costText=opening?"":(supplierItem(id)?"\nBayaran pembekal bertambah: "+money(cost)+"\nJumlah belum dibayar: "+money(supplierDue(id==5?5:id==6?6:-1)):"\nKos restock (duit keluar): "+money(cost));new AlertDialog.Builder(this).setTitle("Stok berjaya dikemas kini").setMessage((opening?"Stok awal: "+value:"Baki "+before+" + restock "+value+" = "+stock(id))+"\n\n"+names[id]+" · Baki sekarang: "+stock(id)+costText).setPositiveButton("OK",null).show();}catch(Exception e){message("Gagal simpan. Semak bilangan stok dan cuba lagi.");}}).setNegativeButton("Batal",null).show();}
  void editOpeningStock(int id){int current=stockToday(id)[0];EditText input=new EditText(this);input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);input.setText(""+Math.max(0,current));input.setHint("0 dibenarkan");new AlertDialog.Builder(this).setTitle("Edit stok awal · "+names[id]).setMessage("Stok awal semasa: "+current+"\nBoleh ubah sehingga 0. Ini pembetulan stok dan tidak dikira jualan atau duit keluar.").setView(input).setPositiveButton("Simpan",(d,w)->{try{int target=Integer.parseInt(input.getText().toString().trim());if(target<0||target>100000)throw new Exception();int delta=target-current;if(delta!=0){JSONObject e=new JSONObject();e.put("time",timestamp());e.put("item",id);e.put("qty",delta);e.put("type","opening_adjustment");e.put("note","Edit stok awal");JSONArray list=entries("stock_entries");list.put(e);if(!getPreferences(0).edit().putString("stock_entries",list.toString()).commit())throw new Exception();}cachedStock=null;qty[id]=Math.min(qty[id],stock(id));draw();message("Stok awal dikemas kini kepada "+target);}catch(Exception e){message("Masukkan stok awal 0 hingga 100000");}}).setNegativeButton("Batal",null).show();}
  void chooseMenuSource(){new AlertDialog.Builder(this).setTitle("Sumber menu").setItems(names,(d,id)->editMenuSource(id)).setNegativeButton("Tutup",null).show();}
  void editMenuSource(int id){String[] options={"Pembekal","Sendiri"};int checked=supplierItem(id)?0:1;new AlertDialog.Builder(this).setTitle(names[id]+" · sumber").setSingleChoiceItems(options,checked,null).setPositiveButton("Simpan",(d,w)->{AlertDialog a=(AlertDialog)d;int pos=a.getListView().getCheckedItemPosition();getPreferences(0).edit().putBoolean("menu_supplier_"+id,pos==0).apply();message(names[id]+" ditetapkan sebagai "+options[pos]);}).setNegativeButton("Batal",null).show();}
  void chooseMenuPrice(){new AlertDialog.Builder(this).setTitle("Harga menu").setItems(names,(d,id)->editMenuPrice(id)).setNegativeButton("Tutup",null).show();}
  EditText priceField(String label,String value){EditText field=new EditText(this);field.setSingleLine(true);field.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);field.setHint(label);field.setText(value);return field;}
  int parseCents(EditText field){return new java.math.BigDecimal(field.getText().toString().trim()).movePointRight(2).intValueExact();}
  void editMenuPrice(int id){LinearLayout form=col();form.setPadding(dp(18),dp(3),dp(18),0);
    EditText selling=priceField("Harga jualan RM",String.format(Locale.US,"%.2f",prices[id]/100.0));EditText cost=priceField("Harga mentah RM",String.format(Locale.US,"%.2f",costUnit(id)/100.0));EditText pack=new EditText(this);pack.setSingleLine(true);pack.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);pack.setHint("Bilangan unit bagi harga mentah");pack.setText(""+costPack(id));
    add(form,text("Harga jualan (RM)",13,ink,true),-1,-2);add(form,selling,-1,-2);add(form,text("Harga mentah / belian (RM)",13,ink,true),-1,-2);add(form,cost,-1,-2);add(form,text("Bilangan unit bagi harga mentah (nasi: 30)",12,muted,false),-1,-2);add(form,pack,-1,-2);
    new AlertDialog.Builder(this).setTitle("Edit harga · "+names[id]).setView(form).setPositiveButton("Simpan",(d,w)->{try{int sell=parseCents(selling),buy=parseCents(cost),units=Integer.parseInt(pack.getText().toString().trim());if(sell<=0||sell>10000000||buy<0||buy>1000000||units<=0||units>100000)throw new Exception();if(!getPreferences(0).edit().putInt("price_"+id,sell).putInt("cost_"+id,buy).putInt("cost_pack_"+id,units).commit())throw new Exception();prices[id]=sell;draw();message("Harga disimpan. Restock berikutnya akan guna harga mentah baru.");}catch(Exception e){message("Semak harga RM dan bilangan unit.");}}).setNegativeButton("Batal",null).show();}
  void editEmail(){EditText input=new EditText(this);input.setSingleLine(true);input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);input.setText(getPreferences(0).getString("receipt_email",""));input.setHint("nama@gmail.com");
    new AlertDialog.Builder(this).setTitle("Alamat e-mel pada resit").setView(input).setPositiveButton("Simpan",(d,w)->{String value=input.getText().toString().trim();if(!value.isEmpty()&&!android.util.Patterns.EMAIL_ADDRESS.matcher(value).matches()){message("Alamat e-mel tidak sah");return;}getPreferences(0).edit().putString("receipt_email",value).apply();message("E-mel disimpan");}).setNegativeButton("Batal",null).show();}
  void addMenu(){LinearLayout form=col();form.setPadding(dp(18),dp(5),dp(18),0);EditText name=new EditText(this),price=new EditText(this);name.setSingleLine(true);name.setHint("Nama menu");price.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);price.setHint("Harga RM, contoh 7.00");Spinner source=new Spinner(this);source.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"Pembekal","Sendiri"}));add(form,name,-1,-2);add(form,price,-1,-2);add(form,text("Sumber stok",12,muted,true),-1,-2);add(form,source,-1,-2);
    new AlertDialog.Builder(this).setTitle("+ Tambah menu").setView(form).setPositiveButton("Simpan",(d,w)->{try{String title=name.getText().toString().trim();if(title.isEmpty()||title.length()>35)throw new Exception();int cents=new java.math.BigDecimal(price.getText().toString().trim()).movePointRight(2).intValueExact();if(cents<=0||cents>10000000)throw new Exception();JSONObject item=new JSONObject();item.put("name",title);item.put("price",cents);appendEntry("custom_menu",item);names=Arrays.copyOf(names,names.length+1);names[names.length-1]=title;icons=Arrays.copyOf(icons,icons.length+1);icons[icons.length-1]="🍽";prices=Arrays.copyOf(prices,prices.length+1);prices[prices.length-1]=cents;qty=Arrays.copyOf(qty,qty.length+1);getPreferences(0).edit().putBoolean("menu_supplier_"+(names.length-1),source.getSelectedItemPosition()==0).apply();draw();message("Menu baru disimpan. Masukkan stok sebelum jual.");}catch(Exception e){message("Semak nama dan harga menu");}}).setNegativeButton("Batal",null).show();}
  void deleteMenu(){
    ArrayList<Integer> ids=new ArrayList<>();ArrayList<String> labels=new ArrayList<>();
    for(int i=0;i<names.length;i++)if(!getPreferences(0).getBoolean("menu_hidden_"+i,false)){ids.add(i);labels.add(names[i]);}
    if(ids.isEmpty()){message("Tiada menu untuk dipadam");return;}
    new AlertDialog.Builder(this).setTitle("Padam menu").setItems(labels.toArray(new String[0]),(d,which)->{
      int id=ids.get(which);new AlertDialog.Builder(this).setTitle("Padam "+names[id]+"?")
      .setMessage("Menu ini akan dibuang daripada paparan jualan dan stok. Rekod jualan lama dikekalkan supaya laporan tidak rosak.")
      .setPositiveButton("PADAM",(x,w)->{getPreferences(0).edit().putBoolean("menu_hidden_"+id,true).remove("menu_image_"+id).commit();qty[id]=0;cachedStock=null;draw();message("Menu dipadam");})
      .setNegativeButton("Batal",null).show();
    }).setNegativeButton("Batal",null).show();
  }
  void pickMenuImage(){new AlertDialog.Builder(this).setTitle("Gambar untuk menu").setItems(names,(d,id)->{imageItem=id;Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);intent.setType("image/*");intent.addCategory(Intent.CATEGORY_OPENABLE);try{startActivityForResult(intent,PICK_IMAGE);}catch(Exception e){message("Galeri tidak dapat dibuka");}}).show();}
  void today(){today.setText(money(getPreferences(0).getInt("sales_"+date(),0)));}
  void history(){new AlertDialog.Builder(this).setTitle("Rekod & operasi").setItems(new String[]{"Jualan hari ini","Graf & jualan bulanan","Buka jualan / tambah stok","Baki stok","Duit masuk / keluar","Eksport rekod Excel / PDF","No. telefon resit","Tetapan & reset data"},(d,which)->{
    if(which==0)new AlertDialog.Builder(this).setTitle("Rekod hari ini · "+date()).setMessage("Jualan: "+money(getPreferences(0).getInt("sales_"+date(),0))+"\nPesanan selesai: "+getPreferences(0).getInt("orders_"+date(),0)).setPositiveButton("Tutup",null).show();
    else if(which==1)monthly();else if(which==2)stockEntry();else if(which==3)stockBalance();else if(which==4)cashBook();else if(which==5)exportMenu();else if(which==6)editPhone();else settings();
  }).show();}
  void monthly(){Calendar now=Calendar.getInstance();String[] labels=new String[12],keys=new String[12];int[] totals=new int[12];int yearTotal=0;
    for(int i=0;i<12;i++){Calendar c=(Calendar)now.clone();c.add(Calendar.MONTH,i-11);keys[i]=new SimpleDateFormat("yyyy-MM",Locale.US).format(c.getTime());totals[i]=monthTotal(keys[i]);yearTotal+=totals[i];labels[i]=new SimpleDateFormat("MMM yy",new Locale("ms","MY")).format(c.getTime());}
    ScrollView scroll=new ScrollView(this);scroll.setVerticalScrollBarEnabled(false);LinearLayout panel=col();panel.setPadding(dp(16),dp(10),dp(16),dp(12));scroll.addView(panel);
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
    int[] sold=soldBetween(start,end);ScrollView scroll=new ScrollView(this);scroll.setVerticalScrollBarEnabled(false);LinearLayout panel=col();panel.setPadding(dp(18),dp(12),dp(18),dp(14));scroll.addView(panel);
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
  void stockBalance(){String day=selectedStockDay;ScrollView scroll=new ScrollView(this);scroll.setVerticalScrollBarEnabled(false);LinearLayout panel=col();panel.setPadding(dp(14),dp(10),dp(14),dp(14));scroll.addView(panel);add(panel,text("RINGKASAN · "+day,12,muted,true),-1,-2);gap(panel,8);
    int[] soldDay=soldBetween(day,day);for(int i=0;i<names.length;i++){if(getPreferences(0).getBoolean("menu_hidden_"+i,false))continue;LinearLayout c=col();c.setPadding(dp(12),dp(10),dp(12),dp(10));c.setBackground(shape(surface,12));if(unlimited(i)){add(c,text(names[i],15,ink,true),-1,-2);add(c,text("Tanpa had unit · "+soldDay[i]+" hidangan terjual",12,muted,false),-1,-2);}else{int[] st=stockForDay(i,day);LinearLayout r=row();r.addView(text(names[i],15,ink,true),new LinearLayout.LayoutParams(0,-2,1));add(r,text("BAKI "+st[3],16,blue,true),-2,-2);add(c,r,-1,-2);gap(c,5);add(c,text("Awal "+st[0]+"   •   Restock +"+st[1]+"   •   Terjual −"+st[2]+"   •   Rosak −"+st[4],12,muted,false),-1,-2);}add(panel,c,-1,-2);gap(panel,7);}
    new AlertDialog.Builder(this).setTitle("Baki stok · "+day).setView(scroll).setPositiveButton("Tutup",null).setNeutralButton(date().equals(day)?"Tambah stok":"Pilih tarikh",(d,w)->{if(date().equals(day))stockEntry();else chooseStockDay();}).show();}
  void cashBook(){new AlertDialog.Builder(this).setTitle("Duit masuk / keluar").setItems(new String[]{"Lihat rekod bulan ini","Catat duit masuk","Catat duit keluar"},(d,index)->{if(index==0)cashHistory();else cashEntry(index==1);}).show();}
  void cashEntry(boolean incoming){LinearLayout form=col();form.setPadding(dp(20),dp(5),dp(20),0);EditText amount=new EditText(this);amount.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);amount.setHint("Jumlah RM, contoh 10.50");add(form,amount,-1,-2);
    String[] categories=incoming?new String[]{"Modal tambahan","Lain-lain"}:new String[]{"Sewa","Minyak kereta","Barang plastik","Sate mentah","Arang","Bahan lain","Lain-lain"};
    Spinner category=new Spinner(this);ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,categories);adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);category.setAdapter(adapter);add(form,category,-1,48);
    EditText note=new EditText(this);note.setSingleLine(true);note.setHint("Catatan tambahan (jika ada)");add(form,note,-1,-2);
    new AlertDialog.Builder(this).setTitle(incoming?"Catat duit masuk":"Catat duit keluar").setView(form).setPositiveButton("Simpan",(d,w)->{try{java.math.BigDecimal rm=new java.math.BigDecimal(amount.getText().toString().trim());int cents=rm.movePointRight(2).intValueExact();if(cents<=0)throw new Exception();JSONObject entry=new JSONObject();entry.put("time",timestamp());entry.put("month",date().substring(0,7));entry.put("amount",incoming?cents:-cents);entry.put("category",categories[category.getSelectedItemPosition()]);entry.put("note",note.getText().toString().trim());appendEntry("cash_entries",entry);draw();message("Catatan disimpan");}catch(Exception e){message("Masukkan jumlah RM yang sah");}}).setNegativeButton("Batal",null).show();}
  void cashHistory(){String month=selectedMonth,day=selectedCashDay;JSONArray list=entries("cash_entries");int incoming=0,outgoing=0;ScrollView scroll=new ScrollView(this);scroll.setVerticalScrollBarEnabled(false);LinearLayout panel=col();panel.setPadding(dp(14),dp(10),dp(14),dp(14));scroll.addView(panel);String period=cashDailyMode?day:monthLabel(month);add(panel,text((cashDailyMode?"HARIAN · ":"BULANAN · ")+period,12,muted,true),-1,-2);gap(panel,8);ArrayList<JSONObject> shown=new ArrayList<>();
    for(int i=list.length()-1;i>=0;i--){JSONObject e=list.optJSONObject(i);if(e==null||legacySupplierRestock(e))continue;boolean match=cashDailyMode?day.equals(entryDay(e)):month.equals(e.optString("month"));if(!match)continue;int value=e.optInt("amount");if(value>0)incoming+=value;else outgoing-=value;shown.add(e);}
    LinearLayout totals=row();metric(totals,"MASUK",money(incoming),blue,null);metric(totals,"KELUAR",money(outgoing),ink,null);add(panel,totals,-1,-2);gap(panel,10);add(panel,text("BAKI DUIT OVERALL  "+money(totalMoneyBalance()),14,gold,true),-1,-2);gap(panel,12);if(shown.isEmpty())add(panel,text("Belum ada catatan untuk tempoh ini.",13,muted,false),-1,-2);
    for(JSONObject e:shown){int value=e.optInt("amount");LinearLayout card=col();card.setPadding(dp(12),dp(10),dp(12),dp(10));card.setBackground(shape(surface,11));LinearLayout r=row();r.addView(text(e.optString("time"),11,muted,true),new LinearLayout.LayoutParams(0,-2,1));add(r,text((value>0?"+ ":"− ")+money(Math.abs(value)),14,value>0?blue:0xffd45a55,true),-2,-2);add(card,r,-1,-2);gap(card,4);add(card,text(e.optString("category","Catatan"),13,ink,true),-1,-2);String note=e.optString("note");if(!note.isEmpty()&&!note.equals(e.optString("category")))add(card,text(note,12,muted,false),-1,-2);add(panel,card,-1,-2);gap(panel,7);}new AlertDialog.Builder(this).setTitle("Duit masuk / keluar").setView(scroll).setPositiveButton("Tutup",null).show();}
  void settings(){new AlertDialog.Builder(this).setTitle("Tetapan data").setItems(new String[]{"No. telefon resit","Reset stok sahaja","Reset semua data (jualan, stok, duit & tetapan)"},(d,i)->{if(i==0)editPhone();else resetData(i==2);}).show();}
  void resetData(boolean all){String label=all?"SEMUA data jualan, stok, duit dan tetapan":"stok dan baki stok";
    new AlertDialog.Builder(this).setTitle("Padam "+label+"?").setMessage(all?"Rekod tidak boleh dipulihkan selepas dipadam. Eksport rekod sebelum teruskan.":"Rekod jualan, duit, nombor telefon dan printer masih disimpan. Stok akan kembali 0.")
      .setPositiveButton("Teruskan",(d,w)->new AlertDialog.Builder(this).setTitle("Sahkan reset").setMessage("Anda pasti mahu padam "+label+"?")
        .setPositiveButton("Ya, padam",(dd,ww)->{if(all)getPreferences(0).edit().clear().commit();else resetStockRecords();Arrays.fill(qty,0);if(all){loadMenu();activePage=4;}draw();message("Reset selesai");})
        .setNegativeButton("Batal",null).show()).setNegativeButton("Batal",null).show();}
  void exportMenu(){int current=Calendar.getInstance().get(Calendar.YEAR);String[] years=new String[2];years[0]="Tahun "+current;years[1]="Tahun "+(current-1);
    new AlertDialog.Builder(this).setTitle("Eksport rekod jualan").setItems(years,(d,i)->{exportYear=current-i;
      new AlertDialog.Builder(this).setTitle("Tahun "+exportYear).setItems(new String[]{"Excel (CSV)","PDF"},(dialog,kind)->{
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(kind==0?"text/csv":"application/pdf");intent.putExtra(Intent.EXTRA_TITLE,"WarisanPOS-Jualan-"+exportYear+(kind==0?".csv":".pdf"));
        try{startActivityForResult(intent,kind==0?EXPORT_CSV:EXPORT_PDF);}catch(Exception e){message("Telefon tidak boleh membuka pilihan simpan fail");}
      }).show();}).show();}
  @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);
  if (request == RC_SIGN_IN) {
    Task<GoogleSignInAccount> task =
            GoogleSignIn.getSignedInAccountFromIntent(data);

    try {
        GoogleSignInAccount account =
                task.getResult(ApiException.class);

        AuthCredential credential =
                GoogleAuthProvider.getCredential(account.getIdToken(), null);

        firebaseAuth.signInWithCredential(credential)
                .addOnCompleteListener(this, authTask -> {
                    if (authTask.isSuccessful()) {
                        new AlertDialog.Builder(this)
                                .setTitle("Berjaya")
                                .setMessage("Google berjaya disambungkan")
                                .setPositiveButton("OK", null)
                                .show();
                        draw();
                    } else {
                        new AlertDialog.Builder(this)
                                .setTitle("Firebase Error")
                                .setMessage(authTask.getException() == null
                                        ? "Unknown error"
                                        : authTask.getException().toString())
                                .setPositiveButton("OK", null)
                                .show();
                    }
                });

    } catch (ApiException e) {
        new AlertDialog.Builder(this)
                .setTitle("Google Sign-In Error")
                .setMessage("Error code: " + e.getStatusCode()
                        + "\n\n" + e.getMessage())
                .setPositiveButton("OK", null)
                .show();
    }
    return;
  }                                                                              
    if(result!=RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();
    if(request==IMPORT_BACKUP){readBackup(uri);return;}
    if(request==PICK_IMAGE){try{getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);if(imageItem<0||imageItem>=names.length)return;getPreferences(0).edit().putString("menu_image_"+imageItem,uri.toString()).apply();draw();message("Gambar menu disimpan");}catch(Exception e){message("Tidak dapat menyimpan gambar ini");}return;}
    try(OutputStream out=getContentResolver().openOutputStream(uri)){
      if(out==null)throw new IOException("Gagal membuka fail");
      if(request==EXPORT_BACKUP){out.write(backupJson().toString(2).getBytes(StandardCharsets.UTF_8));message("Backup berjaya disimpan");return;}
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
    new AlertDialog.Builder(this).setTitle("Bayaran "+money(due)).setItems(new String[]{"Tunai","QR / DuitNow"},(dialog,index)->{if(index==0)cashPayment(due);else showPaymentQr(due);}).setNegativeButton("Batal",null).show();}
  int cashCents(EditText input){try{return new java.math.BigDecimal(input.getText().toString().trim()).movePointRight(2).intValueExact();}catch(Exception e){return -1;}}
  void cashPayment(int due){LinearLayout form=col();form.setPadding(dp(20),dp(3),dp(20),0);
    add(form,text("JUMLAH BELIAN  "+money(due),16,blue,true),-1,-2);gap(form,10);
    EditText received=new EditText(this);received.setSingleLine(true);received.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);received.setHint("Duit pelanggan beri · contoh 50.00");add(form,received,-1,-2);gap(form,10);
    TextView change=text("Masukkan jumlah tunai diterima",17,muted,true);add(form,change,-1,-2);
    AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Bayaran tunai").setView(form).setPositiveButton("Bayaran diterima",null).setNegativeButton("Batal",null).create();
    dialog.setOnShowListener(v->{Button next=dialog.getButton(AlertDialog.BUTTON_POSITIVE);next.setEnabled(false);
      received.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){int tendered=cashCents(received);boolean enough=tendered>=due;next.setEnabled(enough);change.setText(enough?"BAKI PULANGAN  "+money(tendered-due):tendered<0?"Masukkan jumlah tunai diterima":"Tunai belum cukup · kurang "+money(due-tendered));change.setTextColor(enough?blue:muted);}public void afterTextChanged(android.text.Editable value){}});
      next.setOnClickListener(view->{int tendered=cashCents(received);if(tendered<due){message("Tunai diterima tidak mencukupi");return;}dialog.dismiss();completePayment("Tunai",due,tendered);});});dialog.show();}
  void showPaymentQr(int due){ScrollView scroll=new ScrollView(this);scroll.setVerticalScrollBarEnabled(false);scroll.setFillViewport(false);ImageView image=new ImageView(this);image.setImageResource(R.drawable.qr_frozen_ld);image.setAdjustViewBounds(true);image.setScaleType(ImageView.ScaleType.FIT_CENTER);scroll.addView(image,new ScrollView.LayoutParams(-1,-2));
    new AlertDialog.Builder(this).setTitle("QR DuitNow · Frozen LD").setMessage("Jumlah: "+money(due)+"\nTunjukkan QR ini kepada pelanggan. Sahkan selepas bayaran diterima.").setView(scroll)
      .setPositiveButton("Bayaran diterima",(d,w)->completePayment("QR / DuitNow",due,due)).setNegativeButton("Batal",null).show();}
  String line(String left,String right){int spaces=Math.max(1,32-left.length()-right.length());return left+String.format(Locale.US,"%"+spaces+"s","")+right+"\n";}
  String receipt(String method,int due,int order,int tendered){StringBuilder b=new StringBuilder();
    String shopName=getPreferences(0).getString("shop_name","WARISAN FROZEN");
    b.append(centerReceipt(shopName));
    String phone=getPreferences(0).getString("receipt_phone","");if(!phone.isEmpty())b.append("       Tel: ").append(phone).append("\n");
    String email=getPreferences(0).getString("receipt_email","");if(!email.isEmpty())b.append(email).append("\n");
    b.append("--------------------------------\n").append(line("No. resit",String.format(Locale.US,"#%04d",order)))
      .append(line("Tarikh",new SimpleDateFormat("dd/MM/yy HH:mm",Locale.US).format(new Date()))).append("--------------------------------\n");
    for(int i=0;i<qty.length;i++)if(qty[i]>0){b.append(names[i]).append("\n");b.append(line("  "+qty[i]+" x "+money(prices[i]),money(qty[i]*prices[i])));}
    b.append("--------------------------------\n").append(line("JUMLAH",money(due))).append(line("BAYAR",method));
    if("Tunai".equals(method))b.append(line("TUNAI DITERIMA",money(tendered))).append(line("BAKI PULANGAN",money(tendered-due)));
    return b
      .append("================================\n       TERIMA KASIH!\n   Sila datang lagi.\n").append(ownerReceipt()).append("\n\n").toString();}
  void confirm(String method,int due){completePayment(method,due,due);}
  void confirm(String method,int due,int tendered){completePayment(method,due,tendered);}
  void completePayment(String method,int due,int tendered){
      int order=getPreferences(0).getInt("orders_"+date(),0)+1;
      int[] purchased=Arrays.copyOf(qty,qty.length);
      String printed=receipt(method,due,order,tendered);
      try{JSONObject sale=new JSONObject();sale.put("time",timestamp());JSONArray soldItems=new JSONArray();for(int amount:purchased)soldItems.put(amount);sale.put("qty",soldItems);JSONArray sales=entries("stock_sales");sales.put(sale);
        if(!getPreferences(0).edit().putInt("sales_"+date(),getPreferences(0).getInt("sales_"+date(),0)+due).putInt("orders_"+date(),order).putString("stock_sales",sales.toString()).commit())throw new IOException("Gagal simpan");
      }catch(Exception e){message("Bayaran gagal direkod. Semak simpanan telefon.");return;}
      playPaymentSound();
      Arrays.fill(qty,0);draw();
      previewReceipt(printed,purchased,method,due,order,tendered);
  }
  void receiptRule(LinearLayout sheet){View rule=new View(this);rule.setBackgroundColor(0xffe5e7e2);LinearLayout.LayoutParams lp=params(-1,1);lp.setMargins(0,dp(13),0,dp(13));sheet.addView(rule,lp);}
  void receiptRow(LinearLayout sheet,String label,String value,boolean highlight){
    LinearLayout r=row();int receiptText=0xff26384c,receiptMuted=0xff66788a;TextView left=text(label,highlight?17:13,highlight?blue:receiptMuted,highlight);TextView right=text(value,highlight?20:13,highlight?blue:receiptText,true);
    r.addView(left,new LinearLayout.LayoutParams(0,-2,1));add(r,right,-2,-2);add(sheet,r,-1,-2);
  }
  void previewReceipt(String printed,int[] purchased,String method,int due,int order,int tendered){
    ScrollView scroll=new ScrollView(this);scroll.setVerticalScrollBarEnabled(false);
    int receiptText=0xff26384c,receiptMuted=0xff66788a;
    LinearLayout sheet=col();sheet.setPadding(dp(18),dp(12),dp(18),dp(14));sheet.setBackgroundColor(Color.WHITE);scroll.addView(sheet);
    ImageView logo=new ImageView(this);logo.setImageResource(R.drawable.warisan_logo);makeCircle(logo);
    LinearLayout logoRow=row();logoRow.setGravity(Gravity.CENTER);add(logoRow,logo,74,74);add(sheet,logoRow,-1,-2);gap(sheet,5);
    TextView brand=text(getPreferences(0).getString("shop_name","WARISAN FROZEN"),17,blue,true);brand.setGravity(Gravity.CENTER);add(sheet,brand,-1,-2);
    String phone=getPreferences(0).getString("receipt_phone","");
    TextView contact=text(phone.isEmpty()?"No. telefon belum diisi":"Tel: "+phone,11,receiptMuted,false);contact.setGravity(Gravity.CENTER);add(sheet,contact,-1,-2);
    String email=getPreferences(0).getString("receipt_email","");if(!email.isEmpty()){TextView mail=text(email,11,receiptMuted,false);mail.setGravity(Gravity.CENTER);add(sheet,mail,-1,-2);}
    receiptRule(sheet);
    receiptRow(sheet,"RESIT BAYARAN",String.format(Locale.US,"#%04d",order),false);gap(sheet,4);
    receiptRow(sheet,"Tarikh",new SimpleDateFormat("dd/MM/yyyy HH:mm",Locale.US).format(new Date()),false);
    receiptRule(sheet);
    for(int i=0;i<purchased.length;i++)if(purchased[i]>0){receiptRow(sheet,names[i],money(prices[i]*purchased[i]),false);
      TextView details=text(purchased[i]+" × "+money(prices[i]),11,receiptMuted,false);add(sheet,details,-1,-2);gap(sheet,10);}
    receiptRule(sheet);receiptRow(sheet,"JUMLAH",money(due),true);gap(sheet,8);
    receiptRow(sheet,"Kaedah bayaran",method,false);if("Tunai".equals(method)){gap(sheet,6);receiptRow(sheet,"Tunai diterima",money(tendered),false);gap(sheet,6);receiptRow(sheet,"BAKI PULANGAN",money(tendered-due),true);}receiptRule(sheet);
    TextView thanks=text("Terima kasih! Sila datang lagi.",12,receiptMuted,false);thanks.setGravity(Gravity.CENTER);add(sheet,thanks,-1,-2);
    String owner=ownerDisplay();if(!owner.isEmpty()){gap(sheet,8);TextView owned=text("DIMILIKI OLEH : "+owner,10,receiptMuted,true);owned.setGravity(Gravity.CENTER);add(sheet,owned,-1,-2);}
    new AlertDialog.Builder(this).setTitle("Semak resit").setView(scroll)
      .setPositiveButton("Cetak resit",(dialog,button)->print(printed))
      .setNeutralButton("Share",(dialog,button)->shareReceiptImage(sheet,"resit-customer"))
      .setNegativeButton("Tutup",null).show();
  }
  void shareReceiptImage(View receiptView,String prefix){
    try{
      int width=receiptView.getWidth();
      if(width<=0)width=dp(560);
      int wSpec=View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY);
      int hSpec=View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED);
      receiptView.measure(wSpec,hSpec);
      int height=Math.max(1,receiptView.getMeasuredHeight());
      receiptView.layout(0,0,width,height);
      Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
      Canvas canvas=new Canvas(bitmap);canvas.drawColor(Color.WHITE);receiptView.draw(canvas);
      ContentValues values=new ContentValues();
      values.put(MediaStore.Images.Media.DISPLAY_NAME,prefix+"-"+new SimpleDateFormat("yyyyMMdd-HHmmss",Locale.US).format(new Date())+".png");
      values.put(MediaStore.Images.Media.MIME_TYPE,"image/png");
      if(Build.VERSION.SDK_INT>=29)values.put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/WarisanPOS");
      Uri uri=getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);
      if(uri==null)throw new IOException("Gagal menyediakan gambar resit");
      try(OutputStream out=getContentResolver().openOutputStream(uri)){if(out==null||!bitmap.compress(Bitmap.CompressFormat.PNG,100,out))throw new IOException("Gagal menyimpan gambar resit");}
      Intent share=new Intent(Intent.ACTION_SEND);share.setType("image/png");share.putExtra(Intent.EXTRA_STREAM,uri);share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
      startActivity(Intent.createChooser(share,"Share resit"));
    }catch(Exception e){message("Gagal share resit. Cuba semula.");}
  }
  String centerReceipt(String value){if(value==null)value="";value=value.trim();if(value.length()>32)value=value.substring(0,32);int left=Math.max(0,(32-value.length())/2);return String.format(Locale.US,"%"+left+"s%s\n","",value);}
  String ownerDisplay(){String company=getPreferences(0).getString("company_name","").trim();String reg=getPreferences(0).getString("company_reg","").trim();if(company.isEmpty()&&reg.isEmpty())return "";if(company.isEmpty())return reg;if(reg.isEmpty())return company;return company+" ("+reg+")";}
  String ownerReceipt(){String owner=ownerDisplay();return owner.isEmpty()?"":"DIMILIKI OLEH : "+owner+"\n";}
  void editShopInfo(String key,String title,String fallback){EditText input=new EditText(this);input.setSingleLine(!"company_hq".equals(key));input.setText(getPreferences(0).getString(key,fallback));input.setHint(title);new AlertDialog.Builder(this).setTitle(title).setView(input).setPositiveButton("Simpan",(d,w)->{getPreferences(0).edit().putString(key,input.getText().toString().trim()).apply();message(title+" disimpan");}).setNegativeButton("Batal",null).show();}
  void previewTestReceipt(boolean directPrint){int[] sample=new int[names.length];if(sample.length>0)sample[0]=10;int due=sample.length>0?prices[0]*10:0;String printed=receipt("Tunai",due,1,due);if(directPrint){print(printed);return;}previewReceipt(printed,sample,"Tunai",due,1,due);}
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

  int damagedQty(int id){int n=0;JSONArray list=entries("stock_damage");for(int i=0;i<list.length();i++){JSONObject e=list.optJSONObject(i);if(e!=null&&!e.optBoolean("stockReset",false)&&e.optInt("item",-1)==id)n+=e.optInt("qty");}return n;}
  int damageTotal(String month){int n=0;JSONArray list=entries("stock_damage");for(int i=0;i<list.length();i++){JSONObject e=list.optJSONObject(i);if(e!=null&&entryDay(e).startsWith(month+"-"))n+=e.optInt("cost");}return n;}
  void resetStockRecords(){try{JSONArray losses=entries("stock_damage");for(int i=0;i<losses.length();i++)losses.getJSONObject(i).put("stockReset",true);getPreferences(0).edit().remove("stock_entries").remove("stock_sales").putString("stock_damage",losses.toString()).commit();}catch(Exception e){message("Reset gagal");}}
  void damageEntry(int id){if(costUnit(id)<=0){message("Tetapkan harga mentah dahulu untuk kira kerugian");editMenuPrice(id);return;}if(stock(id)==0){message("Tiada stok untuk direkod rosak");return;}LinearLayout form=col();form.setPadding(dp(18),dp(6),dp(18),0);
    add(form,text("Baki: "+stock(id)+" unit · Kos "+money(costUnit(id))+" / "+costPack(id)+" unit",13,ink,true),-1,-2);
    EditText units=new EditText(this);units.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);units.setHint("Bilangan stok rosak");add(form,units,-1,-2);
    EditText reason=new EditText(this);reason.setHint("Sebab: basi / jatuh / terbakar");add(form,reason,-1,-2);
    TextView preview=text("Kerugian ikut harga mentah semasa",13,0xffb54743,true);add(form,preview,-1,-2);
    units.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void afterTextChanged(android.text.Editable e){}public void onTextChanged(CharSequence s,int a,int b,int c){try{int n=Integer.parseInt(s.toString());preview.setText(n>0&&n<=stock(id)?"Nilai stok rosak: "+money(restockCost(id,n)):"Bilangan mesti 1 hingga "+stock(id));}catch(Exception e){preview.setText("Masukkan bilangan stok rosak");}}});
    AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Stok rosak · "+names[id]).setView(form).setPositiveButton("Simpan",null).setNegativeButton("Batal",null).create();
    dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view->{try{int n=Integer.parseInt(units.getText().toString().trim());String note=reason.getText().toString().trim();if(n<=0||n>stock(id)||note.isEmpty()){message("Semak bilangan dan isi sebab rosak");return;}
      JSONObject e=new JSONObject();e.put("time",timestamp());e.put("item",id);e.put("name",names[id]);e.put("qty",n);e.put("cost",restockCost(id,n));e.put("unitCost",costUnit(id));e.put("pack",costPack(id));e.put("note",note);JSONArray list=entries("stock_damage");list.put(e);
      if(!getPreferences(0).edit().putString("stock_damage",list.toString()).commit())throw new IOException();qty[id]=Math.min(qty[id],stock(id));dialog.dismiss();draw();message("Stok rosak direkod · kerugian "+money(e.optInt("cost")));
    }catch(Exception e){message("Gagal simpan. Semak bilangan dan simpanan telefon.");}}));dialog.show();}
  void damageHistory(){JSONArray list=entries("stock_damage");ScrollView scroll=new ScrollView(this);scroll.setVerticalScrollBarEnabled(false);LinearLayout panel=col();panel.setPadding(dp(14),dp(10),dp(14),dp(14));scroll.addView(panel);boolean dayMode=activePage==1||cashDailyMode;String day=activePage==1?selectedStockDay:selectedCashDay;String month=selectedMonth;int totalLoss=0,countLoss=0;
    for(int i=list.length()-1;i>=0;i--){JSONObject e=list.optJSONObject(i);if(e==null||e.optBoolean("stockReset",false))continue;String d=entryDay(e);if(dayMode?!day.equals(d):!d.startsWith(month+"-"))continue;countLoss++;totalLoss+=e.optInt("cost");LinearLayout card=col();card.setPadding(dp(12),dp(10),dp(12),dp(10));card.setBackground(shape(surface,11));LinearLayout r=row();r.addView(text(e.optString("name","Stok"),14,ink,true),new LinearLayout.LayoutParams(0,-2,1));add(r,text(money(e.optInt("cost")),14,0xffd45a55,true),-2,-2);add(card,r,-1,-2);add(card,text(e.optString("time")+"  ·  "+e.optInt("qty")+" unit",11,muted,true),-1,-2);String note=e.optString("note");if(!note.isEmpty())add(card,text(note,12,muted,false),-1,-2);add(panel,card,-1,-2);gap(panel,7);}
    LinearLayout summary=col();summary.setPadding(dp(12),dp(10),dp(12),dp(10));summary.setBackground(shape(0xff2b3f55,11));add(summary,text("JUMLAH KERUGIAN",11,muted,true),-1,-2);add(summary,text(money(totalLoss),21,totalLoss>0?0xffd45a55:blue,true),-1,-2);panel.addView(summary,0);if(countLoss==0){TextView empty=text("Tiada stok rosak untuk tempoh ini.",13,muted,false);empty.setPadding(0,dp(12),0,0);add(panel,empty,-1,-2);}new AlertDialog.Builder(this).setTitle("Stok rosak · "+(dayMode?day:monthLabel(month))).setView(scroll).setPositiveButton("Tutup",null).show();}
  int[][] cashSeries(String month){int days=daysInMonth(month);int[][] values=new int[3][days];for(int d=0;d<days;d++)values[0][d]=getPreferences(0).getInt("sales_"+month+String.format(Locale.US,"-%02d",d+1),0);
    for(String key:new String[]{"cash_entries","stock_damage"}){JSONArray list=entries(key);for(int i=0;i<list.length();i++){JSONObject e=list.optJSONObject(i);if(e==null||(key.equals("cash_entries")&&legacySupplierRestock(e)))continue;String day=entryDay(e);if(!day.startsWith(month+"-"))continue;try{int d=Integer.parseInt(day.substring(8,10))-1;if(d<0||d>=days)continue;if(key.equals("stock_damage"))values[2][d]+=e.optInt("cost");else{int n=e.optInt("amount");values[n>=0?0:1][d]+=Math.abs(n);}}catch(Exception ignored){}}}return values;}
  void cashDaily(String month){int[][] v=cashSeries(month);StringBuilder b=new StringBuilder();for(int d=0;d<v[0].length;d++)if(v[0][d]!=0||v[1][d]!=0||v[2][d]!=0)b.append(d+1).append(" ").append(monthLabel(month)).append("\nMasuk ").append(money(v[0][d])).append(" · Keluar ").append(money(v[1][d])).append("\nRosak ").append(money(v[2][d])).append(" · Baki ").append(money(v[0][d]-v[1][d])).append("\n\n");new AlertDialog.Builder(this).setTitle("Pecahan harian").setMessage(b.length()==0?"Belum ada rekod.":b.toString()).setPositiveButton("Tutup",null).show();}
  class CashDonutChart extends View {
    final int[] totals=new int[3];
    final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
    final int[] colors={0xff218364,0xffbd473e,0xffb17916};
    final String[] labels={"Masuk","Keluar","Rosak"};

    CashDonutChart(String month){
      super(MainActivity.this);
      int[][] v=cashSeries(month);
      for(int k=0;k<3;k++)for(int n:v[k])totals[k]+=Math.abs(n);
      setContentDescription("Graf bulat duit masuk, duit keluar dan stok rosak. Nilai tepat tersedia dalam Pecahan harian graf.");
    }

    @Override protected void onDraw(Canvas c){
      super.onDraw(c);
      float w=getWidth(),h=getHeight();
      float cx=w*.50f,cy=dp(118),outer=dp(78),stroke=dp(27);
      float sum=totals[0]+totals[1]+totals[2];

      p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(stroke);p.setStrokeCap(Paint.Cap.BUTT);
      android.graphics.RectF oval=new android.graphics.RectF(cx-outer,cy-outer,cx+outer,cy+outer);
      if(sum<=0){
        p.setColor(0xffd8dee8);c.drawArc(oval,-90,360,false,p);
      }else{
        float angle=-90f;
        for(int k=0;k<3;k++){
          if(totals[k]<=0)continue;
          float sweep=360f*totals[k]/sum;
          p.setColor(colors[k]);c.drawArc(oval,angle,sweep,false,p);angle+=sweep;
        }
      }

      p.setStyle(Paint.Style.FILL);p.setTextAlign(Paint.Align.CENTER);p.setTypeface(Typeface.DEFAULT_BOLD);
      p.setColor(ink);p.setTextSize(dp(12));c.drawText("ALIRAN DUIT",cx,cy-dp(5),p);
      p.setTypeface(Typeface.DEFAULT);p.setColor(muted);p.setTextSize(dp(10));c.drawText("bulan dipilih",cx,cy+dp(13),p);

      float y=h-dp(35);float slot=w/3f;
      for(int k=0;k<3;k++){
        float x=slot*k+slot/2f;
        p.setColor(colors[k]);c.drawCircle(x-dp(30),y-dp(9),dp(4),p);
        p.setTextAlign(Paint.Align.LEFT);p.setTypeface(Typeface.DEFAULT_BOLD);p.setTextSize(dp(10));c.drawText(labels[k],x-dp(21),y-dp(5),p);
        p.setColor(ink);p.setTypeface(Typeface.DEFAULT);p.setTextSize(dp(10));c.drawText(money(totals[k]),x-dp(30),y+dp(13),p);
      }
    }
  }
  void backupPicker(boolean restore){Intent intent=new Intent(restore?Intent.ACTION_OPEN_DOCUMENT:Intent.ACTION_CREATE_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType(restore?"*/*":"application/json");if(!restore)intent.putExtra(Intent.EXTRA_TITLE,"WarisanPOS-Backup-"+date()+".json");try{startActivityForResult(intent,restore?IMPORT_BACKUP:EXPORT_BACKUP);}catch(Exception e){message("Pilihan fail tidak dapat dibuka");}}
  JSONObject backupJson()throws JSONException{JSONObject root=new JSONObject(),data=new JSONObject();root.put("app","my.warisan.pos");root.put("format",1);root.put("created",timestamp());for(Map.Entry<String,?> e:getPreferences(0).getAll().entrySet()){Object value=e.getValue();JSONObject item=new JSONObject();String type=value instanceof Integer?"int":value instanceof Long?"long":value instanceof Float?"float":value instanceof Boolean?"boolean":value instanceof Set?"set":"string";item.put("type",type);item.put("value",value instanceof Set?new JSONArray((Set<?>)value):value);data.put(e.getKey(),item);}root.put("preferences",data);return root;}
  void syncToCloud(){
  FirebaseUser user=firebaseAuth.getCurrentUser();

  if(user==null){
    message("Sila sambungkan akaun Google dahulu");
    return;
  }

  if(cloudSyncBusy)return;
  cloudSyncBusy=true;

  try{
    Map<String,Object> cloudData=new HashMap<>();
    cloudData.put("backup",backupJson().toString());
    cloudData.put("updatedAt",System.currentTimeMillis());

    firestore.collection("users")
      .document(user.getUid())
      .collection("warisanpos")
      .document("current")
      .set(cloudData,SetOptions.merge())
      .addOnSuccessListener(v->{
        cloudSyncBusy=false;
        message("Data berjaya sync ke Google");
      })
      .addOnFailureListener(e->{
        cloudSyncBusy=false;
        message("Sync gagal: "+e.getMessage());
      });

  }catch(Exception e){
    cloudSyncBusy=false;
    message("Sync gagal: "+e.getMessage());
  }
  }void restoreFromCloud(){
    FirebaseUser user=firebaseAuth.getCurrentUser();

    if(user==null){
      message("Sila sambungkan akaun Google dahulu");
      return;
    }

    if(cloudSyncBusy)return;
    cloudSyncBusy=true;

    firestore.collection("users")
      .document(user.getUid())
      .collection("warisanpos")
      .document("current")
      .get()
      .addOnSuccessListener(doc->{
        cloudSyncBusy=false;

        if(!doc.exists()){
          message("Backup Google belum ada");
          return;
        }

        String backup=doc.getString("backup");
        if(backup==null || backup.trim().isEmpty()){
          message("Backup Google kosong");
          return;
        }

        try{
          JSONObject root=new JSONObject(backup);
          JSONObject data;

          // Format backup WarisanPOS semasa:
          // {app, format, created, preferences:{...}}
          if(root.has("preferences") && root.opt("preferences") instanceof JSONObject){
            data=root.getJSONObject("preferences");
          }else{
            // Sokongan backup lama yang menyimpan preferences terus di root.
            data=root;
            data.remove("app");
            data.remove("version");
            data.remove("createdAt");
            data.remove("format");
            data.remove("created");
          }

          validateBackup(data);

          new AlertDialog.Builder(this)
            .setTitle("Pulihkan dari Google?")
            .setMessage("Data semasa akan digantikan dengan backup Google. Salinan data semasa akan disimpan dahulu dalam telefon.")
            .setPositiveButton("Pulihkan",(d,w)->restoreBackup(data))
            .setNegativeButton("Batal",null)
            .show();

        }catch(Exception e){
          message("Gagal pulihkan backup: "+e.getMessage());
        }
      })
      .addOnFailureListener(e->{
        cloudSyncBusy=false;
        message("Gagal ambil backup: "+e.getMessage());
      });
  }
  void readBackup(Uri uri){try(InputStream in=getContentResolver().openInputStream(uri)){if(in==null)throw new IOException();java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();byte[] bytes=new byte[8192];int n;while((n=in.read(bytes))!=-1){if(out.size()+n>32*1024*1024)throw new IOException("Fail terlalu besar");out.write(bytes,0,n);}JSONObject root=new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8));if(!"my.warisan.pos".equals(root.getString("app"))||root.getInt("format")!=1)throw new IOException("Format backup tidak serasi");JSONObject data=root.getJSONObject("preferences");validateBackup(data);new AlertDialog.Builder(this).setTitle("Pulihkan backup?").setMessage("Backup: "+root.optString("created")+"\nData semasa akan digantikan dengan backup ini. Salinan sebelum pulih disimpan dalam telefon. Gambar menu mungkin perlu dipilih semula jika berpindah telefon.").setPositiveButton("Pulihkan",(d,w)->restoreBackup(data)).setNegativeButton("Batal",null).show();}catch(Exception e){message("Backup tidak sah. Data semasa tidak diubah.");}}
  void validateBackup(JSONObject data) throws Exception {
    if(data==null)throw new Exception("Backup kosong");

    Iterator<String> keys=data.keys();
    while(keys.hasNext()){
      String key=keys.next();
      Object raw=data.opt(key);

      if(raw==null || raw==JSONObject.NULL)continue;
      if(!(raw instanceof JSONObject))
        throw new Exception("Format "+key+" tidak sah");

      JSONObject item=(JSONObject)raw;
      String type=item.optString("type","");
      if(type.isEmpty())
        throw new Exception("No value for type pada "+key);

      if(!item.has("value"))
        throw new Exception("No value pada "+key);

      Object value=item.opt("value");

      if("int".equals(type)){
        if(!(value instanceof Number))Integer.parseInt(String.valueOf(value));
      }else if("long".equals(type)){
        if(!(value instanceof Number))Long.parseLong(String.valueOf(value));
      }else if("float".equals(type)){
        Float.parseFloat(String.valueOf(value));
      }else if("boolean".equals(type)){
        if(!(value instanceof Boolean) &&
           !"true".equalsIgnoreCase(String.valueOf(value)) &&
           !"false".equalsIgnoreCase(String.valueOf(value)))
          throw new Exception("Boolean "+key+" tidak sah");
      }else if("string".equals(type)){
        if(value==JSONObject.NULL)throw new Exception("String "+key+" kosong");
      }else if("set".equals(type)){
        if(!(value instanceof JSONArray))
          throw new Exception("Set "+key+" tidak sah");
      }else{
        throw new Exception("Jenis "+type+" tidak disokong");
      }

      // Lima rekod utama disimpan sebagai JSON array dalam String.
      if(("stock_entries".equals(key) ||
          "stock_sales".equals(key) ||
          "stock_damage".equals(key) ||
          "cash_entries".equals(key) ||
          "custom_menu".equals(key)) && "string".equals(type)){
        new JSONArray(String.valueOf(value));
      }
    }
  }
  void restoreBackup(JSONObject data){
    try{
      validateBackup(data);

      // Simpan snapshot data semasa sebelum overwrite.
      try(java.io.FileOutputStream out=openFileOutput("before-restore.json",MODE_PRIVATE)){
        out.write(backupJson().toString().getBytes(StandardCharsets.UTF_8));
        out.getFD().sync();
      }

      android.content.SharedPreferences.Editor editor=getPreferences(0).edit().clear();
      Iterator<String> keys=data.keys();

      while(keys.hasNext()){
        String key=keys.next();
        JSONObject e=data.getJSONObject(key);
        String type=e.getString("type");
        Object value=e.opt("value");

        switch(type){
          case "int":
            editor.putInt(key,value instanceof Number?((Number)value).intValue():Integer.parseInt(String.valueOf(value)));
            break;
          case "long":
            editor.putLong(key,value instanceof Number?((Number)value).longValue():Long.parseLong(String.valueOf(value)));
            break;
          case "float":
            editor.putFloat(key,Float.parseFloat(String.valueOf(value)));
            break;
          case "boolean":
            editor.putBoolean(key,value instanceof Boolean?(Boolean)value:Boolean.parseBoolean(String.valueOf(value)));
            break;
          case "string":
            editor.putString(key,value==JSONObject.NULL?"":String.valueOf(value));
            break;
          case "set":
            Set<String> values=new HashSet<>();
            JSONArray a=e.getJSONArray("value");
            for(int i=0;i<a.length();i++)values.add(a.getString(i));
            editor.putStringSet(key,values);
            break;
          default:
            throw new IOException("Jenis data tidak disokong: "+type);
        }
      }

      if(!editor.commit())throw new IOException("Gagal menulis data");
      loadMenu();
      cachedStock=null;
      Arrays.fill(qty,0);
      draw();
      message("Backup berjaya dipulihkan");
    }catch(Exception e){
      message("Pemulihan gagal: "+e.getMessage());
    }
  }

  void snapshotBeforeUpdate(){if(getPreferences(0).getInt("data_version",0)>=19)return;try{if(!getPreferences(0).getAll().isEmpty()){java.io.File file=new java.io.File(getFilesDir(),"before-update-19.json");if(!file.exists())try(java.io.FileOutputStream out=new java.io.FileOutputStream(file)){out.write(backupJson().toString().getBytes(StandardCharsets.UTF_8));out.getFD().sync();}}getPreferences(0).edit().putInt("data_version",19).commit();}catch(Exception e){message("Salinan sebelum update gagal. Eksport backup melalui Setting.");}}
  void recoverInternal(){String[] files={"before-update-19.json","before-restore.json"};new AlertDialog.Builder(this).setTitle("Pilih salinan dalaman").setItems(new String[]{"Sebelum update 3.15","Sebelum pemulihan terakhir"},(d,index)->{java.io.File file=new java.io.File(getFilesDir(),files[index]);if(!file.exists()){message("Salinan ini belum tersedia");return;}readBackup(Uri.fromFile(file));}).setNegativeButton("Batal",null).show();}

}
