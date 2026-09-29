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
  static final int REQUEST_BLUETOOTH=42, EXPORT_CSV=43, EXPORT_PDF=44, PICK_IMAGE=45, EXPORT_BACKUP=46, IMPORT_BACKUP=47, PICK_TNG_QR=49;
  int exportYear;
  int activePage=0, imageItem=-1;
  String selectedMonth,selectedDay,rangeStart,selectedStockDay,selectedCashDay;
  boolean cashDailyMode=false;
  int salesReportMode=0; // 0=harian, 1=bulanan, 2=tarikh tersuai
  String salesReportStart="",salesReportEnd="";
  int[] cachedStock;
  final UUID printerUuid=UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
  String pendingReceipt;
  int dp(int x){return (int)(x*getResources().getDisplayMetrics().density+.5f);}
  String money(int cents){return String.format(Locale.US,"RM %.2f",cents/100.0);}
  String date(){return new SimpleDateFormat("yyyy-MM-dd",Locale.US).format(new Date());}
  int sum(){int n=0;for(int i=0;i<qty.length;i++)n+=qty[i]*prices[i];return n;}
  int count(){int n=0;for(int q:qty)n+=q;return n;}
  boolean unlimited(int id){return id==4;}
  String entryDay(JSONObject entry){String business=entry.optString("businessDay","");if(business.matches("\\d{4}-\\d{2}-\\d{2}"))return business;String time=entry.optString("time","");return time.length()>=10?time.substring(6,10)+"-"+time.substring(3,5)+"-"+time.substring(0,2):"";}
  boolean hasOpeningStock(int id){JSONArray list=entries("stock_entries");for(int i=0;i<list.length();i++){JSONObject record=list.optJSONObject(i);if(record!=null&&record.optInt("item",-1)==id)return true;}return false;}
  int[] stockForDay(int id,String target){
    int[] summary=new int[6];long opening=0;JSONArray incoming=entries("stock_entries"),sales=entries("stock_sales"),damaged=entries("stock_damage"),distributed=entries("stock_distribution");
    for(int i=0;i<incoming.length();i++){JSONObject e=incoming.optJSONObject(i);if(e==null||e.optInt("item",-1)!=id)continue;String day=entryDay(e),type=e.optString("type","restock");int value=e.optInt("qty",0);if(day.compareTo(target)<0)opening+=value;else if(day.equals(target)){if("restock".equals(type))summary[1]+=Math.max(0,value);else if("opening".equals(type)||"opening_adjustment".equals(type))opening+=value;}}
    for(int i=0;i<sales.length();i++){JSONObject e=sales.optJSONObject(i);if(e==null||"edar".equals(e.optString("source")))continue;JSONArray q=e.optJSONArray("qty");if(q==null)continue;String day=entryDay(e);int value=Math.max(0,q.optInt(id));if(day.compareTo(target)<0)opening-=value;else if(day.equals(target))summary[2]+=value;}
    for(int i=0;i<distributed.length();i++){JSONObject e=distributed.optJSONObject(i);if(e==null||e.optInt("item",-1)!=id)continue;String day=entryDay(e);int net=Math.max(0,e.optInt("qty",0)-e.optInt("returned",0));if(day.compareTo(target)<0)opening-=net;else if(day.equals(target))summary[5]+=net;}
    for(int i=0;i<damaged.length();i++){JSONObject e=damaged.optJSONObject(i);if(e==null||e.optBoolean("stockReset",false)||e.optInt("item",-1)!=id)continue;String day=entryDay(e);int value=Math.max(0,e.optInt("qty"));boolean refund=e.optBoolean("refund",false);if(day.compareTo(target)<0){if(!refund)opening-=value;}else if(day.equals(target))summary[4]+=value;}
    JSONArray pending=entries("pending_orders");for(int i=0;i<pending.length();i++){JSONObject e=pending.optJSONObject(i);if(e==null||e.optBoolean("paid",false)||e.optBoolean("cancelled",false))continue;JSONArray q=e.optJSONArray("qty");if(q==null)continue;String day=entryDay(e);int value=Math.max(0,q.optInt(id));if(day.compareTo(target)<0)opening-=value;else if(day.equals(target))summary[2]+=value;}
    summary[0]=(int)Math.max(0,Math.min(Integer.MAX_VALUE,opening));summary[3]=Math.max(0,summary[0]+summary[1]-summary[2]-summary[5]-summary[4]);return summary;
  }
  int[] stockToday(int id){return stockForDay(id,businessDay());}
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
googleSignInClient = GoogleSignIn.getClient(this, gso);snapshotBeforeUpdate();loadMenu();clearCurrentNasiDamageOnce();applyCorrection20260926();applySupplierReceiptDetailFix20260926();applyBusinessDayRepair20260929();selectedDay=date();selectedMonth=selectedDay.substring(0,7);rangeStart=selectedDay;selectedStockDay=selectedDay;selectedCashDay=selectedDay;if(b!=null){int[] saved=b.getIntArray("cart");if(saved!=null)System.arraycopy(saved,0,qty,0,Math.min(saved.length,qty.length));activePage=b.getInt("page",0);selectedDay=b.getString("selectedDay",selectedDay);selectedMonth=selectedDay.substring(0,7);rangeStart=b.getString("rangeStart",rangeStart);selectedStockDay=b.getString("selectedStockDay",selectedStockDay);selectedCashDay=b.getString("selectedCashDay",selectedCashDay);cashDailyMode=b.getBoolean("cashDailyMode",cashDailyMode);}draw();}
  @Override protected void onSaveInstanceState(Bundle b){b.putIntArray("cart",qty);b.putInt("page",activePage);b.putString("selectedDay",selectedDay);b.putString("rangeStart",rangeStart);b.putString("selectedStockDay",selectedStockDay);b.putString("selectedCashDay",selectedCashDay);b.putBoolean("cashDailyMode",cashDailyMode);super.onSaveInstanceState(b);}
  void draw(){
    cachedStock=null;
    LinearLayout screen=col();screen.setBackgroundColor(cream);
    getWindow().setStatusBarColor(cream);
    getWindow().setNavigationBarColor(cream);
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
    LinearLayout heading=col();heading.setPadding(dp(10),0,0,0);add(heading,text("WARISAN POS",21,blue,true),-1,-2);LinearLayout sub=row();sub.addView(text("KIOS WARISAN  ·  SISTEM JUALAN",10,muted,true),new LinearLayout.LayoutParams(0,-2,1));TextView liveClock=text("",10,gold,true);liveClock.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);add(sub,liveClock,-2,-2);add(heading,sub,-1,-2);startHeaderClock(liveClock);header.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
    add(body,header,-1,-2);gap(body,18);
    LinearLayout title=row();title.addView(text("Pilih menu",20,ink,true),new LinearLayout.LayoutParams(0,-2,1));TextView historyButton=chip("HISTORY",0xff203f61,gold);add(title,historyButton,-2,-2);historyButton.setOnClickListener(v->saleHistoryChooseDate());LinearLayout.LayoutParams hp=(LinearLayout.LayoutParams)historyButton.getLayoutParams();TextView addMenuButton=chip("+ MENU",blue,Color.WHITE);LinearLayout.LayoutParams amp=params(-2,-2);amp.leftMargin=dp(6);title.addView(addMenuButton,amp);addMenuButton.setOnClickListener(v->addMenu());items=chip("0 item",0xff203f61,gold);LinearLayout.LayoutParams itemLp=params(-2,-2);itemLp.leftMargin=dp(7);title.addView(items,itemLp);add(body,title,-1,-2);
    add(body,text("Tekan + untuk tambah pesanan",12,muted,false),-1,-2);gap(body,13);
    ArrayList<Integer> visibleMenu=new ArrayList<>();for(int i=0;i<names.length;i++){boolean manuallyHidden=getPreferences(0).getBoolean("menu_hidden_"+i,false);boolean hasStock=unlimited(i)||stock(i)>0;if(!manuallyHidden&&hasStock)visibleMenu.add(i);}for(int id:visibleMenu){LinearLayout line=row();product(line,id);add(body,line,-1,-2);gap(body,9);}
    // "Pesanan semasa" removed from MENU because confirmed orders are managed in ORDER.
    // Keep an off-screen basket container so the existing cart refresh logic remains unchanged.
    basket=col();
    gap(body,8);
    LinearLayout footer=row();footer.setPadding(dp(17),dp(9),dp(17),dp(10));footer.setBackgroundColor(surface);
    LinearLayout amount=col();add(amount,text("JUMLAH",11,muted,true),-1,-2);total=text("RM 0.00",22,blue,true);add(amount,total,-1,-2);footer.addView(amount,new LinearLayout.LayoutParams(0,-2,1));
    payButton=new Button(this);payButton.setAllCaps(false);payButton.setText("Confirm Order  →");payButton.setTextColor(Color.WHITE);payButton.setTextSize(16);payButton.setBackground(shape(blue,12));payButton.setOnClickListener(v->confirmOrder());add(footer,payButton,140,51);add(screen,footer,-1,-2);refresh();addNavigation(screen);
  }

  void startHeaderClock(TextView view){final android.os.Handler h=new android.os.Handler(android.os.Looper.getMainLooper());final Runnable[] tick=new Runnable[1];tick[0]=()->{if(!view.isAttachedToWindow())return;view.setText(new SimpleDateFormat("dd/MM/yyyy  HH:mm:ss",Locale.US).format(new Date()));h.postDelayed(tick[0],1000);};view.post(tick[0]);}
  void product(LinearLayout pair,int id){
    LinearLayout card=row();card.setGravity(Gravity.CENTER_VERTICAL);card.setPadding(dp(11),dp(10),dp(11),dp(10));card.setBackground(shape(surface,15));
    // Menu sengaja guna ikon ringan sahaja. Logo kedai kekal pada header/resit.
    TextView icon=chip(icons[id],0xff203f61,gold);icon.setTextSize(20);icon.setGravity(Gravity.CENTER);add(card,icon,52,52);
    LinearLayout info=col();info.setPadding(dp(11),0,dp(8),0);TextView name=text(names[id],15,ink,true);name.setMaxLines(2);add(info,name,-1,-2);gap(info,3);add(info,text(money(prices[id]),14,gold,true),-1,-2);
    int available=unlimited(id)?Integer.MAX_VALUE:stock(id);TextView stockLabel=text(unlimited(id)?"Kuah · tanpa had unit":"Stok: "+available+(available==0?" · HABIS":""),11,available==0?0xffff4d4d:blue,true);add(info,stockLabel,-1,-2);card.addView(info,new LinearLayout.LayoutParams(0,-2,1));
    LinearLayout controls=row();controls.setGravity(Gravity.CENTER_VERTICAL);TextView minus=chip("−",0xffc83f46,Color.WHITE),number=text(""+qty[id],16,ink,true),plus=chip("+",blue,Color.WHITE);minus.setGravity(Gravity.CENTER);number.setGravity(Gravity.CENTER);plus.setGravity(Gravity.CENTER);
    add(controls,minus,45,42);add(controls,number,44,42);add(controls,plus,45,42);add(card,controls,-2,-2);
    number.setClickable(true);number.setOnClickListener(v->editOrderQuantity(id));
    minus.setOnClickListener(v->{qty[id]=Math.max(0,qty[id]-1);draw();});
    plus.setAlpha(available<=qty[id]?.35f:1f);plus.setEnabled(available>qty[id]);plus.setOnClickListener(v->stockLimit(id,1));
    LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);cp.setMargins(dp(2),0,dp(2),0);pair.addView(card,cp);
  }
  void editOrderQuantity(int id){
    EditText input=new EditText(this);input.setSingleLine(true);input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);input.setText(""+qty[id]);input.setSelectAllOnFocus(true);input.setHint("Masukkan kuantiti");styleInput(input);
    AlertDialog dialog=darkBuilder().setTitle("Kuantiti · "+names[id]).setMessage(unlimited(id)?"Masukkan jumlah yang customer beli.":"Stok tersedia: "+stock(id)).setView(input).setPositiveButton("OK",null).setNegativeButton("Batal",null).create();
    dialog.setOnShowListener(v->{styleDarkDialogNow(dialog);input.requestFocus();dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(x->{try{String raw=input.getText().toString().trim();if(raw.isEmpty())throw new NumberFormatException();int value=Integer.parseInt(raw);if(value<0||value>100000)throw new NumberFormatException();if(!unlimited(id)&&value>stock(id)){message("Stok "+names[id]+" hanya tinggal "+stock(id)+".");return;}qty[id]=value;dialog.dismiss();draw();}catch(Exception e){message("Masukkan nombor 0 hingga 100000.");}});});
    dialog.show();
  }

  void refresh(){items.setText(count()+" item");total.setText(money(sum()));payButton.setAlpha(sum()==0?.55f:1f);basket.removeAllViews();
    if(count()==0){TextView empty=text("Belum ada pesanan. Pilih menu di atas.",13,muted,false);empty.setPadding(0,dp(13),0,dp(13));add(basket,empty,-1,-2);return;}
    for(int i=0;i<qty.length;i++)if(qty[i]>0){final int id=i;LinearLayout r=row();r.addView(text(names[i]+" × "+qty[i],14,ink,true),new LinearLayout.LayoutParams(0,dp(39),1));add(r,text(money(qty[i]*prices[i]),13,blue,true),-2,-2);TextView minus=chip("−",0xffc83f46,Color.WHITE);LinearLayout.LayoutParams m=params(32,30);m.leftMargin=dp(8);r.addView(minus,m);minus.setOnClickListener(v->{qty[id]--;draw();});add(basket,r,-1,-2);}
  }
  void addNavigation(LinearLayout screen){LinearLayout nav=row();nav.setBackgroundColor(cream);nav.setPadding(dp(7),dp(7),dp(7),dp(9));String[] labels={"MENU","ORDER","STOK","DUIT","SETTING"};int[] pages={0,11,1,3,4};
    for(int i=0;i<labels.length;i++){final int page=pages[i];boolean selected=(activePage==page)||(page==3&&activePage==2)||(page==4&&activePage>=4&&activePage!=11);TextView tab=text(labels[i],10,selected?Color.rgb(19,24,34):Color.WHITE,true);tab.setGravity(Gravity.CENTER);tab.setBackground(shape(selected?gold:Color.rgb(20,58,101),11));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(49),1);p.setMargins(dp(3),0,dp(3),0);nav.addView(tab,p);tab.setOnClickListener(v->{activePage=page;draw();});}add(screen,nav,-1,-2);}
  void heading(String title,String subtitle){
    LinearLayout brand=row();brand.setGravity(Gravity.CENTER_VERTICAL);ImageView logo=new ImageView(this);logo.setImageResource(R.drawable.warisan_logo);makeCircle(logo);add(brand,logo,44,44);LinearLayout brandText=col();brandText.setPadding(dp(9),0,0,0);add(brandText,text("WARISAN POS",18,blue,true),-1,-2);add(brandText,text("KIOS WARISAN  ·  SISTEM JUALAN",9,muted,true),-1,-2);brand.addView(brandText,new LinearLayout.LayoutParams(0,-2,1));TextView clock=text("",9,gold,true);clock.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);add(brand,clock,-2,-2);startHeaderClock(clock);add(body,brand,-1,-2);gap(body,15);
    add(body,text(title,23,ink,true),-1,-2);add(body,text(subtitle,12,muted,false),-1,-2);gap(body,16);
  }
  void action(String label,Runnable callback){TextView button=text(label+"   ›",15,blue,true);button.setPadding(dp(16),dp(14),dp(14),dp(14));button.setBackground(shape(surface,12));add(body,button,-1,-2);gap(body,9);button.setOnClickListener(v->callback.run());}
  void metric(LinearLayout row,String title,String value,int highlight,Runnable tap){LinearLayout card=col();card.setPadding(dp(12),dp(12),dp(9),dp(12));card.setBackground(shape(surface,13));TextView name=text(title,11,muted,true);name.setMaxLines(2);add(card,name,-1,-2);gap(card,8);TextView figure=text(value,value.startsWith("RM")?16:20,highlight,true);figure.setMaxLines(1);figure.setEllipsize(android.text.TextUtils.TruncateAt.END);add(card,figure,-1,-2);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(94),1);p.setMargins(dp(3),0,dp(3),0);row.addView(card,p);if(tap!=null)card.setOnClickListener(v->tap.run());}
  boolean legacySupplierRestock(JSONObject e){
    String c=e==null?"":e.optString("category","");if(!c.startsWith("Restock · "))return false;String item=c.substring("Restock · ".length()).trim();for(int id=0;id<names.length;id++)if(names[id].equals(item)&&supplierItem(id))return true;return false;
  }
  int[] cashTotals(String month){int[] totals=new int[2];JSONArray entries=entries("cash_entries");for(int i=0;i<entries.length();i++){JSONObject e=entries.optJSONObject(i);if(e==null||legacySupplierRestock(e)||!month.equals(e.optString("month")))continue;int amount=e.optInt("amount");if(amount>=0)totals[0]+=amount;else totals[1]-=amount;}return totals;}
  int[] cashTotalsDay(String day){int[] totals=new int[2];JSONArray list=entries("cash_entries");for(int i=0;i<list.length();i++){JSONObject e=list.optJSONObject(i);if(e==null||legacySupplierRestock(e)||!day.equals(entryDay(e)))continue;int amount=e.optInt("amount");if(amount>=0)totals[0]+=amount;else totals[1]-=amount;}return totals;}
  int damageTotalDay(String day){int n=0;JSONArray list=entries("stock_damage");for(int i=0;i<list.length();i++){JSONObject e=list.optJSONObject(i);if(e!=null&&!e.optBoolean("stockReset",false)&&day.equals(entryDay(e)))n+=e.optInt("cost");}return n;}
  boolean personalCash(JSONObject e){if(e==null)return false;if(e.optBoolean("personal",false))return true;String c=e.optString("category","");return e.optInt("amount",0)<0&&(c.equals("Pengeluaran Peribadi")||c.equals("Rokok · Peribadi")||c.equals("Nescafe / Minuman · Peribadi")||c.equals("Makan / Air kedai · Peribadi")||c.equals("Makan / Minum · Peribadi"));}
  boolean stockPurchaseCash(JSONObject e){String c=e==null?"":e.optString("category","");return c.startsWith("Bayaran Pembekal · ")||c.startsWith("Restock · ")||"Sate mentah".equals(c);}
  int personalTotal(String period,boolean daily){int n=0;JSONArray list=entries("cash_entries");for(int i=0;i<list.length();i++){JSONObject e=list.optJSONObject(i);if(e==null||!personalCash(e))continue;boolean match=daily?period.equals(entryDay(e)):period.equals(e.optString("month"));if(match&&e.optInt("amount")<0)n-=e.optInt("amount");}return n;}
  int operatingExpenseTotal(String period,boolean daily){int n=0;JSONArray list=entries("cash_entries");for(int i=0;i<list.length();i++){JSONObject e=list.optJSONObject(i);if(e==null||legacySupplierRestock(e)||personalCash(e)||stockPurchaseCash(e))continue;boolean match=daily?period.equals(entryDay(e)):period.equals(e.optString("month"));if(match&&e.optInt("amount")<0)n-=e.optInt("amount");}return n;}
  int cashCategoryTotal(String period,boolean daily,String... categories){int n=0;JSONArray list=entries("cash_entries");for(int i=0;i<list.length();i++){JSONObject e=list.optJSONObject(i);if(e==null)continue;boolean match=daily?period.equals(entryDay(e)):period.equals(e.optString("month"));if(!match||e.optInt("amount",0)>=0)continue;String c=e.optString("category","");for(String wanted:categories)if(wanted.equals(c)){n-=e.optInt("amount");break;}}return n;}
  int supplierPaidTotal(String period,boolean daily){int n=0;JSONArray list=entries("cash_entries");for(int i=0;i<list.length();i++){JSONObject e=list.optJSONObject(i);if(e==null)continue;boolean match=daily?period.equals(entryDay(e)):period.equals(e.optString("month"));if(match&&e.optInt("amount",0)<0&&e.optString("category","").startsWith("Bayaran Pembekal · "))n-=e.optInt("amount");}return n;}
  int businessCashBalance(){long total=0;for(String key:getPreferences(0).getAll().keySet())if(key.matches("sales_\\d{4}-\\d{2}-\\d{2}"))total+=getPreferences(0).getInt(key,0);JSONArray list=entries("cash_entries");for(int i=0;i<list.length();i++){JSONObject e=list.optJSONObject(i);if(e!=null&&!legacySupplierRestock(e)&&!personalCash(e))total+=e.optInt("amount");}return (int)Math.max(Integer.MIN_VALUE,Math.min(Integer.MAX_VALUE,total));}
  int productCostBetween(String start,String end){long total=0;JSONArray sales=entries("stock_sales");for(int i=0;i<sales.length();i++){JSONObject sale=sales.optJSONObject(i);if(sale==null||sale.optBoolean("refunded",false))continue;String day=entryDay(sale);if(day.compareTo(start)<0||day.compareTo(end)>0)continue;JSONArray q=sale.optJSONArray("qty");if(q==null)continue;int storedCost=sale.optInt("costTotal",-1);if(storedCost>=0){total+=storedCost;continue;}for(int id=0;id<Math.min(q.length(),names.length);id++){int units=Math.max(0,q.optInt(id));if(units>0&&!unlimited(id))total+=restockCost(id,units);}}return (int)Math.min(Integer.MAX_VALUE,total);}
  int refundedSalesBetween(String start,String end){int total=0;JSONArray sales=entries("stock_sales");for(int i=0;i<sales.length();i++){JSONObject sale=sales.optJSONObject(i);if(sale!=null&&sale.optBoolean("refunded",false)){String day=entryDay(sale);if(day.compareTo(start)>=0&&day.compareTo(end)<=0)total+=saleTotal(sale);}}return total;}
  int businessProfit(String period,boolean daily){String start=daily?period:period+"-01",end=daily?period:period+"-31";int netSales=saleTotalBetween(start,end);return netSales-productCostBetween(start,end)-operatingExpenseTotal(period,daily);}
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
    // Dark rounded preview frame + white printer-style receipt with breathing room.
    LinearLayout previewFrame=col();previewFrame.setPadding(dp(12),dp(4),dp(12),dp(8));previewFrame.setBackground(shape(cream,20));
    ScrollView scroll=new ScrollView(this);scroll.setVerticalScrollBarEnabled(false);scroll.setFillViewport(true);previewFrame.addView(scroll,new LinearLayout.LayoutParams(-1,-2));
    int receiptText=0xff26384c,receiptMuted=0xff66788a;
    LinearLayout sheet=col();sheet.setPadding(dp(20),dp(14),dp(20),dp(16));sheet.setBackground(shape(Color.WHITE,10));scroll.addView(sheet);
    ImageView logo=new ImageView(this);logo.setImageResource(R.drawable.warisan_logo);makeCircle(logo);LinearLayout logoRow=row();logoRow.setGravity(Gravity.CENTER);add(logoRow,logo,74,74);add(sheet,logoRow,-1,-2);gap(sheet,5);
    TextView brand=text(getPreferences(0).getString("shop_name","WARISAN FROZEN"),17,blue,true);brand.setGravity(Gravity.CENTER);add(sheet,brand,-1,-2);
    String phone=getPreferences(0).getString("receipt_phone","");TextView contact=text(phone.isEmpty()?"No. telefon belum diisi":"Tel: "+phone,11,receiptMuted,false);contact.setGravity(Gravity.CENTER);add(sheet,contact,-1,-2);
    String email=getPreferences(0).getString("receipt_email","");if(!email.isEmpty()){TextView mail=text(email,11,receiptMuted,false);mail.setGravity(Gravity.CENTER);add(sheet,mail,-1,-2);}
    receiptRule(sheet);TextView title=text("RESIT BAYARAN PEMBEKAL",14,blue,true);title.setGravity(Gravity.CENTER);add(sheet,title,-1,-2);gap(sheet,9);
    receiptRowLight(sheet,"Tarikh",new SimpleDateFormat("dd/MM/yyyy HH:mm",Locale.US).format(new Date()),false);receiptRule(sheet);
    String details=supplierItemsText(group);if(!details.isEmpty()){TextView dl=text("PECAHAN STOK",11,receiptMuted,true);add(sheet,dl,-1,-2);gap(sheet,4);for(String lineText:details.split("\\n")){TextView dv=text(lineText,12,receiptText,false);add(sheet,dv,-1,-2);gap(sheet,3);}receiptRule(sheet);}
    receiptRowLight(sheet,"Jumlah asal",money(original),false);gap(sheet,7);receiptRowLight(sheet,"Jumlah tolakan",money(deduction),false);receiptRule(sheet);receiptRowLight(sheet,"JUMLAH DIBAYAR",money(paid),true);
    if(!remark.trim().isEmpty()){receiptRule(sheet);TextView rl=text("REMARK",11,receiptMuted,true);add(sheet,rl,-1,-2);gap(sheet,4);TextView rv=text(remark.trim(),13,receiptText,false);add(sheet,rv,-1,-2);}
    receiptRule(sheet);TextView status=text("TELAH DIBAYAR",13,blue,true);status.setGravity(Gravity.CENTER);add(sheet,status,-1,-2);
    String owner=ownerDisplay();if(!owner.isEmpty()){gap(sheet,8);TextView owned=text("DIMILIKI OLEH : "+owner,10,receiptMuted,true);owned.setGravity(Gravity.CENTER);add(sheet,owned,-1,-2);}
    AlertDialog dialog=darkBuilder().setTitle("PREVIEW RESIT PEMBEKAL").setView(previewFrame).setPositiveButton("Cetak resit",(d,w)->print(printed)).setNeutralButton("Share",(d,w)->shareReceiptImage(sheet,"resit-pembekal")).setNegativeButton("Tutup",null).create();applyDarkReportDialog(dialog);
  }
  void previewSupplierReceipt(){int original=17500,deduction=2500,paid=15000;String remark="Contoh remark / alasan tolakan";showSupplierReceipt(supplierReceipt(-1,original,deduction,paid,remark),-1,original,deduction,paid,remark);}
  void paySupplier(){paySupplier(-1,"Sate");}
  void paySupplier(int group,String label){int due=supplierDue(group);if(due<=0){message("Tiada bayaran pembekal "+label+" tertunggak.");return;}LinearLayout form=col();form.setPadding(dp(18),dp(5),dp(18),0);add(form,text("Jumlah perlu dibayar: "+money(due),15,ink,true),-1,-2);EditText deduction=priceField("Jumlah tolakan RM","0.00");EditText remark=new EditText(this);remark.setHint("Remark / alasan tolakan");styleInput(remark);add(form,text("Jumlah tolakan",12,muted,true),-1,-2);add(form,deduction,-1,-2);add(form,text("Remark",12,muted,true),-1,-2);add(form,remark,-1,-2);AlertDialog dialog=darkBuilder().setTitle("Telah dibayar · "+label).setView(form).setPositiveButton("CONFIRM",null).setNegativeButton("Batal",null).create();dialog.setOnShowListener(v->{styleDarkDialogNow(dialog);dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(x->{try{int cut=parseCents(deduction);String note=remark.getText().toString().trim();if(cut<0||cut>due){message("Jumlah tolakan tidak sah");return;}if(cut>0&&note.isEmpty()){message("Isi remark / alasan untuk jumlah tolakan");return;}int paid=due-cut;JSONObject expense=new JSONObject();expense.put("time",timestamp());expense.put("businessDay",businessDay());expense.put("month",businessDay().substring(0,7));expense.put("amount",-paid);expense.put("category","Bayaran Pembekal · "+label);expense.put("note",note);expense.put("supplierOriginal",due);expense.put("supplierDeduction",cut);expense.put("supplierPaid",paid);expense.put("supplierGroup",group);expense.put("supplierLabel",label);expense.put("supplierItemsSnapshot",supplierItems(group).toString());JSONArray cash=entries("cash_entries");cash.put(expense);String key=supplierDueKey(group);String detailSnapshot=supplierItems(group).toString();getPreferences(0).edit().putString("supplier_receipt_snapshot_"+group,detailSnapshot).commit();String printed=supplierReceipt(group,due,cut,paid,note);android.content.SharedPreferences.Editor editor=getPreferences(0).edit().putString("cash_entries",cash.toString()).putInt(key,0).remove(supplierItemsKey(group));if(!editor.commit())throw new Exception();dialog.dismiss();draw();getPreferences(0).edit().putString(supplierItemsKey(group),detailSnapshot).commit();showSupplierReceipt(printed,group,due,cut,paid,note);getPreferences(0).edit().remove(supplierItemsKey(group)).apply();}catch(Exception e){message("Semak jumlah tolakan dan cuba lagi.");}});});dialog.show();}


  void supplierReceiptHistory(int group,String label){
    JSONArray cash=entries("cash_entries");ArrayList<JSONObject> records=new ArrayList<>();
    String category="Bayaran Pembekal · "+label;
    for(int i=cash.length()-1;i>=0;i--){JSONObject e=cash.optJSONObject(i);if(e!=null&&category.equals(e.optString("category")))records.add(e);}
    if(records.isEmpty()){message("Belum ada sejarah bayaran pembekal "+label+".");return;}
    String[] rows=new String[records.size()];for(int i=0;i<records.size();i++){JSONObject e=records.get(i);String t=e.optString("time","");rows[i]=(t.isEmpty()?"Tarikh tidak diketahui":t)+"   ·   "+money(e.optInt("supplierPaid",Math.abs(e.optInt("amount"))));}
    darkBuilder().setTitle("History resit · "+label).setItems(rows,(d,which)->{try{JSONObject e=records.get(which);int original=e.optInt("supplierOriginal",Math.abs(e.optInt("amount"))),cut=e.optInt("supplierDeduction",0),paid=e.optInt("supplierPaid",Math.abs(e.optInt("amount")));String note=e.optString("note","");String snapshot=e.optString("supplierItemsSnapshot","");String key=supplierItemsKey(group);String before=getPreferences(0).getString(key,"[]");if(!snapshot.isEmpty())getPreferences(0).edit().putString(key,snapshot).commit();String printed=supplierReceipt(group,original,cut,paid,note);showSupplierReceipt(printed,group,original,cut,paid,note);getPreferences(0).edit().putString(key,before).apply();}catch(Exception ex){message("Resit lama tidak dapat dibuka.");}}).setNegativeButton("Tutup",null).show();
  }
  void addSupplierPaymentCard(String label,int group){int due=supplierDue(group);LinearLayout supplier=col();supplier.setPadding(dp(15),dp(13),dp(15),dp(13));supplier.setBackground(shape(surface,15));add(supplier,text("BAYARAN PEMBEKAL · "+label,11,muted,true),-1,-2);gap(supplier,7);add(supplier,text("Jumlah perlu dibayar",12,ink,true),-1,-2);add(supplier,text(money(due),23,due>0?gold:blue,true),-1,-2);gap(supplier,10);LinearLayout buttons=row();TextView paid=chip("TELAH DIBAYAR",due>0?blue:0xff526273,Color.WHITE),history=chip("HISTORY RESIT",0xff203f61,gold);paid.setGravity(Gravity.CENTER);history.setGravity(Gravity.CENTER);paid.setEnabled(due>0);paid.setAlpha(due>0?1f:.55f);buttons.addView(paid,new LinearLayout.LayoutParams(0,dp(43),1));LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(0,dp(43),1);hp.leftMargin=dp(7);buttons.addView(history,hp);add(supplier,buttons,-1,-2);paid.setOnClickListener(v->paySupplier(group,label));history.setOnClickListener(v->supplierReceiptHistory(group,label));add(body,supplier,-1,-2);gap(body,10);}

  String saleReportStart(){if(salesReportMode==0)return selectedDay;if(salesReportMode==1)return selectedMonth+"-01";return salesReportStart.isEmpty()?selectedDay:salesReportStart;}
  String saleReportEnd(){if(salesReportMode==0)return selectedDay;if(salesReportMode==1)return selectedMonth+"-31";return salesReportEnd.isEmpty()?selectedDay:salesReportEnd;}
  int saleTotalBetween(String start,String end){int total=0;JSONArray list=entries("stock_sales");for(int i=0;i<list.length();i++){JSONObject e=list.optJSONObject(i);if(e==null||e.optBoolean("refunded",false))continue;String d=entryDay(e);if(d.compareTo(start)>=0&&d.compareTo(end)<=0)total+=saleTotal(e);}return total;}
  int saleCountBetween(String start,String end){int total=0;JSONArray list=entries("stock_sales");for(int i=0;i<list.length();i++){JSONObject e=list.optJSONObject(i);if(e==null||e.optBoolean("refunded",false))continue;String d=entryDay(e);if(d.compareTo(start)>=0&&d.compareTo(end)<=0){JSONArray q=e.optJSONArray("qty");if(q!=null)for(int n=0;n<q.length();n++)total+=Math.max(0,q.optInt(n));}}return total;}
  int transactionCountBetween(String start,String end){int total=0;JSONArray list=entries("stock_sales");for(int i=0;i<list.length();i++){JSONObject e=list.optJSONObject(i);if(e==null||e.optBoolean("refunded",false))continue;String d=entryDay(e);if(d.compareTo(start)>=0&&d.compareTo(end)<=0)total++;}return total;}
  int[] soldNetBetween(String start,String end){int[] sold=new int[names.length];JSONArray list=entries("stock_sales");for(int i=0;i<list.length();i++){JSONObject e=list.optJSONObject(i);if(e==null||e.optBoolean("refunded",false))continue;String d=entryDay(e);if(d.compareTo(start)<0||d.compareTo(end)>0)continue;JSONArray q=e.optJSONArray("qty");if(q!=null)for(int n=0;n<Math.min(q.length(),sold.length);n++)sold[n]+=Math.max(0,q.optInt(n));}return sold;}
  int paymentTotalBetween(String start,String end,String methodPart){int total=0;JSONArray list=entries("stock_sales");for(int i=0;i<list.length();i++){JSONObject e=list.optJSONObject(i);if(e==null||e.optBoolean("refunded",false))continue;String d=entryDay(e);if(d.compareTo(start)<0||d.compareTo(end)>0)continue;if(e.optString("method","").toLowerCase(Locale.US).contains(methodPart.toLowerCase(Locale.US)))total+=saleTotal(e);}return total;}
  void chooseSalesReportRange(){pickDay("Tarikh mula",salesReportStart.isEmpty()?selectedDay:salesReportStart,start->{salesReportStart=start;pickDay("Tarikh akhir",salesReportEnd.isEmpty()?selectedDay:salesReportEnd,end->{if(end.compareTo(start)<0){message("Tarikh akhir mesti selepas tarikh mula.");return;}salesReportEnd=end;salesReportMode=2;draw();});});}
  void addReportTabs(){
    LinearLayout tabs=row();String[] labels={"HARIAN","BULANAN","TARIKH"};for(int i=0;i<3;i++){final int m=i;TextView b=chip(labels[i],salesReportMode==i?gold:0xff173b60,salesReportMode==i?0xff17202c:Color.WHITE);b.setGravity(Gravity.CENTER);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(40),1);p.setMargins(dp(3),0,dp(3),0);tabs.addView(b,p);b.setOnClickListener(v->{if(m==2){chooseSalesReportRange();return;}salesReportMode=m;draw();});}add(body,tabs,-1,-2);gap(body,9);
    if(salesReportMode==0)action("Tarikh: "+selectedDay,()->chooseSalesDay());else if(salesReportMode==1)action("Bulan: "+monthLabel(selectedMonth),()->chooseMonth());else action("Dari "+saleReportStart()+"  hingga  "+saleReportEnd(),()->chooseSalesReportRange());
  }
  void addSalesBreakdown(String start,String end){
    int[] sold=soldNetBetween(start,end);int total=saleTotalBetween(start,end),allQty=0;for(int n:sold)allQty+=n;
    LinearLayout pieCard=col();pieCard.setPadding(dp(12),dp(12),dp(12),dp(12));pieCard.setBackground(shape(surface,14));
    add(pieCard,text("PECAHAN JUALAN · NILAI",13,ink,true),-1,-2);gap(pieCard,8);add(pieCard,new SalesPieChart(sold),-1,220);add(body,pieCard,-1,-2);gap(body,10);

    LinearLayout detail=col();detail.setPadding(dp(12),dp(12),dp(12),dp(12));detail.setBackground(shape(surface,14));
    add(detail,text("DETAIL JUALAN MENGIKUT PRODUK",13,ink,true),-1,-2);gap(detail,8);
    LinearLayout head=row();head.setGravity(Gravity.CENTER_VERTICAL);
    TextView h1=text("MENU",10,muted,true);TextView h2=text("KUANTITI",10,muted,true);TextView h3=text("JUMLAH / %",10,muted,true);
    head.addView(h1,new LinearLayout.LayoutParams(0,dp(28),1.35f));head.addView(h2,new LinearLayout.LayoutParams(0,dp(28),.8f));head.addView(h3,new LinearLayout.LayoutParams(0,dp(28),1.15f));add(detail,head,-1,28);
    int sateQty=0,sateValue=0;
    for(int i=0;i<names.length;i++){
      if(sold[i]<=0)continue;
      int value=sold[i]*prices[i];if(i<3){sateQty+=sold[i];sateValue+=value;}
      double pct=total<=0?0:(value*100.0/total);
      LinearLayout r=row();r.setGravity(Gravity.CENTER_VERTICAL);r.setPadding(0,dp(4),0,dp(4));
      TextView nm=text(icons[i]+"  "+names[i],11,ink,true);nm.setSingleLine(true);nm.setEllipsize(android.text.TextUtils.TruncateAt.END);
      String unit=i<3?" cucuk":i==5?" mangkuk":" unit";
      TextView qty=text(sold[i]+unit,10,muted,true);qty.setGravity(Gravity.CENTER);
      TextView val=text(money(value)+"\n"+String.format(Locale.US,"%.1f%%",pct),10,gold,true);val.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);
      r.addView(nm,new LinearLayout.LayoutParams(0,dp(46),1.35f));r.addView(qty,new LinearLayout.LayoutParams(0,dp(46),.8f));r.addView(val,new LinearLayout.LayoutParams(0,dp(46),1.15f));
      add(detail,r,-1,46);
    }
    if(allQty==0)add(detail,text("Belum ada item terjual untuk tempoh ini.",12,muted,false),-1,-2);
    LinearLayout totalRow=row();totalRow.setPadding(0,dp(7),0,0);TextView tl=text("JUMLAH",11,blue,true);totalRow.addView(tl,new LinearLayout.LayoutParams(0,-2,1));add(totalRow,text(allQty+" item  ·  "+money(total),11,blue,true),-2,-2);add(detail,totalRow,-1,-2);
    add(body,detail,-1,-2);gap(body,10);

    if(sateQty>0){
      LinearLayout sate=col();sate.setPadding(dp(12),dp(12),dp(12),dp(12));sate.setBackground(shape(surface,14));add(sate,text("DETAIL SATE · PECAHAN",13,ink,true),-1,-2);gap(sate,7);
      for(int i=0;i<3;i++){if(sold[i]<=0)continue;double pct=sateValue<=0?0:(sold[i]*prices[i]*100.0/sateValue);LinearLayout r=row();r.setGravity(Gravity.CENTER_VERTICAL);TextView a=text(names[i],11,ink,true);r.addView(a,new LinearLayout.LayoutParams(0,dp(38),1));add(r,text(sold[i]+" cucuk",10,muted,true),dp(78),dp(38));TextView v=text(money(sold[i]*prices[i])+" · "+String.format(Locale.US,"%.1f%%",pct),10,gold,true);v.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);add(r,v,dp(120),dp(38));add(sate,r,-1,38);}
      add(sate,text("Jumlah sate: "+sateQty+" cucuk  ·  "+money(sateValue),11,blue,true),-1,-2);add(body,sate,-1,-2);gap(body,10);
    }
  }
  void addPaymentBreakdown(String start,String end){
    int cash=paymentTotalBetween(start,end,"tunai"),qr=paymentTotalBetween(start,end,"qr"),total=saleTotalBetween(start,end);
    LinearLayout card=col();card.setPadding(dp(12),dp(12),dp(12),dp(12));card.setBackground(shape(surface,14));add(card,text("KAEDAH BAYARAN",13,ink,true),-1,-2);gap(card,8);
    String[] n={"Tunai","QR / Online"};int[] v={cash,qr};for(int i=0;i<2;i++){LinearLayout r=row();r.addView(text(n[i],12,ink,true),new LinearLayout.LayoutParams(0,-2,1));double pct=total<=0?0:v[i]*100.0/total;add(r,text(money(v[i])+"   "+String.format(Locale.US,"%.1f%%",pct),12,i==0?gold:blue,true),-2,-2);add(card,r,-1,-2);gap(card,7);}add(body,card,-1,-2);gap(body,10);
  }
  void addRecentTransactions(String start,String end){
    JSONArray list=entries("stock_sales");LinearLayout card=col();card.setPadding(dp(12),dp(12),dp(12),dp(12));card.setBackground(shape(surface,14));add(card,text("TRANSAKSI TERKINI",13,ink,true),-1,-2);gap(card,7);int shown=0;
    for(int i=list.length()-1;i>=0&&shown<8;i--){JSONObject e=list.optJSONObject(i);if(e==null||e.optBoolean("refunded",false))continue;String d=entryDay(e);if(d.compareTo(start)<0||d.compareTo(end)>0)continue;String tm=e.optString("time","");String clock=tm.length()>=16?tm.substring(11):tm;LinearLayout r=row();add(r,text(clock,11,muted,true),dp(52),-2);TextView desc=text(saleItemsText(e).replace("\n"," · "),11,ink,false);desc.setMaxLines(1);desc.setEllipsize(android.text.TextUtils.TruncateAt.END);r.addView(desc,new LinearLayout.LayoutParams(0,-2,1));add(r,text(money(saleTotal(e)),11,gold,true),-2,-2);add(card,r,-1,-2);gap(card,6);shown++;}
    if(shown==0)add(card,text("Tiada transaksi untuk tempoh ini.",12,muted,false),-1,-2);add(body,card,-1,-2);gap(body,10);
  }

  boolean cashCategoryMatches(JSONObject e,String category){
    String c=e.optString("category","");
    if("Bayaran Pembekal".equals(category))return c.startsWith("Bayaran Pembekal · ");
    if("Bahan Lain".equals(category))return c.equals("Bahan lain")||c.equals("Barang plastik")||c.equals("Lain-lain");
    if("Pengeluaran Peribadi".equals(category))return personalCash(e);
    if("Duit Masuk".equals(category))return e.optInt("amount",0)>0&&!legacySupplierRestock(e);
    if("Duit Keluar".equals(category))return e.optInt("amount",0)<0&&!legacySupplierRestock(e)&&!personalCash(e);
    return c.equals(category);
  }
  void filteredCashHistory(String title,String category){
    LinearLayout box=col();box.setPadding(dp(16),dp(12),dp(16),dp(12));box.setBackgroundColor(cream);
    ScrollView sc=new ScrollView(this);sc.setFillViewport(true);sc.setBackgroundColor(cream);sc.addView(box);
    JSONArray list=entries("cash_entries");int found=0,total=0;
    for(int i=list.length()-1;i>=0;i--){JSONObject e=list.optJSONObject(i);if(e==null||!cashCategoryMatches(e,category))continue;found++;int a=e.optInt("amount",0);total+=a;String when=e.optString("time",e.optString("date",""));String cat=e.optString("category",category),note=e.optString("note",e.optString("remark",""));LinearLayout row=col();row.setPadding(dp(12),dp(10),dp(12),dp(10));row.setBackground(shape(surface,12));add(row,text(cat+"   "+money(Math.abs(a)),12,a>=0?0xff46c979:0xffff6b6b,true),-1,-2);add(row,text(when+(note.isEmpty()?"":"\n"+note),10,muted,false),-1,-2);add(box,row,-1,-2);gap(box,7);}
    if(found==0)add(box,text("Belum ada rekod "+title.toLowerCase(new Locale("ms","MY"))+".",12,muted,false),-1,-2);else{gap(box,6);add(box,text("Jumlah rekod: "+found+"   ·   Nilai: "+money(Math.abs(total)),12,gold,true),-1,-2);}
    AlertDialog dlg=new AlertDialog.Builder(this).setTitle(title).setView(sc).setPositiveButton("TUTUP",null).create();
    dlg.setOnShowListener(x->{dlg.getWindow().setBackgroundDrawable(shape(cream,16));int id=getResources().getIdentifier("alertTitle","id","android");TextView t=dlg.findViewById(id);if(t!=null)t.setTextColor(ink);dlg.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(gold);});
    dlg.show();
  }
  void supplierHub(){
    LinearLayout box=col();box.setPadding(dp(14),dp(12),dp(14),dp(12));box.setBackgroundColor(cream);ScrollView sc=new ScrollView(this);sc.setFillViewport(true);sc.setBackgroundColor(cream);sc.addView(box);AlertDialog dlg=new AlertDialog.Builder(this).setTitle("Bayaran Pembekal").setView(sc).setNegativeButton("Tutup",null).create();
    int dueSate=supplierDue(-1);LinearLayout sate=col();sate.setPadding(dp(10),dp(10),dp(10),dp(10));sate.setBackground(shape(surface,11));add(sate,text("SATE",13,ink,true),-1,-2);add(sate,text("Baki perlu dibayar  "+money(dueSate),12,dueSate>0?gold:0xff46c979,true),-1,-2);LinearLayout sr=row();TextView sp=chip("TELAH DIBAYAR",blue,Color.WHITE);TextView sh=chip("HISTORY RESIT",0xff294563,Color.WHITE);sr.addView(sp,new LinearLayout.LayoutParams(0,dp(40),1));LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(0,dp(40),1);hp.leftMargin=dp(6);sr.addView(sh,hp);add(sate,sr,-1,-2);sp.setOnClickListener(v->{dlg.dismiss();paySupplier(-1,"Sate");});sh.setOnClickListener(v->{dlg.dismiss();supplierReceiptHistory(-1,"Sate");});add(box,sate,-1,-2);gap(box,8);
    for(int id=3;id<names.length;id++){if(id==4||getPreferences(0).getBoolean("menu_hidden_"+id,false)||!supplierItem(id))continue;final int sid=id;int due=supplierDue(sid);LinearLayout c=col();c.setPadding(dp(10),dp(10),dp(10),dp(10));c.setBackground(shape(surface,11));add(c,text(names[sid].toUpperCase(new Locale("ms","MY")),13,ink,true),-1,-2);add(c,text("Baki perlu dibayar  "+money(due),12,due>0?gold:0xff46c979,true),-1,-2);LinearLayout rr=row();TextView p=chip("TELAH DIBAYAR",blue,Color.WHITE),h=chip("HISTORY RESIT",0xff294563,Color.WHITE);rr.addView(p,new LinearLayout.LayoutParams(0,dp(40),1));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(40),1);lp.leftMargin=dp(6);rr.addView(h,lp);add(c,rr,-1,-2);p.setOnClickListener(v->{dlg.dismiss();paySupplier(sid,names[sid]);});h.setOnClickListener(v->{dlg.dismiss();supplierReceiptHistory(sid,names[sid]);});add(box,c,-1,-2);gap(box,8);}
    dlg.setOnShowListener(x->{dlg.getWindow().setBackgroundDrawable(shape(cream,16));int id=getResources().getIdentifier("alertTitle","id","android");TextView t=dlg.findViewById(id);if(t!=null)t.setTextColor(ink);dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(gold);});
    dlg.show();
  }
  void drawPage(){if(activePage==11){heading("Order belum bayar","Semua order yang sudah confirm tetapi belum dibayar.");JSONArray pending=entries("pending_orders");int open=0;for(int i=0;i<pending.length();i++){JSONObject o=pending.optJSONObject(i);if(o!=null&&!o.optBoolean("paid",false)&&!o.optBoolean("cancelled",false))open++;}if(open==0){LinearLayout empty=col();empty.setPadding(dp(16),dp(18),dp(16),dp(18));empty.setBackground(shape(surface,15));add(empty,text("Tiada order belum bayar.",15,ink,true),-1,-2);gap(empty,5);add(empty,text("Order yang di-Confirm dari MENU akan muncul di sini.",12,muted,false),-1,-2);add(body,empty,-1,-2);}else drawPendingOrders();
      gap(body,12);
    }else if(activePage==1){heading("Stok & restock","Pilih tarikh untuk semak rekod stok. Baki hari sebelumnya dibawa ke hari seterusnya.");
      action("Tarikh stok: "+selectedStockDay+"   ▼",()->chooseStockDay());boolean stockTodayView=businessDay().equals(selectedStockDay);
      for(int i=0;i<names.length;i++){final int id=i;if(getPreferences(0).getBoolean("menu_hidden_"+id,false))continue;if(unlimited(id)){action(names[id]+" · kuah ikut liter (tiada had unit)",()->message("Kuah kacang sentiasa boleh dijual."));continue;}
        int[] st=stockForDay(id,selectedStockDay);LinearLayout card=col();card.setPadding(dp(12),dp(10),dp(12),dp(10));card.setBackground(shape(surface,15));
        add(card,text(names[id],16,ink,true),-1,-2);gap(card,7);
        LinearLayout stockRow=row();stockTile(stockRow,"AWAL",""+st[0]);stockTile(stockRow,"RESTOCK","+"+st[1]);stockTile(stockRow,"TERJUAL","−"+st[2]);stockTile(stockRow,"BAKI",""+st[3]);add(card,stockRow,-1,-2);if(st[5]>0){gap(card,5);TextView edarInfo=chip("EDAR DI LUAR  −"+st[5],0xff6a4b1f,0xffffd54f);edarInfo.setGravity(Gravity.CENTER);add(card,edarInfo,-1,32);}
        if(st[4]>0){TextView damaged=text("Rosak: "+st[4]+" unit",11,0xffff4d4d,true);damaged.setPadding(dp(3),dp(6),0,0);add(card,damaged,-1,-2);}
        gap(card,7);
        if(stockTodayView){LinearLayout controls=row();TextView startBtn=chip(hasOpeningStock(id)?"Edit awal":"+ Stok awal",0xffffd54f,0xff6d3b16),more=chip("+ Restock",blue,Color.WHITE),damage=chip("− Rosak",0xffff2d2d,Color.WHITE);startBtn.setGravity(Gravity.CENTER);more.setGravity(Gravity.CENTER);damage.setGravity(Gravity.CENTER);controls.addView(startBtn,new LinearLayout.LayoutParams(0,dp(38),1));LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(0,dp(38),1);mp.leftMargin=dp(5);controls.addView(more,mp);LinearLayout.LayoutParams dp2=new LinearLayout.LayoutParams(0,dp(38),1);dp2.leftMargin=dp(5);controls.addView(damage,dp2);startBtn.setOnClickListener(v->{if(hasOpeningStock(id))editOpeningStock(id);else stockEntryItem(id,true);});more.setOnClickListener(v->stockEntryItem(id,false));damage.setOnClickListener(v->damageEntry(id));add(card,controls,-1,-2);if(distributionPrice(id)>0){gap(card,6);TextView edar=chip("EDAR STOK",0xff8a5b17,Color.WHITE);edar.setGravity(Gravity.CENTER);edar.setOnClickListener(v->distributeStock(id));add(card,edar,-1,38);}}
        else{TextView old=chip("REKOD LAMA · PAPARAN SAHAJA",0xff31485f,muted);old.setGravity(Gravity.CENTER);add(card,old,-1,34);}
        add(body,card,-1,-2);gap(body,8);}
      action("HISTORY EDAR / BAYAR / STOK PULANG",()->distributionHistory());action("Lihat ringkasan stok tarikh dipilih",()->stockBalance());action("Rekod stok rosak",()->damageHistory());
    }else if(activePage==2){heading("Detail Jualan","Dashboard laporan lengkap · graf, pie, produk, bayaran dan transaksi.");
      action("‹  Kembali ke DUIT",()->{activePage=3;draw();});addReportTabs();
      String rs=saleReportStart(),re=saleReportEnd();int reportSales=saleTotalBetween(rs,re),reportItems=saleCountBetween(rs,re),reportTx=transactionCountBetween(rs,re);int reportOp=0;if(salesReportMode==1)reportOp=operatingExpenseTotal(selectedMonth,false);else if(salesReportMode==0)reportOp=operatingExpenseTotal(rs,true);else{JSONArray ce=entries("cash_entries");for(int i=0;i<ce.length();i++){JSONObject e=ce.optJSONObject(i);if(e==null||legacySupplierRestock(e)||personalCash(e)||stockPurchaseCash(e))continue;String d=entryDay(e);if(d.compareTo(rs)>=0&&d.compareTo(re)<=0&&e.optInt("amount")<0)reportOp-=e.optInt("amount");}}int reportProfit=reportSales-productCostBetween(rs,re)-reportOp;
      LinearLayout summary=row();metric(summary,"JUMLAH JUALAN",money(reportSales),blue,null);metric(summary,"UNTUNG BERSIH",money(reportProfit),reportProfit>=0?0xff46c979:0xffff6b6b,null);add(body,summary,-1,-2);gap(body,7);
      LinearLayout summary2=row();metric(summary2,"JUMLAH ITEM",""+reportItems,0xffb987ff,null);metric(summary2,"TRANSAKSI",""+reportTx,gold,null);add(body,summary2,-1,-2);gap(body,10);
      if(salesReportMode==0){
        int[] byHour=new int[6];String[] labels={"4pm","6pm","8pm","10pm","12am","2am"};JSONArray sales=entries("stock_sales");for(int i=0;i<sales.length();i++){JSONObject e=sales.optJSONObject(i);if(e==null||e.optBoolean("refunded",false)||!selectedDay.equals(entryDay(e)))continue;String t=e.optString("time","");int h=8;try{h=Integer.parseInt(t.substring(11,13));}catch(Exception ignored){}int shifted=h<4?h+24:h;int bucket=Math.max(0,Math.min(5,(shifted-16)/2));byHour[bucket]+=saleTotal(e);}LinearLayout c=col();c.setPadding(dp(12),dp(12),dp(12),dp(8));c.setBackground(shape(surface,14));add(c,text("GRAF JUALAN · HARIAN",13,ink,true),-1,-2);add(c,new SalesChart(byHour,labels),-1,205);add(body,c,-1,-2);gap(body,10);
      }else if(salesReportMode==1){
        int days=daysInMonth(selectedMonth);int[] totals=new int[days];String[] labels=new String[days];for(int i=0;i<days;i++){String d=selectedMonth+String.format(Locale.US,"-%02d",i+1);totals[i]=saleTotalBetween(d,d);labels[i]=(i==0||(i+1)%5==0||i==days-1)?""+(i+1):"";}LinearLayout c=col();c.setPadding(dp(12),dp(12),dp(12),dp(8));c.setBackground(shape(surface,14));add(c,text("GRAF JUALAN · "+monthLabel(selectedMonth),13,ink,true),-1,-2);add(c,new SalesChart(totals,labels),-1,205);add(body,c,-1,-2);gap(body,10);
      }else{
        java.util.ArrayList<Integer> tv=new java.util.ArrayList<>();java.util.ArrayList<String> lv=new java.util.ArrayList<>();try{java.text.SimpleDateFormat f=new java.text.SimpleDateFormat("yyyy-MM-dd",Locale.US);java.util.Calendar a=java.util.Calendar.getInstance(),b=java.util.Calendar.getInstance();a.setTime(f.parse(rs));b.setTime(f.parse(re));int guard=0;while(!a.after(b)&&guard++<93){String d=f.format(a.getTime());tv.add(saleTotalBetween(d,d));lv.add(new java.text.SimpleDateFormat("d/M",Locale.US).format(a.getTime()));a.add(java.util.Calendar.DATE,1);}}catch(Exception ignored){}int[] totals=new int[tv.size()];String[] labels=new String[lv.size()];for(int i=0;i<tv.size();i++){totals[i]=tv.get(i);labels[i]=(i==0||i==tv.size()-1||i%5==0)?lv.get(i):"";}LinearLayout c=col();c.setPadding(dp(12),dp(12),dp(12),dp(8));c.setBackground(shape(surface,14));add(c,text("GRAF JUALAN · TARIKH DIPILIH",13,ink,true),-1,-2);add(c,new SalesChart(totals,labels),-1,205);add(body,c,-1,-2);gap(body,10);
      }
      addSalesBreakdown(rs,re);addPaymentBreakdown(rs,re);addRecentTransactions(rs,re);
      action("History customer / Refund",()->{if(salesReportMode==0)saleHistory(selectedDay);else if(salesReportMode==1)saleHistory(selectedMonth);else new AlertDialog.Builder(this).setTitle("History Tarikh Dipilih").setMessage("History transaksi untuk "+rs+" hingga "+re+" dipaparkan pada bahagian Transaksi Terkini di atas.").setPositiveButton("OK",null).show();});action("Export laporan Excel / PDF",()->exportMenu());
    }else if(activePage==3){heading("Duit / POS","Fokus jualan harian dan sesi POS. Operasi bermula sekitar 4 petang; sesi kekal pada tarikh Opening sehingga Closing dibuat.");
      LinearLayout mode=row();TextView daily=chip("HARIAN",cashDailyMode?gold:0xff294563,cashDailyMode?0xff17202c:Color.WHITE),monthlyBtn=chip("BULANAN",!cashDailyMode?gold:0xff294563,!cashDailyMode?0xff17202c:Color.WHITE);daily.setGravity(Gravity.CENTER);monthlyBtn.setGravity(Gravity.CENTER);mode.addView(daily,new LinearLayout.LayoutParams(0,dp(42),1));LinearLayout.LayoutParams mlp=new LinearLayout.LayoutParams(0,dp(42),1);mlp.leftMargin=dp(7);mode.addView(monthlyBtn,mlp);add(body,mode,-1,-2);gap(body,9);daily.setOnClickListener(v->{cashDailyMode=true;draw();});monthlyBtn.setOnClickListener(v->{cashDailyMode=false;draw();});
      if(cashDailyMode)action("Tarikh: "+selectedCashDay+"   ▼",()->chooseCashDay());else action("Bulan: "+monthLabel(selectedMonth)+"   ▼",()->chooseMonth());
      String period=cashDailyMode?selectedCashDay:selectedMonth;String from=cashDailyMode?period:period+"-01",to=cashDailyMode?period:period+"-31";int sale=saleTotalBetween(from,to);int profit=businessProfit(period,cashDailyMode);int supplierPaid=supplierPaidTotal(period,cashDailyMode),supplierOutstanding=supplierDue(),fuel=cashCategoryTotal(period,cashDailyMode,"Minyak kereta"),rent=cashCategoryTotal(period,cashDailyMode,"Sewa");
      add(body,text("OPERASI JUALAN",12,muted,true),-1,-2);gap(body,7);
      LinearLayout saleCard=row();metric(saleCard,cashDailyMode?"JUALAN HARI INI  ›":"JUALAN BULAN INI  ›",money(sale),blue,()->{selectedDay=cashDailyMode?selectedCashDay:selectedMonth+"-01";selectedMonth=selectedDay.substring(0,7);salesReportMode=cashDailyMode?0:1;activePage=2;draw();});add(body,saleCard,-1,-2);gap(body,8);
      LinearLayout r1=row();metric(r1,"UNTUNG BERSIH JUALAN",money(profit),profit>=0?gold:0xffff6b6b,null);metric(r1,"BAYARAN PEMBEKAL  ›",money(supplierPaid),gold,()->supplierHub());add(body,r1,-1,-2);gap(body,8);
      LinearLayout r2=row();metric(r2,"MINYAK KERETA  ›",money(fuel),ink,()->filteredCashHistory("Minyak Kereta","Minyak kereta"));metric(r2,"SEWA  ›",money(rent),ink,()->filteredCashHistory("Sewa","Sewa"));add(body,r2,-1,-2);gap(body,12);
      if(cashDailyMode){add(body,text("SESI POS",12,muted,true),-1,-2);gap(body,7);String activeDay=posSessionOpen()?posSessionDay():"";if(selectedCashDay.equals(activeDay)||selectedCashDay.equals(date()))drawPosSession();else drawHistoricalPos(selectedCashDay);}
      else{gap(body,8);CashDonutChart cashChart=new CashDonutChart(selectedMonth);add(body,cashChart,-1,270);}
    }else if(activePage==4){
      heading("Setting","WarisanPOS 3.33 FINAL · Tetapan kedai dan data.");
      action("👤  Account",()->{activePage=5;draw();});
      action("🧾  Bill / Resit",()->{activePage=7;draw();});
       action("💳  Payment / DuitNow",()->{activePage=10;draw();});
      action("🍽  Pengurusan Menu",()->{activePage=6;draw();});
      action("🏪  Maklumat Kedai",()->{activePage=8;draw();});
      action("⚠  Reset",()->{activePage=9;draw();});
    }else if(activePage==5){
      heading("Account","Akaun, backup cloud dan salinan pada telefon.");
      action("‹  Kembali ke Setting",()->{activePage=4;draw();});
      add(body,text("ACCOUNT",11,muted,true),-1,-2);gap(body,7);
      FirebaseUser user=firebaseAuth.getCurrentUser();
      if(user==null){
        action("🔐  Sign in Google",()->{Intent signInIntent=googleSignInClient.getSignInIntent();startActivityForResult(signInIntent,RC_SIGN_IN);});
      }else{
        String email=user.getEmail()==null?"Google Account":user.getEmail();
        action("☁  "+email,()->message("Google account telah disambungkan."));
        action("Log out Google",()->{firebaseAuth.signOut();googleSignInClient.signOut().addOnCompleteListener(task->draw());});
      }
      gap(body,7);add(body,text("BACKUP / PULIHKAN",11,muted,true),-1,-2);gap(body,7);
      if(user!=null){action("☁  Backup / Sync ke Google",()->syncToCloud());action("↻  Pulihkan dari Google",()->restoreFromCloud());}
      else add(body,text("Sign in Google untuk guna backup cloud.",12,muted,false),-1,-2);
      gap(body,10);add(body,text("DEVICE",11,muted,true),-1,-2);gap(body,7);
      action("↓  Backup to phone",()->backupPicker(false));
      action("↑  Restore from phone",()->backupPicker(true));
      action("↶  Pulihkan salinan sebelum update / pulih",()->recoverInternal());
    }else if(activePage==6){
      heading("Pengurusan Menu","Tambah, edit, gambar dan padam menu.");
      action("‹  Kembali ke Setting",()->{activePage=4;draw();});
      action("+ Tambah menu & harga",()->addMenu());
      action("Edit harga jualan & harga mentah",()->chooseMenuPrice());
      action("Edit harga edar",()->chooseDistributionPrice());
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
    }else if(activePage==10){
      heading("Payment / DuitNow","Tetapan merchant disimpan dari Setting, bukan hard-code dalam source.");
      action("‹  Kembali ke Setting",()->{activePage=4;draw();});
      String provider=getPreferences(0).getString("payment_provider","Static DuitNow");
      action("Upload / Tukar QR Touch ’n Go",()->pickTngQr());
      action("Preview QR Touch ’n Go",()->previewTngQr());
      action("Provider · "+provider,()->editPaymentProvider());
      action("Merchant ID",()->editPaymentSetting("payment_merchant_id","Merchant ID",""));
      action("API / Public Key",()->editPaymentSetting("payment_api_key","API / Public Key",""));
      action("Secret Key",()->editPaymentSetting("payment_secret","Secret Key",""));
      action("API Base URL",()->editPaymentSetting("payment_base_url","API Base URL",""));
      action("Test Connection",()->testPaymentConnection());
      action("Padam konfigurasi merchant",()->deletePaymentConfig());
    }else if(activePage==9){
      heading("Reset","Pilihan reset data WarisanPOS.");
      action("‹  Kembali ke Setting",()->{activePage=4;draw();});
      action("Reset stok sahaja",()->resetData(false));
      action("Reset semua data",()->resetData(true));
    }}
  String dailyClosingReceipt(String day){
    int sales=getPreferences(0).getInt("sales_"+day,0),orders=getPreferences(0).getInt("orders_"+day,0);
    int[] sold=soldBetween(day,day),cash=cashTotalsDay(day);
    int personal=personalTotal(day,true),damage=damageTotalDay(day),profit=businessProfit(day,true);
    StringBuilder b=new StringBuilder();String shopName=getPreferences(0).getString("shop_name","WARISAN FROZEN");
    b.append(centerReceipt(shopName));
    String phone=getPreferences(0).getString("receipt_phone","");if(!phone.isEmpty())b.append(centerReceipt("Tel: "+phone));
    b.append("--------------------------------\n").append(centerReceipt("LAPORAN CLOSING HARIAN"));
    b.append(line("Tarikh",day)).append("--------------------------------\n");
    b.append(line("JUALAN POS",money(sales))).append(line("PESANAN",String.valueOf(orders)));
    b.append("--------------------------------\nITEM TERJUAL\n");
    boolean any=false;for(int i=0;i<names.length;i++){if(sold[i]<=0)continue;any=true;String unit=i<3?" cucuk":i==4?" hidangan":" unit";b.append(line(names[i],sold[i]+unit));}
    if(!any)b.append("Tiada item terjual\n");
    b.append("--------------------------------\n");
    b.append(line("DUIT MASUK",money(cash[0]))).append(line("DUIT KELUAR",money(cash[1])));
    b.append(line("PERIBADI",money(personal))).append(line("STOK ROSAK",money(damage)));
    b.append("--------------------------------\n").append(line("UNTUNG PERNIAGAAN",money(profit)));
    b.append("================================\n").append(centerReceipt("CLOSING HARIAN")).append(ownerReceipt()).append("\n\n");return b.toString();
  }
  int defaultDistributionPrice(int id){if(id==0)return 130;if(id==1)return 160;if(id==3)return 60;if(id==5)return 600;if(id==6)return 400;return 0;}
  int distributionPrice(int id){int def=defaultDistributionPrice(id);return getPreferences(0).getInt("distribution_price_"+id,def);}
  void chooseDistributionPrice(){darkBuilder().setTitle("Harga Edar").setItems(names,(d,id)->editDistributionPrice(id)).setNegativeButton("Tutup",null).show();}
  void editDistributionPrice(int id){int current=distributionPrice(id);LinearLayout form=col();form.setPadding(dp(18),dp(6),dp(18),0);EditText price=priceField("Harga edar RM",current>0?String.format(Locale.US,"%.2f",current/100.0):"");add(form,text("Harga edar (RM)",13,ink,true),-1,-2);add(form,price,-1,-2);add(form,text("Letak 0.00 jika produk ini tidak digunakan untuk Edar.",12,muted,false),-1,-2);darkBuilder().setTitle("Harga Edar · "+names[id]).setView(form).setPositiveButton("Simpan",(d,w)->{try{int cents=parseCents(price);if(cents<0||cents>10000000)throw new Exception();if(!getPreferences(0).edit().putInt("distribution_price_"+id,cents).commit())throw new Exception();draw();message("Harga Edar "+names[id]+" disimpan · "+money(cents));}catch(Exception e){message("Semak harga Edar RM.");}}).setNegativeButton("Batal",null).show();}
  boolean posSessionOpen(){return getPreferences(0).getBoolean("pos_session_open",false);}
  String posSessionDay(){String d=getPreferences(0).getString("pos_session_day",date());return d.isEmpty()?date():d;}
  int posOpening(){return getPreferences(0).getInt("pos_opening",0);}
  String nextDay(String day){try{SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd",Locale.US);Calendar c=Calendar.getInstance();c.setTime(f.parse(day));c.add(Calendar.DATE,1);return f.format(c.getTime());}catch(Exception e){return date();}}
  String previousDay(String day){try{SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd",Locale.US);Calendar c=Calendar.getInstance();c.setTime(f.parse(day));c.add(Calendar.DATE,-1);return f.format(c.getTime());}catch(Exception e){return day;}}
  void applyBusinessDayRepair20260929(){android.content.SharedPreferences p=getPreferences(0);if(p.getBoolean("fix_business_day_20260929",false))return;try{String today=date();if(!"2026-09-29".equals(today)){p.edit().putBoolean("fix_business_day_20260929",true).commit();return;}String oldDay="2026-09-28";String[] keys={"stock_sales","pending_orders","cash_entries","stock_entries","stock_damage","stock_distribution"};android.content.SharedPreferences.Editor ed=p.edit();for(String key:keys){JSONArray list=entries(key);boolean changed=false;for(int i=0;i<list.length();i++){JSONObject e=list.optJSONObject(i);if(e==null)continue;if(today.equals(entryDay(e))){e.put("businessDay",oldDay);changed=true;}}if(changed)ed.putString(key,list.toString());}int salesToday=p.getInt("sales_"+today,0),salesOld=p.getInt("sales_"+oldDay,0);if(salesToday>0){ed.putInt("sales_"+oldDay,salesOld+salesToday);ed.putInt("sales_"+today,0);}int ordersToday=p.getInt("orders_"+today,0),ordersOld=p.getInt("orders_"+oldDay,0);if(ordersToday>0){ed.putInt("orders_"+oldDay,ordersOld+ordersToday);ed.putInt("orders_"+today,0);}if(p.getBoolean("pos_session_open",false)&&today.equals(p.getString("pos_session_day","")))ed.putString("pos_session_day",oldDay);ed.putBoolean("fix_business_day_20260929",true);ed.commit();}catch(Exception e){}}
  String businessDay(){return posSessionOpen()?posSessionDay():date();}
  int outstandingCount(){int n=0;JSONArray p=entries("pending_orders");for(int i=0;i<p.length();i++){JSONObject e=p.optJSONObject(i);if(e!=null&&!e.optBoolean("paid",false)&&!e.optBoolean("cancelled",false))n++;}JSONArray d=entries("stock_distribution");for(int i=0;i<d.length();i++){JSONObject e=d.optJSONObject(i);if(e!=null&&!e.optBoolean("paid",false)&&Math.max(0,e.optInt("qty")-e.optInt("returned"))>0)n++;}return n;}
  int sessionSales(String day){return saleTotalBetween(day,day);}
  int sessionCash(String day){return paymentTotalBetween(day,day,"Tunai");}
  int sessionQr(String day){return paymentTotalBetween(day,day,"QR");}
  JSONObject closingForDay(String day){JSONArray list=entries("pos_closings");JSONObject found=null;for(int i=0;i<list.length();i++){JSONObject e=list.optJSONObject(i);if(e!=null&&day.equals(entryDay(e)))found=e;}return found;}
  int legacyBalanceThrough(String day){long total=0;android.content.SharedPreferences p=getPreferences(0);for(String key:p.getAll().keySet()){if(!key.matches("sales_\\d{4}-\\d{2}-\\d{2}"))continue;String d=key.substring(6);if(d.compareTo(day)<=0)total+=p.getInt(key,0);}JSONArray list=entries("cash_entries");for(int i=0;i<list.length();i++){JSONObject e=list.optJSONObject(i);if(e==null||legacySupplierRestock(e))continue;String d=entryDay(e);if(!d.isEmpty()&&d.compareTo(day)<=0)total+=e.optInt("amount");}return (int)Math.max(Integer.MIN_VALUE,Math.min(Integer.MAX_VALUE,total));}
  void drawHistoricalPos(String day){JSONObject c=closingForDay(day);int sales=saleTotalBetween(day,day),cash=paymentTotalBetween(day,day,"Tunai"),qr=paymentTotalBetween(day,day,"QR");int opening=0,balance;if(c!=null){opening=c.optInt("opening",0);sales=c.optInt("sales",sales);cash=c.optInt("cash",cash);qr=c.optInt("qr",qr);balance=c.has("closingBalance")?c.optInt("closingBalance",0):Math.max(0,opening+cash);}else balance=Math.max(0,legacyBalanceThrough(day));LinearLayout a=row();metric(a,"OPENING BALANCE",money(opening),gold,null);metric(a,"JUALAN HARI",money(sales),blue,null);add(body,a,-1,-2);gap(body,7);LinearLayout b=row();metric(b,c!=null?"BAKI CLOSING":"BAKI DUIT AKHIR HARI",money(balance),0xff46c979,null);add(body,b,-1,-2);gap(body,6);add(body,text("Tunai "+money(cash)+"   ·   QR "+money(qr)+(c==null?"\nRekod lama: baki dikira daripada rekod jualan + aliran duit sehingga tarikh ini.":""),11,muted,false),-1,-2);}
  void drawPosSession(){boolean open=posSessionOpen();String day=posSessionDay();int opening=open?posOpening():0,sales=open?sessionSales(day):0,cash=open?sessionCash(day):0,balance=open?Math.max(0,opening+cash):0;LinearLayout a=row();metric(a,"OPENING BALANCE",money(opening),gold,null);metric(a,"JUALAN SESI",money(sales),blue,null);add(body,a,-1,-2);gap(body,7);LinearLayout b=row();metric(b,"BAKI SESI POS",money(balance),open?0xff46c979:muted,null);add(body,b,-1,-2);gap(body,8);if(open){action("OPENING DIKUNCI · "+day,()->message("Selagi sesi "+day+" belum Closing, Opening baru tidak boleh dibuat."));gap(body,6);action("CLOSING POS · "+day,()->previewPosClosing());}else{action("BUKA POS / OPENING BALANCE",()->openPosSession());}}
  int lastClosingBalance(){JSONArray list=entries("pos_closings");JSONObject latest=null;String latestDay="";for(int i=0;i<list.length();i++){JSONObject e=list.optJSONObject(i);if(e==null)continue;String d=entryDay(e);if(latest==null||d.compareTo(latestDay)>=0){latest=e;latestDay=d;}}if(latest==null)return Math.max(0,totalMoneyBalance());int saved=latest.optInt("closingBalance",Integer.MIN_VALUE);if(saved>0)return saved;if(saved==0){int overall=Math.max(0,totalMoneyBalance());if(overall>0)return overall;return 0;}int legacy=latest.optInt("opening",0)+latest.optInt("cash",0);return legacy>0?legacy:Math.max(0,totalMoneyBalance());}
  void startPosSession(String day,int amount){if(getPreferences(0).edit().putBoolean("pos_session_open",true).putString("pos_session_day",day).putInt("pos_opening",Math.max(0,amount)).commit()){selectedCashDay=day;draw();}else message("Opening POS gagal disimpan.");}
  void openPosSession(){if(posSessionOpen()){message("Closing sesi semasa dahulu sebelum Opening baru.");return;}String last=getPreferences(0).getString("pos_last_closed_day","");String proposed=last.isEmpty()?date():nextDay(last);if(date().compareTo(proposed)>0)proposed=date();final String day=proposed;final int lastBalance=lastClosingBalance();LinearLayout box=col();box.setPadding(dp(16),dp(10),dp(16),dp(8));box.setBackgroundColor(cream);add(box,text("OPENING POS · "+day,18,blue,true),-1,-2);gap(box,8);add(box,text("Baki Closing Terakhir  "+money(lastBalance),14,gold,true),-1,-2);if(!last.isEmpty())add(box,text("Closing terakhir · "+last,11,muted,false),-1,-2);add(box,text("Pilih sama ada bawa baki Closing terakhir atau mulakan sesi baru dengan RM0.00.",12,muted,false),-1,-2);AlertDialog dlg=darkBuilder().setView(box).setPositiveButton("KEKALKAN BAKI",null).setNeutralButton("MULA RM0.00",null).setNegativeButton("BATAL",null).create();dlg.setOnShowListener(v->{styleDarkDialogNow(dlg);dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(x->{startPosSession(day,lastBalance);dlg.dismiss();});dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(x->{startPosSession(day,0);dlg.dismiss();});});dlg.show();}
  void previewPosClosing(){if(!posSessionOpen()){message("Tiada sesi POS yang sedang dibuka.");return;}String day=posSessionDay();int opening=posOpening(),sales=sessionSales(day),cash=sessionCash(day),qr=sessionQr(day),tx=transactionCountBetween(day,day),out=outstandingCount(),balance=Math.max(0,opening+cash);LinearLayout box=col();box.setPadding(dp(16),dp(12),dp(16),dp(8));box.setBackgroundColor(cream);add(box,text("PREVIEW CLOSING · "+day,18,blue,true),-1,-2);gap(box,9);add(box,text("Opening Balance  "+money(opening),14,ink,true),-1,-2);add(box,text("Jualan Sesi  "+money(sales),14,ink,true),-1,-2);add(box,text("Tunai  "+money(cash)+"   ·   QR  "+money(qr),13,muted,false),-1,-2);add(box,text("Transaksi  "+tx,13,muted,false),-1,-2);add(box,text("Belum bayar carry forward  "+out,13,gold,true),-1,-2);gap(box,8);add(box,text("BAKI SEBELUM CLOSING  "+money(balance),16,0xff46c979,true),-1,-2);AlertDialog dlg=darkBuilder().setView(box).setPositiveButton("CONFIRM CLOSING",null).setNegativeButton("BATAL",null).create();dlg.setOnShowListener(v->{styleDarkDialogNow(dlg);dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(x->{confirmPosClosing(day,opening,sales,cash,qr,tx,out,balance);dlg.dismiss();});});dlg.show();}
  void confirmPosClosing(String day,int opening,int sales,int cash,int qr,int tx,int outstanding,int balance){try{JSONObject c=new JSONObject();c.put("time",timestamp());c.put("businessDay",day);c.put("opening",opening);c.put("sales",sales);c.put("cash",cash);c.put("qr",qr);c.put("transactions",tx);c.put("outstandingCarryForward",outstanding);c.put("closingBalance",balance);JSONArray list=entries("pos_closings");list.put(c);if(!getPreferences(0).edit().putString("pos_closings",list.toString()).putBoolean("pos_session_open",false).putString("pos_last_closed_day",day).putInt("pos_opening",0).commit())throw new Exception();draw();message("Closing "+day+" disimpan. Baki sesi POS sekarang RM0.00. Opening baru sudah boleh dibuat.");}catch(Exception e){message("Closing gagal disimpan.");}}
  void distributeStock(int id){int price=distributionPrice(id);if(price<=0){message("Produk ini belum ditetapkan untuk Edar.");return;}int available=stock(id);if(available<=0){message("Stok "+names[id]+" tidak cukup untuk Edar.");return;}EditText input=new EditText(this);input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);input.setHint("Kuantiti · maksimum "+available);styleInput(input);darkBuilder().setTitle("EDAR · "+names[id]).setMessage("Harga edar "+money(price)+" / unit. Stok akan keluar dari baki kedai dan masuk History Edar sebagai BELUM BAYAR.").setView(input).setPositiveButton("EDAR",(d,w)->{try{int n=Integer.parseInt(input.getText().toString().trim());if(n<=0||n>stock(id))throw new Exception();JSONObject e=new JSONObject();e.put("time",timestamp());e.put("businessDay",businessDay());e.put("item",id);e.put("qty",n);e.put("returned",0);e.put("unitPrice",price);e.put("paid",false);JSONArray list=entries("stock_distribution");list.put(e);if(!getPreferences(0).edit().putString("stock_distribution",list.toString()).commit())throw new Exception();draw();message(n+" "+names[id]+" direkod sebagai EDAR · BELUM BAYAR.");}catch(Exception e){message("Kuantiti Edar tidak sah.");}}).setNegativeButton("Batal",null).show();}
  void distributionHistory(){JSONArray list=entries("stock_distribution");ScrollView scroll=new ScrollView(this);LinearLayout panel=col();panel.setPadding(dp(14),dp(12),dp(14),dp(14));panel.setBackgroundColor(cream);scroll.addView(panel);int shown=0;for(int i=list.length()-1;i>=0;i--){JSONObject e=list.optJSONObject(i);if(e==null)continue;shown++;final int index=i;int id=e.optInt("item",-1),q=e.optInt("qty"),ret=e.optInt("returned"),sold=Math.max(0,q-ret),price=e.optInt("unitPrice"),due=sold*price;boolean paid=e.optBoolean("paid",false);LinearLayout c=col();c.setPadding(dp(11),dp(10),dp(11),dp(10));c.setBackground(shape(surface,12));String nm=id>=0&&id<names.length?names[id]:"Produk";add(c,text(nm+" · "+entryDay(e),14,ink,true),-1,-2);add(c,text("Edar "+q+"   ·   Pulang "+ret+"   ·   Terjual "+sold,12,muted,false),-1,-2);add(c,text("Perlu bayar  "+money(due)+"   ·   "+(paid?"SUDAH BAYAR":"BELUM BAYAR"),12,paid?0xff46c979:gold,true),-1,-2);if(!paid){gap(c,6);LinearLayout rr=row();TextView back=chip("STOK PULANG",0xff294563,Color.WHITE),pay=chip("BAYAR",blue,Color.WHITE);rr.addView(back,new LinearLayout.LayoutParams(0,dp(38),1));LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(0,dp(38),1);pp.leftMargin=dp(6);rr.addView(pay,pp);back.setOnClickListener(v->returnDistributedStock(index));pay.setOnClickListener(v->payDistribution(index));add(c,rr,-1,-2);}add(panel,c,-1,-2);gap(panel,7);}if(shown==0)add(panel,text("Belum ada rekod Edar.",14,muted,false),-1,-2);AlertDialog dlg=darkBuilder().setTitle("HISTORY EDAR").setView(scroll).setPositiveButton("Tutup",null).create();applyDarkReportDialog(dlg);}
  void returnDistributedStock(int index){JSONArray list=entries("stock_distribution");JSONObject e=list.optJSONObject(index);if(e==null||e.optBoolean("paid",false)){message("Rekod sudah dibayar atau tidak dijumpai.");return;}int remaining=Math.max(0,e.optInt("qty")-e.optInt("returned"));if(remaining<=0){message("Semua stok Edar sudah dipulangkan.");return;}EditText input=new EditText(this);input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);input.setHint("Maksimum "+remaining);styleInput(input);darkBuilder().setTitle("STOK PULANG").setMessage("Boleh pulang sehingga "+remaining+" unit. Stok pulang masuk semula ke Baki dan bukan Stok Rosak.").setView(input).setPositiveButton("PULANG",(d,w)->{try{int n=Integer.parseInt(input.getText().toString().trim());if(n<=0||n>remaining)throw new Exception();JSONArray fresh=entries("stock_distribution");JSONObject x=fresh.optJSONObject(index);if(x==null||x.optBoolean("paid",false))throw new Exception();x.put("returned",x.optInt("returned")+n);x.put("returnTime",timestamp());fresh.put(index,x);if(!getPreferences(0).edit().putString("stock_distribution",fresh.toString()).commit())throw new Exception();draw();message(n+" unit stok pulang masuk semula ke Baki.");}catch(Exception ex){message("Kuantiti stok pulang tidak sah.");}}).setNegativeButton("Batal",null).show();}
  void payDistribution(int index){try{JSONArray dist=entries("stock_distribution");JSONObject e=dist.optJSONObject(index);if(e==null||e.optBoolean("paid",false)){message("Rekod Edar ini sudah dibayar.");return;}int id=e.optInt("item"),sold=Math.max(0,e.optInt("qty")-e.optInt("returned")),total=sold*e.optInt("unitPrice"),day=0;if(sold<=0){e.put("paid",true);e.put("paidTime",timestamp());dist.put(index,e);getPreferences(0).edit().putString("stock_distribution",dist.toString()).commit();draw();message("Edar ditutup RM0 kerana semua stok dipulangkan.");return;}String saleDay=entryDay(e);JSONObject sale=new JSONObject();sale.put("time",timestamp());sale.put("businessDay",saleDay);sale.put("paymentDate",date());sale.put("source","edar");sale.put("total",total);sale.put("method","Edar");sale.put("refunded",false);sale.put("costTotal",restockCost(id,sold));JSONArray q=new JSONArray();for(int i=0;i<names.length;i++)q.put(i==id?sold:0);sale.put("qty",q);JSONArray sales=entries("stock_sales");sales.put(sale);e.put("paid",true);e.put("paidTime",timestamp());e.put("saleTotal",total);dist.put(index,e);android.content.SharedPreferences.Editor ed=getPreferences(0).edit().putString("stock_sales",sales.toString()).putString("stock_distribution",dist.toString()).putInt("sales_"+saleDay,getPreferences(0).getInt("sales_"+saleDay,0)+total);if(!ed.commit())throw new Exception();draw();message("Bayaran Edar "+money(total)+" masuk rekod jualan "+saleDay+".");}catch(Exception ex){message("Bayaran Edar gagal direkod.");}}
  void dailyClosing(String day){
    String printed=dailyClosingReceipt(day);int sales=getPreferences(0).getInt("sales_"+day,0),orders=getPreferences(0).getInt("orders_"+day,0),profit=businessProfit(day,true);int[] sold=soldBetween(day,day),cash=cashTotalsDay(day);
    ScrollView scroll=new ScrollView(this);scroll.setVerticalScrollBarEnabled(false);LinearLayout panel=col();panel.setPadding(dp(16),dp(12),dp(16),dp(14));panel.setBackgroundColor(cream);scroll.addView(panel);
    add(panel,text("CLOSING HARIAN · "+day,18,blue,true),-1,-2);gap(panel,10);LinearLayout top=row();metric(top,"JUALAN",money(sales),blue,null);metric(top,"PESANAN",""+orders,gold,null);add(panel,top,-1,-2);gap(panel,8);LinearLayout moneyRow=row();metric(moneyRow,"DUIT MASUK",money(cash[0]),blue,null);metric(moneyRow,"UNTUNG",money(profit),profit>=0?gold:0xffff6b6b,null);add(panel,moneyRow,-1,-2);gap(panel,12);
    add(panel,text("ITEM TERJUAL",12,muted,true),-1,-2);gap(panel,6);boolean any=false;for(int i=0;i<names.length;i++){if(sold[i]<=0)continue;any=true;String unit=i<3?" cucuk":i==4?" hidangan":" unit";add(panel,text(names[i]+"  ·  "+sold[i]+unit,13,ink,false),-1,-2);gap(panel,4);}if(!any)add(panel,text("Tiada item terjual.",13,muted,false),-1,-2);
    AlertDialog dialog=darkBuilder().setView(scroll).setPositiveButton("CETAK REPORT",(d,w)->print(printed)).setNeutralButton("TUTUP",null).create();applyDarkReportDialog(dialog);
  }

  void loadQrFullResolution(ImageView view,String location)throws Exception{
    Uri uri=Uri.parse(location);BitmapFactory.Options options=new BitmapFactory.Options();options.inScaled=false;
    try(InputStream source=getContentResolver().openInputStream(uri)){Bitmap bitmap=BitmapFactory.decodeStream(source,null,options);if(bitmap==null)throw new IOException("QR tidak dapat dibuka");view.setImageBitmap(bitmap);}
    view.setAdjustViewBounds(true);view.setScaleType(ImageView.ScaleType.FIT_CENTER);view.setFilterTouchesWhenObscured(true);
  }
  void pickTngQr(){Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);intent.setType("image/*");intent.addCategory(Intent.CATEGORY_OPENABLE);intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);try{startActivityForResult(intent,PICK_TNG_QR);}catch(Exception e){message("Galeri tidak dapat dibuka");}}
  void previewTngQr(){String saved=getPreferences(0).getString("tng_qr_uri","");if(saved.isEmpty()){message("QR Touch ’n Go belum dimasukkan.");return;}try{LinearLayout box=col();box.setPadding(dp(16),dp(14),dp(16),dp(12));box.setBackground(shape(surface,18));add(box,text("QR TOUCH ’N GO",19,ink,true),-1,-2);gap(box,10);ImageView image=new ImageView(this);loadQrFullResolution(image,saved);add(box,image,-1,430);AlertDialog dialog=darkBuilder().setView(box).setPositiveButton("OK",null).create();dialog.setOnShowListener(v->{styleDarkDialogNow(dialog);});dialog.show();}catch(Exception e){message("QR tidak dapat dibuka. Upload semula.");}}
  void editPaymentProvider(){String[] options={"Static DuitNow","HitPay","Maybank QRPayBiz","Custom API"};String current=getPreferences(0).getString("payment_provider","Static DuitNow");int checked=0;for(int i=0;i<options.length;i++)if(options[i].equals(current))checked=i;final int initial=checked;darkBuilder().setTitle("Pilih payment provider").setSingleChoiceItems(options,checked,null).setPositiveButton("Simpan",(d,w)->{AlertDialog a=(AlertDialog)d;int pos=a.getListView().getCheckedItemPosition();if(pos<0)pos=initial;getPreferences(0).edit().putString("payment_provider",options[pos]).apply();draw();}).setNegativeButton("Batal",null).show();}
  void editPaymentSetting(String key,String title,String hint){EditText input=new EditText(this);input.setSingleLine(true);input.setHint(hint);String old=getPreferences(0).getString(key,"");input.setText(old);if(key.contains("secret")||key.contains("api_key"))input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);styleInput(input);darkBuilder().setTitle(title).setView(input).setPositiveButton("Simpan",(d,w)->{getPreferences(0).edit().putString(key,input.getText().toString().trim()).apply();message(title+" disimpan pada device.");}).setNegativeButton("Batal",null).show();}
  void testPaymentConnection(){String provider=getPreferences(0).getString("payment_provider","Static DuitNow");if("Static DuitNow".equals(provider)){message("Static DuitNow tidak memerlukan API connection.");return;}String base=getPreferences(0).getString("payment_base_url","").trim(),key=getPreferences(0).getString("payment_api_key","").trim();if(base.isEmpty()||key.isEmpty()){message("Isi API Base URL dan API/Public Key dahulu.");return;}message("Konfigurasi "+provider+" tersedia. Ujian server sebenar akan aktif selepas endpoint provider disahkan.");}
  void deletePaymentConfig(){darkBuilder().setTitle("Padam konfigurasi merchant?").setMessage("Merchant ID, API key, secret dan URL akan dipadam dari telefon ini.").setPositiveButton("PADAM",(d,w)->{getPreferences(0).edit().remove("payment_provider").remove("payment_merchant_id").remove("payment_api_key").remove("payment_secret").remove("payment_base_url").apply();draw();message("Konfigurasi merchant dipadam.");}).setNegativeButton("Batal",null).show();}
  void stockTile(LinearLayout row,String title,String value){LinearLayout box=col();box.setGravity(Gravity.CENTER);box.setPadding(dp(5),dp(6),dp(5),dp(6));int back="BAKI".equals(title)?0xff9fc9f5:("TERJUAL".equals(title)?0xff4caf70:0xffffd54f);box.setBackground(shape(back,9));int tileLabel=0xff5d4930;int tileValue=0xff15263a;TextView label=text(title,9,tileLabel,true);label.setGravity(Gravity.CENTER);TextView figure=text(value,17,"BAKI".equals(title)?blue:tileValue,true);figure.setGravity(Gravity.CENTER);add(box,label,-1,-2);add(box,figure,-1,-2);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(57),1);p.setMargins(dp(2),0,dp(2),0);row.addView(box,p);}
  void stockEntryItem(int id){stockEntryItem(id,false);}
  void stockEntryItem(int id,boolean opening){if(unlimited(id)){message("Kuah kacang diurus mengikut liter, tanpa had stok unit.");return;}if(opening&&hasOpeningStock(id)){editOpeningStock(id);return;}
    if(!opening&&costUnit(id)<=0){darkBuilder().setTitle("Harga mentah belum ditetapkan").setMessage("Tetapkan harga mentah "+names[id]+" sebelum restock supaya duit keluar dikira dengan betul.").setPositiveButton("Edit harga",(d,w)->editMenuPrice(id)).setNegativeButton("Batal",null).show();return;}
    EditText input=new EditText(this);input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);input.setHint("Bilangan unit / cucuk");styleInput(input);int before=stock(id);
    darkBuilder().setTitle((opening?"Stok awal · ":"Restock · ")+names[id]).setMessage("Baki sebelum tambah: "+before+(opening?"\nStok awal tidak dikira sebagai belian baru.":"\nKos: "+money(costUnit(id))+" / "+costPack(id)+" unit. "+(supplierItem(id)?"Restock ini akan masuk ke Bayaran Pembekal sehingga ditanda Telah Dibayar.":"Restock akan dicatat sebagai duit keluar.")))
      .setView(input).setPositiveButton("Simpan",(dialog,w)->{try{int value=Integer.parseInt(input.getText().toString().trim());if(value<0||(!opening&&value==0)||value>100000)throw new NumberFormatException();if(opening&&hasOpeningStock(id))throw new Exception("Stok awal sudah direkod");int cost=opening?0:restockCost(id,value);JSONObject record=new JSONObject();record.put("time",timestamp());record.put("businessDay",businessDay());record.put("item",id);record.put("qty",value);record.put("type",opening?"opening":"restock");record.put("cost",cost);
        JSONArray stocks=entries("stock_entries");stocks.put(record);android.content.SharedPreferences.Editor editor=getPreferences(0).edit().putString("stock_entries",stocks.toString());
        if(!opening){if(supplierItem(id)){int group=sateItem(id)?-1:id;int due=supplierDue(group);long next=(long)due+cost;if(next>Integer.MAX_VALUE)throw new Exception("Jumlah pembekal terlalu besar");String key=supplierDueKey(group);editor.putInt(key,(int)next);JSONArray supplierLines=supplierItems(group);addSupplierItem(supplierLines,id,value,cost);editor.putString(supplierItemsKey(group),supplierLines.toString());}else{JSONObject expense=new JSONObject();expense.put("time",timestamp());expense.put("businessDay",businessDay());expense.put("month",businessDay().substring(0,7));expense.put("amount",-cost);expense.put("category","Restock · "+names[id]);expense.put("note",value+" unit × "+money(costUnit(id))+" / "+costPack(id)+" unit");JSONArray cash=entries("cash_entries");cash.put(expense);editor.putString("cash_entries",cash.toString());}}
        if(!editor.commit())throw new Exception("Gagal simpan rekod");draw();String costText=opening?"":(supplierItem(id)?"\nBayaran pembekal bertambah: "+money(cost)+"\nJumlah belum dibayar: "+money(supplierDue(id==5?5:id==6?6:-1)):"\nKos restock (duit keluar): "+money(cost));darkBuilder().setTitle("Stok berjaya dikemas kini").setMessage((opening?"Stok awal: "+value:"Baki "+before+" + restock "+value+" = "+stock(id))+"\n\n"+names[id]+" · Baki sekarang: "+stock(id)+costText).setPositiveButton("OK",null).show();}catch(Exception e){message("Gagal simpan. Semak bilangan stok dan cuba lagi.");}}).setNegativeButton("Batal",null).show();}
  void editOpeningStock(int id){int current=stockToday(id)[0];EditText input=new EditText(this);input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);input.setText(""+Math.max(0,current));input.setHint("0 dibenarkan");styleInput(input);darkBuilder().setTitle("Edit stok awal · "+names[id]).setMessage("Stok awal semasa: "+current+"\nBoleh ubah sehingga 0. Ini pembetulan stok dan tidak dikira jualan atau duit keluar.").setView(input).setPositiveButton("Simpan",(d,w)->{try{int target=Integer.parseInt(input.getText().toString().trim());if(target<0||target>100000)throw new Exception();int delta=target-current;if(delta!=0){JSONObject e=new JSONObject();e.put("time",timestamp());e.put("businessDay",businessDay());e.put("item",id);e.put("qty",delta);e.put("type","opening_adjustment");e.put("note","Edit stok awal");JSONArray list=entries("stock_entries");list.put(e);if(!getPreferences(0).edit().putString("stock_entries",list.toString()).commit())throw new Exception();}cachedStock=null;qty[id]=Math.min(qty[id],stock(id));draw();message("Stok awal dikemas kini kepada "+target);}catch(Exception e){message("Masukkan stok awal 0 hingga 100000");}}).setNegativeButton("Batal",null).show();}
  void chooseMenuSource(){darkBuilder().setTitle("Sumber menu").setItems(names,(d,id)->editMenuSource(id)).setNegativeButton("Tutup",null).show();}
  void editMenuSource(int id){String[] options={"Pembekal","Sendiri"};int checked=supplierItem(id)?0:1;darkBuilder().setTitle(names[id]+" · sumber").setSingleChoiceItems(options,checked,null).setPositiveButton("Simpan",(d,w)->{AlertDialog a=(AlertDialog)d;int pos=a.getListView().getCheckedItemPosition();getPreferences(0).edit().putBoolean("menu_supplier_"+id,pos==0).apply();message(names[id]+" ditetapkan sebagai "+options[pos]);}).setNegativeButton("Batal",null).show();}
  void chooseMenuPrice(){darkBuilder().setTitle("Harga menu").setItems(names,(d,id)->editMenuPrice(id)).setNegativeButton("Tutup",null).show();}
  EditText priceField(String label,String value){EditText field=new EditText(this);field.setSingleLine(true);field.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);field.setHint(label);field.setText(value);styleInput(field);return field;}
  int parseCents(EditText field){return new java.math.BigDecimal(field.getText().toString().trim()).movePointRight(2).intValueExact();}
  void editMenuPrice(int id){LinearLayout form=col();form.setPadding(dp(18),dp(3),dp(18),0);
    EditText selling=priceField("Harga jualan RM",String.format(Locale.US,"%.2f",prices[id]/100.0));EditText cost=priceField("Harga mentah RM",String.format(Locale.US,"%.2f",costUnit(id)/100.0));EditText pack=new EditText(this);pack.setSingleLine(true);pack.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);pack.setHint("Bilangan unit bagi harga mentah");pack.setText(""+costPack(id));styleInput(pack);
    add(form,text("Harga jualan (RM)",13,ink,true),-1,-2);add(form,selling,-1,-2);add(form,text("Harga mentah / belian (RM)",13,ink,true),-1,-2);add(form,cost,-1,-2);add(form,text("Bilangan unit bagi harga mentah (nasi: 30)",12,muted,false),-1,-2);add(form,pack,-1,-2);
    darkBuilder().setTitle("Edit harga · "+names[id]).setView(form).setPositiveButton("Simpan",(d,w)->{try{int sell=parseCents(selling),buy=parseCents(cost),units=Integer.parseInt(pack.getText().toString().trim());if(sell<=0||sell>10000000||buy<0||buy>1000000||units<=0||units>100000)throw new Exception();if(!getPreferences(0).edit().putInt("price_"+id,sell).putInt("cost_"+id,buy).putInt("cost_pack_"+id,units).commit())throw new Exception();prices[id]=sell;draw();message("Harga disimpan. Restock berikutnya akan guna harga mentah baru.");}catch(Exception e){message("Semak harga RM dan bilangan unit.");}}).setNegativeButton("Batal",null).show();}
  void editEmail(){EditText input=new EditText(this);input.setSingleLine(true);input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);input.setText(getPreferences(0).getString("receipt_email",""));input.setHint("nama@gmail.com");styleInput(input);
    darkBuilder().setTitle("Alamat e-mel pada resit").setView(input).setPositiveButton("Simpan",(d,w)->{String value=input.getText().toString().trim();if(!value.isEmpty()&&!android.util.Patterns.EMAIL_ADDRESS.matcher(value).matches()){message("Alamat e-mel tidak sah");return;}getPreferences(0).edit().putString("receipt_email",value).apply();message("E-mel disimpan");}).setNegativeButton("Batal",null).show();}
  void addMenu(){LinearLayout form=col();form.setPadding(dp(18),dp(5),dp(18),0);EditText name=new EditText(this),price=new EditText(this);name.setSingleLine(true);name.setHint("Nama menu");price.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);price.setHint("Harga RM, contoh 7.00");styleInput(name);styleInput(price);Spinner source=new Spinner(this);source.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"Pembekal","Sendiri"}));add(form,name,-1,-2);add(form,price,-1,-2);add(form,text("Sumber stok",12,muted,true),-1,-2);add(form,source,-1,-2);
    darkBuilder().setTitle("+ Tambah menu").setView(form).setPositiveButton("Simpan",(d,w)->{try{String title=name.getText().toString().trim();if(title.isEmpty()||title.length()>35)throw new Exception();int cents=new java.math.BigDecimal(price.getText().toString().trim()).movePointRight(2).intValueExact();if(cents<=0||cents>10000000)throw new Exception();JSONObject item=new JSONObject();item.put("name",title);item.put("price",cents);appendEntry("custom_menu",item);names=Arrays.copyOf(names,names.length+1);names[names.length-1]=title;icons=Arrays.copyOf(icons,icons.length+1);icons[icons.length-1]="🍽";prices=Arrays.copyOf(prices,prices.length+1);prices[prices.length-1]=cents;qty=Arrays.copyOf(qty,qty.length+1);getPreferences(0).edit().putBoolean("menu_supplier_"+(names.length-1),source.getSelectedItemPosition()==0).apply();draw();message("Menu baru disimpan. Masukkan stok sebelum jual.");}catch(Exception e){message("Semak nama dan harga menu");}}).setNegativeButton("Batal",null).show();}
  void deleteMenu(){
    ArrayList<Integer> ids=new ArrayList<>();ArrayList<String> labels=new ArrayList<>();
    for(int i=0;i<names.length;i++)if(!getPreferences(0).getBoolean("menu_hidden_"+i,false)){ids.add(i);labels.add(names[i]);}
    if(ids.isEmpty()){message("Tiada menu untuk dipadam");return;}
    darkBuilder().setTitle("Padam menu").setItems(labels.toArray(new String[0]),(d,which)->{
      int id=ids.get(which);darkBuilder().setTitle("Padam "+names[id]+"?")
      .setMessage("Menu ini akan dibuang daripada paparan jualan dan stok. Rekod jualan lama dikekalkan supaya laporan tidak rosak.")
      .setPositiveButton("PADAM",(x,w)->{getPreferences(0).edit().putBoolean("menu_hidden_"+id,true).remove("menu_image_"+id).commit();qty[id]=0;cachedStock=null;draw();message("Menu dipadam");})
      .setNegativeButton("Batal",null).show();
    }).setNegativeButton("Batal",null).show();
  }
  void pickMenuImage(){darkBuilder().setTitle("Gambar untuk menu").setItems(names,(d,id)->{imageItem=id;Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);intent.setType("image/*");intent.addCategory(Intent.CATEGORY_OPENABLE);try{startActivityForResult(intent,PICK_IMAGE);}catch(Exception e){message("Galeri tidak dapat dibuka");}}).show();}
  void today(){today.setText(money(getPreferences(0).getInt("sales_"+date(),0)));}
  void history(){darkBuilder().setTitle("Rekod & operasi").setItems(new String[]{"Jualan hari ini","Graf & jualan bulanan","Buka jualan / tambah stok","Baki stok","Duit masuk / keluar","Eksport rekod Excel / PDF","No. telefon resit","Tetapan & reset data"},(d,which)->{
    if(which==0)darkBuilder().setTitle("Rekod hari ini · "+date()).setMessage("Jualan: "+money(getPreferences(0).getInt("sales_"+date(),0))+"\nPesanan selesai: "+getPreferences(0).getInt("orders_"+date(),0)).setPositiveButton("Tutup",null).show();
    else if(which==1)monthly();else if(which==2)stockEntry();else if(which==3)stockBalance();else if(which==4)cashBook();else if(which==5)exportMenu();else if(which==6)editPhone();else settings();
  }).show();}
  void monthly(){Calendar now=Calendar.getInstance();String[] labels=new String[12],keys=new String[12];int[] totals=new int[12];int yearTotal=0;
    for(int i=0;i<12;i++){Calendar c=(Calendar)now.clone();c.add(Calendar.MONTH,i-11);keys[i]=new SimpleDateFormat("yyyy-MM",Locale.US).format(c.getTime());totals[i]=monthTotal(keys[i]);yearTotal+=totals[i];labels[i]=new SimpleDateFormat("MMM yy",new Locale("ms","MY")).format(c.getTime());}
    ScrollView scroll=new ScrollView(this);scroll.setVerticalScrollBarEnabled(false);LinearLayout panel=col();panel.setPadding(dp(16),dp(10),dp(16),dp(12));scroll.addView(panel);
    add(panel,text("JUMLAH 12 BULAN",12,muted,true),-1,-2);add(panel,text(money(yearTotal),28,blue,true),-1,-2);gap(panel,12);
    add(panel,new SalesChart(totals,labels),-1,210);gap(panel,10);
    for(int i=11;i>=0;i--){final String month=keys[i];LinearLayout row=row();row.setPadding(dp(10),dp(8),dp(10),dp(8));row.setBackground(shape(i%2==0?0xfff7f7f2:Color.WHITE,9));TextView name=text(labels[i],14,ink,true);row.addView(name,new LinearLayout.LayoutParams(0,dp(37),1));add(row,text(money(totals[i])+"  ›",14,blue,true),-2,-2);add(panel,row,-1,-2);row.setOnClickListener(v->{selectedMonth=month;draw();monthDetail(month);});}
    darkBuilder().setTitle("Rekod jualan bulanan").setView(scroll).setPositiveButton("Tutup",null).setNeutralButton("Eksport",(d,w)->exportMenu()).show();}
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
    int[] sold=soldBetween(start,end);ScrollView scroll=new ScrollView(this);scroll.setVerticalScrollBarEnabled(false);LinearLayout panel=col();panel.setPadding(dp(18),dp(12),dp(18),dp(14));panel.setBackgroundColor(cream);scroll.addView(panel);
    add(panel,text("DARI "+start+" HINGGA "+end,12,muted,true),-1,-2);gap(panel,9);
    LinearLayout metrics=row();metric(metrics,"TOTAL JUALAN",money(sales),blue,null);metric(metrics,"TOTAL PESANAN",""+orders,gold,null);add(panel,metrics,-1,-2);gap(panel,15);
    add(panel,text("JUMLAH ITEM TERJUAL",13,ink,true),-1,-2);gap(panel,8);
    for(int i=0;i<names.length;i++){TextView item=text(names[i]+"  ·  "+sold[i]+(i<3?" cucuk":i==4?" hidangan":" unit"),13,ink,false);item.setPadding(dp(10),dp(10),dp(10),dp(10));item.setBackground(shape(surface,9));add(panel,item,-1,-2);gap(panel,5);}
    gap(panel,11);add(panel,text("REKOD HARIAN",13,ink,true),-1,-2);gap(panel,8);add(panel,text(detail.length()==0?"Belum ada jualan untuk tempoh ini.":detail.toString(),12,ink,false),-1,-2);
    AlertDialog reportDialog=darkBuilder().setTitle("Pecahan jualan · total").setView(scroll).setPositiveButton("Tutup",null).setNeutralButton("Tukar tarikh",(d,w)->chooseRangeStart()).create();applyDarkReportDialog(reportDialog);}
  String salesDailyPrintText(String month){
    StringBuilder b=new StringBuilder();int totalSales=0,totalOrders=0;int days=daysInMonth(month);
    b.append(centerReceipt(getPreferences(0).getString("shop_name","WARISAN FROZEN"))).append(centerReceipt("CATATAN JUALAN HARIAN"));
    b.append("--------------------------------\n").append(line("Bulan",monthLabel(month))).append("--------------------------------\n");
    for(int d=1;d<=days;d++){String day=month+String.format(Locale.US,"-%02d",d);int sale=getPreferences(0).getInt("sales_"+day,0),orders=getPreferences(0).getInt("orders_"+day,0);if(sale==0&&orders==0)continue;totalSales+=sale;totalOrders+=orders;b.append(String.format(Locale.US,"%02d/%02d  %3d order  %s\n",d,Integer.parseInt(month.substring(5,7)),orders,money(sale)));}
    b.append("--------------------------------\n").append(line("TOTAL ORDER",String.valueOf(totalOrders))).append(line("TOTAL SALE",money(totalSales))).append("================================\n").append(centerReceipt("WARISAN POS")).append("\n");return b.toString();
  }
  void salesDailyReport(String month){
    int days=daysInMonth(month),totalSales=0,totalOrders=0;int[] daily=new int[days];String[] labels=new String[days];
    ScrollView scroll=new ScrollView(this);scroll.setVerticalScrollBarEnabled(false);LinearLayout panel=col();panel.setPadding(dp(16),dp(12),dp(16),dp(16));panel.setBackgroundColor(cream);scroll.addView(panel);
    for(int d=0;d<days;d++){String day=month+String.format(Locale.US,"-%02d",d+1);daily[d]=getPreferences(0).getInt("sales_"+day,0);int orders=getPreferences(0).getInt("orders_"+day,0);totalSales+=daily[d];totalOrders+=orders;labels[d]=(d==0||(d+1)%5==0||d==days-1)?String.valueOf(d+1):"";}
    add(panel,text("CATATAN JUALAN · "+monthLabel(month),13,gold,true),-1,-2);gap(panel,8);LinearLayout summary=row();metric(summary,"TOTAL SALE",money(totalSales),blue,null);metric(summary,"TOTAL ORDER",String.valueOf(totalOrders),gold,null);add(panel,summary,-1,-2);gap(panel,12);
    LinearLayout chart=col();chart.setPadding(dp(10),dp(10),dp(10),dp(8));chart.setBackground(shape(surface,12));add(chart,text("GRAF SALE HARIAN",11,muted,true),-1,-2);add(chart,new SalesChart(daily,labels),-1,175);add(panel,chart,-1,-2);gap(panel,12);
    add(panel,text("CATATAN HARIAN",12,muted,true),-1,-2);gap(panel,7);boolean any=false;
    for(int d=0;d<days;d++){String day=month+String.format(Locale.US,"-%02d",d+1);int orders=getPreferences(0).getInt("orders_"+day,0);if(daily[d]==0&&orders==0)continue;any=true;LinearLayout card=col();card.setPadding(dp(12),dp(9),dp(12),dp(9));card.setBackground(shape(surface,10));add(card,text(day,12,ink,true),-1,-2);add(card,text("Sale "+money(daily[d])+"   •   "+orders+" order",12,gold,true),-1,-2);add(panel,card,-1,-2);gap(panel,6);}
    if(!any)add(panel,text("Belum ada jualan untuk bulan ini.",12,muted,false),-1,-2);
    AlertDialog dlg=darkBuilder().setTitle("Catatan jualan harian").setView(scroll).setPositiveButton("PRINT",(d,w)->print(salesDailyPrintText(month))).setNeutralButton("Tukar bulan",(d,w)->chooseMonth()).setNegativeButton("Tutup",null).create();applyDarkReportDialog(dlg);
  }
  int daysInMonth(String key){try{Calendar c=Calendar.getInstance();c.setTime(new SimpleDateFormat("yyyy-MM-dd",Locale.US).parse(key+"-01"));return c.getActualMaximum(Calendar.DAY_OF_MONTH);}catch(Exception e){return 31;}}
  void chooseMonth(){Calendar now=Calendar.getInstance();String[] labels=new String[12],keys=new String[12];
    for(int i=0;i<12;i++){Calendar c=(Calendar)now.clone();c.add(Calendar.MONTH,-i);keys[i]=new SimpleDateFormat("yyyy-MM",Locale.US).format(c.getTime());labels[i]=monthLabel(keys[i]);}
    darkBuilder().setTitle("Pilih bulan jualan").setItems(labels,(d,i)->{selectedMonth=keys[i];draw();}).setNegativeButton("Batal",null).show();}
  int[] monthSold(String month){int[] sold=new int[names.length];JSONArray entries=entries("stock_sales");for(int i=0;i<entries.length();i++){JSONObject item=entries.optJSONObject(i);if(item==null)continue;String time=item.optString("time","");if(time.length()<10||!(time.substring(6,10)+"-"+time.substring(3,5)).equals(month))continue;JSONArray q=item.optJSONArray("qty");if(q!=null)for(int n=0;n<sold.length;n++)sold[n]+=q.optInt(n);}return sold;}
  class SalesChart extends View {final int[] values;final String[] labels;final Paint paint=new Paint(3);
    SalesChart(int[] v,String[] l){super(MainActivity.this);values=v;labels=l;}
    @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);float w=getWidth(),h=getHeight(),left=dp(7),right=w-dp(7),top=dp(12),bottom=h-dp(31);int max=1;for(int v:values)max=Math.max(max,v);float step=(right-left)/Math.max(1,values.length);
      paint.setColor(0xffe3e8e2);paint.setStrokeWidth(dp(1));for(int line=0;line<4;line++){float y=top+(bottom-top)*line/3f;canvas.drawLine(left,y,right,y,paint);}
      for(int i=0;i<values.length;i++){float x=left+step*(i+.5f),height=(bottom-top)*values[i]/max;paint.setColor(i==values.length-1?gold:blue);canvas.drawRoundRect(x-step*.34f,bottom-height,x+step*.34f,bottom,dp(3),dp(3),paint);paint.setColor(muted);paint.setTextSize(dp(values.length>12?8:9));paint.setTextAlign(Paint.Align.CENTER);if(!labels[i].isEmpty()){String label=labels[i];int split=label.indexOf('|');if(split>=0){String first=label.substring(0,split),second=label.substring(split+1);canvas.drawText(first,x,h-dp(18),paint);paint.setTextSize(dp(8));canvas.drawText(second,x,h-dp(6),paint);}else canvas.drawText(label.substring(0,Math.min(3,label.length())),x,h-dp(12),paint);}}
    }
  }
  int monthTotal(String month){int total=0;for(int day=1;day<=31;day++)total+=getPreferences(0).getInt("sales_"+month+String.format(Locale.US,"-%02d",day),0);return total;}
  void monthDetail(String month){StringBuilder detail=new StringBuilder();int orders=0;for(int day=1;day<=31;day++){String key=month+String.format(Locale.US,"-%02d",day);int amount=getPreferences(0).getInt("sales_"+key,0);orders+=getPreferences(0).getInt("orders_"+key,0);if(amount!=0)detail.append(key).append("  ·  ").append(money(amount)).append("\n");}
    darkBuilder().setTitle(month).setMessage("Jumlah: "+money(monthTotal(month))+"\nPesanan: "+orders+"\n\n"+(detail.length()==0?"Tiada jualan direkod.":detail.toString())).setPositiveButton("Tutup",null).show();}
  JSONArray entries(String key){try{return new JSONArray(getPreferences(0).getString(key,"[]"));}catch(JSONException e){return new JSONArray();}}
  void appendEntry(String key,JSONObject entry){JSONArray list=entries(key);list.put(entry);getPreferences(0).edit().putString(key,list.toString()).apply();}
  String timestamp(){return new SimpleDateFormat("dd/MM/yyyy HH:mm",Locale.US).format(new Date());}
  void stockEntry(){String[] options=new String[names.length+1];options[0]="Lihat baki stok";System.arraycopy(names,0,options,1,names.length);
    darkBuilder().setTitle("Stok · pilih menu").setItems(options,(d,selection)->{if(selection==0){stockBalance();return;}int id=selection-1;
      stockEntryItem(id);
    }).setNegativeButton("Tutup",null).show();}
  int[] soldQty(){int[] sold=new int[names.length];JSONArray list=entries("stock_sales");for(int i=0;i<list.length();i++){JSONObject record=list.optJSONObject(i);if(record==null)continue;JSONArray q=record.optJSONArray("qty");if(q!=null)for(int j=0;j<sold.length;j++)sold[j]+=q.optInt(j); }return sold;}
  void styleInput(EditText field){
    if(field==null)return;
    field.setTextColor(ink);
    field.setHintTextColor(muted);
    field.setTextSize(17);
    field.setPadding(dp(4),dp(8),dp(4),dp(8));
    field.setBackgroundTintList(android.content.res.ColorStateList.valueOf(gold));
  }
  void styleDarkDialogNow(AlertDialog dialog){
    if(dialog==null)return;
    Window window=dialog.getWindow();
    if(window!=null){
      window.setBackgroundDrawable(shape(surface,20));
      window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
      WindowManager.LayoutParams lp=window.getAttributes();lp.dimAmount=.68f;window.setAttributes(lp);
    }
    int titleId=getResources().getIdentifier("alertTitle","id","android");
    TextView title=dialog.findViewById(titleId);
    if(title!=null){title.setTextColor(ink);title.setTextSize(21);title.setTypeface(null,Typeface.BOLD);}
    TextView message=dialog.findViewById(android.R.id.message);
    if(message!=null){message.setTextColor(muted);message.setTextSize(15);message.setLineSpacing(0,1.08f);}
    ListView list=dialog.getListView();
    if(list!=null){list.setBackgroundColor(Color.TRANSPARENT);list.setDividerHeight(0);}
    Button positive=dialog.getButton(AlertDialog.BUTTON_POSITIVE);
    Button negative=dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
    Button neutral=dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
    if(positive!=null){positive.setTextColor(gold);positive.setTypeface(null,Typeface.BOLD);positive.setBackgroundColor(Color.TRANSPARENT);}
    if(negative!=null){negative.setTextColor(muted);negative.setTypeface(null,Typeface.BOLD);negative.setBackgroundColor(Color.TRANSPARENT);}
    if(neutral!=null){neutral.setTextColor(blue);neutral.setTypeface(null,Typeface.BOLD);neutral.setBackgroundColor(Color.TRANSPARENT);}
  }
  AlertDialog.Builder darkBuilder(){
    return new AlertDialog.Builder(this,AlertDialog.THEME_DEVICE_DEFAULT_DARK){
      @Override public AlertDialog show(){AlertDialog d=super.show();styleDarkDialogNow(d);return d;}
    };
  }
  void applyDarkReportDialog(AlertDialog dialog){
    dialog.setOnShowListener(v->styleDarkDialogNow(dialog));
    dialog.show();
  }
  void stockBalance(){String day=selectedStockDay;ScrollView scroll=new ScrollView(this);scroll.setVerticalScrollBarEnabled(false);LinearLayout panel=col();panel.setPadding(dp(16),dp(16),dp(16),dp(16));panel.setBackgroundColor(cream);scroll.addView(panel);
    LinearLayout hero=col();hero.setPadding(dp(16),dp(14),dp(16),dp(14));hero.setBackground(shape(0xff102f4d,16));add(hero,text("BAKI STOK",22,ink,true),-1,-2);gap(hero,3);add(hero,text(day,13,gold,true),-1,-2);add(hero,text("Awal + restock − terjual − rosak = baki",11,muted,false),-1,-2);add(panel,hero,-1,-2);gap(panel,12);
    int[] soldDay=soldBetween(day,day);for(int i=0;i<names.length;i++){if(getPreferences(0).getBoolean("menu_hidden_"+i,false))continue;LinearLayout c=col();c.setPadding(dp(14),dp(13),dp(14),dp(13));c.setBackground(shape(surface,15));LinearLayout title=row();title.addView(text(names[i],16,ink,true),new LinearLayout.LayoutParams(0,-2,1));
      if(unlimited(i)){add(title,chip(soldDay[i]+" TERJUAL",0xff203f61,blue),-2,-2);add(c,title,-1,-2);gap(c,4);add(c,text("Stok tanpa had unit",11,muted,false),-1,-2);}
      else{int[] st=stockForDay(i,day);int badgeBg=st[3]>0?0xff173f62:0xff4b2930;int badgeText=st[3]>0?blue:0xffff6b6b;add(title,chip("BAKI  "+st[3],badgeBg,badgeText),-2,-2);add(c,title,-1,-2);gap(c,10);
        LinearLayout stats=row();String[] lab={"AWAL","RESTOCK","TERJUAL","ROSAK"};int[] val={st[0],st[1],st[2],st[4]};for(int n=0;n<4;n++){LinearLayout box=col();box.setPadding(dp(5),dp(8),dp(5),dp(8));box.setBackground(shape(0xff20384f,10));TextView l=text(lab[n],8,muted,true);l.setGravity(Gravity.CENTER);add(box,l,-1,-2);TextView vv=text(""+val[n],16,n==3&&val[n]>0?0xffff6b6b:ink,true);vv.setGravity(Gravity.CENTER);add(box,vv,-1,-2);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);lp.setMargins(dp(2),0,dp(2),0);stats.addView(box,lp);}add(c,stats,-1,-2);}
      add(panel,c,-1,-2);gap(panel,9);}
    AlertDialog dialog=darkBuilder().setView(scroll).setPositiveButton("TUTUP",null).setNeutralButton(date().equals(day)?"TAMBAH STOK":"PILIH TARIKH",(d,w)->{if(date().equals(day))stockEntry();else chooseStockDay();}).create();applyDarkReportDialog(dialog);}
  void cashBook(){darkBuilder().setTitle("Duit masuk / keluar").setItems(new String[]{"Lihat rekod bulan ini","Catat duit masuk","Catat duit keluar"},(d,index)->{if(index==0)cashHistory();else cashEntry(index==1);}).show();}
  void cashEntry(boolean incoming){
    LinearLayout form=col();form.setPadding(dp(18),dp(12),dp(18),dp(8));form.setBackground(shape(surface,16));
    add(form,text(incoming?"DUIT MASUK":"DUIT KELUAR",11,incoming?blue:0xffff6b6b,true),-1,-2);gap(form,4);
    add(form,text(incoming?"Rekod duit tambahan yang masuk.":"Pilih kategori supaya untung kedai dan duit peribadi tidak bercampur.",12,muted,false),-1,-2);gap(form,14);
    add(form,text("JUMLAH (RM)",11,muted,true),-1,-2);
    EditText amount=new EditText(this);amount.setSingleLine(true);amount.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);amount.setHint("Contoh: 10.50");styleInput(amount);add(form,amount,-1,52);gap(form,10);
    String[] categories=incoming?new String[]{"Modal tambahan","Lain-lain"}:new String[]{"Sewa","Minyak kereta","Barang plastik","Sate mentah","Arang","Bahan lain","Rokok · Peribadi","Makan / Minum · Peribadi","Pengeluaran Peribadi","Lain-lain"};
    add(form,text("KATEGORI",11,muted,true),-1,-2);
    Spinner category=new Spinner(this);ArrayAdapter<String> adapter=new ArrayAdapter<String>(this,android.R.layout.simple_spinner_item,categories){
      @Override public View getView(int position,View convertView,android.view.ViewGroup parent){TextView t=(TextView)super.getView(position,convertView,parent);t.setTextColor(ink);t.setTextSize(15);t.setPadding(dp(12),0,dp(12),0);t.setBackground(shape(0xff203f61,10));return t;}
      @Override public View getDropDownView(int position,View convertView,android.view.ViewGroup parent){TextView t=(TextView)super.getDropDownView(position,convertView,parent);t.setTextColor(ink);t.setTextSize(15);t.setPadding(dp(16),dp(13),dp(16),dp(13));t.setBackgroundColor(surface);return t;}
    };category.setAdapter(adapter);category.setPopupBackgroundDrawable(shape(surface,12));add(form,category,-1,50);gap(form,10);
    add(form,text("CATATAN",11,muted,true),-1,-2);EditText note=new EditText(this);note.setSingleLine(true);note.setHint("Catatan tambahan (jika ada)");styleInput(note);add(form,note,-1,50);
    if(!incoming){gap(form,10);TextView info=text("Nota: hanya kategori bertanda Peribadi masuk PENGELUARAN PERIBADI. Lain-lain kekal kos operasi bisnes.",11,gold,true);info.setPadding(dp(10),dp(8),dp(10),dp(8));info.setBackground(shape(0xff293a4d,9));add(form,info,-1,-2);}
    AlertDialog dialog=darkBuilder().setTitle(incoming?"Catat duit masuk":"Catat duit keluar").setView(form).setPositiveButton("SIMPAN",null).setNegativeButton("BATAL",null).create();
    dialog.setOnShowListener(v->{styleDarkDialogNow(dialog);Button save=dialog.getButton(AlertDialog.BUTTON_POSITIVE);save.setOnClickListener(x->{try{java.math.BigDecimal rm=new java.math.BigDecimal(amount.getText().toString().trim());int cents=rm.movePointRight(2).intValueExact();if(cents<=0)throw new Exception();JSONObject entry=new JSONObject();entry.put("time",timestamp());entry.put("businessDay",businessDay());entry.put("month",businessDay().substring(0,7));entry.put("amount",incoming?cents:-cents);String chosen=categories[category.getSelectedItemPosition()];entry.put("category",chosen);entry.put("personal",!incoming&&chosen.contains("Peribadi"));entry.put("note",note.getText().toString().trim());appendEntry("cash_entries",entry);dialog.dismiss();draw();message("Catatan disimpan");}catch(Exception e){message("Masukkan jumlah RM yang sah");}});});dialog.show();}
  void cashHistory(){String month=selectedMonth,day=selectedCashDay;JSONArray list=entries("cash_entries");int incoming=0,outgoing=0;ScrollView scroll=new ScrollView(this);scroll.setVerticalScrollBarEnabled(false);LinearLayout panel=col();panel.setPadding(dp(14),dp(10),dp(14),dp(14));panel.setBackgroundColor(cream);scroll.addView(panel);String period=cashDailyMode?day:monthLabel(month);add(panel,text((cashDailyMode?"HARIAN · ":"BULANAN · ")+period,12,muted,true),-1,-2);gap(panel,8);ArrayList<JSONObject> shown=new ArrayList<>();
    for(int i=list.length()-1;i>=0;i--){JSONObject e=list.optJSONObject(i);if(e==null||legacySupplierRestock(e))continue;boolean match=cashDailyMode?day.equals(entryDay(e)):month.equals(e.optString("month"));if(!match)continue;int value=e.optInt("amount");if(value>0)incoming+=value;else outgoing-=value;shown.add(e);}
    LinearLayout totals=row();metric(totals,"MASUK",money(incoming),blue,null);metric(totals,"KELUAR",money(outgoing),ink,null);add(panel,totals,-1,-2);gap(panel,10);add(panel,text("BAKI DUIT OVERALL  "+money(totalMoneyBalance()),14,gold,true),-1,-2);gap(panel,12);if(shown.isEmpty())add(panel,text("Belum ada catatan untuk tempoh ini.",13,muted,false),-1,-2);
    for(JSONObject e:shown){int value=e.optInt("amount");LinearLayout card=col();card.setPadding(dp(12),dp(10),dp(12),dp(10));card.setBackground(shape(surface,11));LinearLayout r=row();r.addView(text(e.optString("time"),11,muted,true),new LinearLayout.LayoutParams(0,-2,1));add(r,text((value>0?"+ ":"− ")+money(Math.abs(value)),14,value>0?blue:0xffd45a55,true),-2,-2);add(card,r,-1,-2);gap(card,4);add(card,text(e.optString("category","Catatan"),13,ink,true),-1,-2);String note=e.optString("note");if(!note.isEmpty()&&!note.equals(e.optString("category")))add(card,text(note,12,muted,false),-1,-2);add(panel,card,-1,-2);gap(panel,7);}AlertDialog reportDialog=darkBuilder().setTitle("Duit masuk / keluar").setView(scroll).setPositiveButton("Tutup",null).create();applyDarkReportDialog(reportDialog);}
  void settings(){darkBuilder().setTitle("Tetapan data").setItems(new String[]{"No. telefon resit","Reset stok sahaja","Reset semua data (jualan, stok, duit & tetapan)"},(d,i)->{if(i==0)editPhone();else resetData(i==2);}).show();}
  void resetData(boolean all){String label=all?"SEMUA data jualan, stok, duit dan tetapan":"stok dan baki stok";
    darkBuilder().setTitle("Padam "+label+"?").setMessage(all?"Rekod tidak boleh dipulihkan selepas dipadam. Eksport rekod sebelum teruskan.":"Rekod jualan, duit, nombor telefon dan printer masih disimpan. Stok akan kembali 0.")
      .setPositiveButton("Teruskan",(d,w)->darkBuilder().setTitle("Sahkan reset").setMessage("Anda pasti mahu padam "+label+"?")
        .setPositiveButton("Ya, padam",(dd,ww)->{if(all)getPreferences(0).edit().clear().commit();else resetStockRecords();Arrays.fill(qty,0);if(all){loadMenu();activePage=4;}draw();message("Reset selesai");})
        .setNegativeButton("Batal",null).show()).setNegativeButton("Batal",null).show();}
  void exportMenu(){int current=Calendar.getInstance().get(Calendar.YEAR);String[] years=new String[2];years[0]="Tahun "+current;years[1]="Tahun "+(current-1);
    darkBuilder().setTitle("Eksport rekod jualan").setItems(years,(d,i)->{exportYear=current-i;
      darkBuilder().setTitle("Tahun "+exportYear).setItems(new String[]{"Excel (CSV)","PDF"},(dialog,kind)->{
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
                        darkBuilder()
                                .setTitle("Berjaya")
                                .setMessage("Google berjaya disambungkan")
                                .setPositiveButton("OK", null)
                                .show();
                        draw();
                    } else {
                        darkBuilder()
                                .setTitle("Firebase Error")
                                .setMessage(authTask.getException() == null
                                        ? "Unknown error"
                                        : authTask.getException().toString())
                                .setPositiveButton("OK", null)
                                .show();
                    }
                });

    } catch (ApiException e) {
        darkBuilder()
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
    if(request==PICK_TNG_QR){try{getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);getPreferences(0).edit().putString("tng_qr_uri",uri.toString()).apply();draw();message("QR Touch ’n Go disimpan");}catch(Exception e){message("Tidak dapat menyimpan QR Touch ’n Go");}return;}
    try(OutputStream out=getContentResolver().openOutputStream(uri)){
      if(out==null)throw new IOException("Gagal membuka fail");
      if(request==EXPORT_BACKUP){out.write(backupJson().toString(2).getBytes(StandardCharsets.UTF_8));message("Backup berjaya disimpan");return;}
      if(request==EXPORT_CSV)writeCsv(out,exportYear);else if(request==EXPORT_PDF)writePdf(out,exportYear);else return;
      message("Rekod "+exportYear+" berjaya disimpan");
    }catch(Exception e){darkBuilder().setTitle("Eksport gagal").setMessage(e.getMessage()).setPositiveButton("OK",null).show();}
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
    for(int i=0;i<=2;i++){float y=upper+(bottom-upper)*i/2f;p.setColor(0xff294563);c.drawLine(left,y,right,y,p);p.setColor(muted);c.drawText(pdfAxis(max*(2-i)/2),x,y+3,p);}
    float lastX=0,lastY=0;String[] months={"Jan","Feb","Mac","Apr","Mei","Jun","Jul","Ogo","Sep","Okt","Nov","Dis"};
    for(int i=0;i<values.length;i++){float px=left+(right-left)*i/Math.max(1,values.length-1),py=bottom-(bottom-upper)*values[i]/max;
      if(i>0){p.setColor(blue);p.setStrokeWidth(2.6f);c.drawLine(lastX,lastY,px,py,p);}p.setColor(gold);c.drawCircle(px,py,3.7f,p);
      if(!daily||i==0||(i+1)%5==0||i==values.length-1){p.setColor(muted);p.setTextSize(9);p.setTextAlign(Paint.Align.CENTER);c.drawText(daily?String.valueOf(i+1):months[i],px,bottom+19,p);p.setTextAlign(Paint.Align.LEFT);}
      lastX=px;lastY=py;
    }
  }
  void writePdf(OutputStream out,int year)throws IOException{PdfDocument pdf=new PdfDocument();try{Paint p=new Paint(3);int[] months=new int[12];int annual=0;
      for(int i=0;i<12;i++){months[i]=monthTotal(String.format(Locale.US,"%04d-%02d",year,i+1));annual+=months[i];}
      PdfDocument.Page cover=pdf.startPage(new PdfDocument.PageInfo.Builder(595,842,1).create());Canvas c=cover.getCanvas();c.drawColor(cream);
      p.setColor(blue);p.setTypeface(Typeface.DEFAULT_BOLD);p.setTextSize(23);c.drawText("WARISAN POS",34,52,p);
      p.setColor(muted);p.setTextSize(12);p.setTypeface(Typeface.DEFAULT);c.drawText("LAPORAN JUALAN TAHUN "+year,34,76,p);
      p.setColor(blue);c.drawRoundRect(34,97,561,158,11,11,p);p.setColor(Color.WHITE);p.setTextSize(12);c.drawText("JUMLAH JUALAN SETAHUN",50,120,p);p.setTypeface(Typeface.DEFAULT_BOLD);p.setTextSize(23);c.drawText(money(annual),50,148,p);
      p.setColor(ink);p.setTextSize(15);c.drawText("Trend jualan bulanan",34,195,p);pdfChart(c,p,months,34,205,527,245,false);
      p.setTextSize(12);p.setTypeface(Typeface.DEFAULT_BOLD);p.setColor(blue);c.drawText("PECAHAN 12 BULAN",34,485,p);
      String[] namesMonth={"Januari","Februari","Mac","April","Mei","Jun","Julai","Ogos","September","Oktober","November","Disember"};
      for(int i=0;i<12;i++){int column=i/6,row=i%6;float x=34+column*267,y=517+row*41;p.setColor(i%2==0?0xff17314a:0xff10283f);c.drawRoundRect(x,y-18,x+252,y+13,6,6,p);p.setColor(ink);p.setTextSize(11);p.setTypeface(Typeface.DEFAULT);c.drawText(namesMonth[i],x+10,y+2,p);p.setColor(blue);p.setTypeface(Typeface.DEFAULT_BOLD);c.drawText(money(months[i]),x+115,y+2,p);}
      pdf.finishPage(cover);
      int pageNo=1;
      for(int month=1;month<=12;month++){String key=String.format(Locale.US,"%04d-%02d",year,month);int[] daily=new int[daysInMonth(key)];int[] orders=new int[daily.length];int count=0;
        for(int day=0;day<daily.length;day++){String dateKey=key+String.format(Locale.US,"-%02d",day+1);daily[day]=getPreferences(0).getInt("sales_"+dateKey,0);orders[day]=getPreferences(0).getInt("orders_"+dateKey,0);count+=orders[day];}
        if(months[month-1]==0&&count==0)continue;
        PdfDocument.Page page=pdf.startPage(new PdfDocument.PageInfo.Builder(595,842,++pageNo).create());c=page.getCanvas();c.drawColor(cream);
        p.setColor(blue);p.setTypeface(Typeface.DEFAULT_BOLD);p.setTextSize(20);c.drawText("WARISAN POS  |  "+namesMonth[month-1]+" "+year,34,48,p);
        p.setColor(ink);p.setTextSize(13);c.drawText("Jualan: "+money(months[month-1])+"     Pesanan: "+count,34,77,p);
        p.setColor(blue);p.setTextSize(14);c.drawText("Trend jualan harian",34,112,p);pdfChart(c,p,daily,34,123,527,205,true);
        p.setColor(blue);p.setTypeface(Typeface.DEFAULT_BOLD);p.setTextSize(11);c.drawText("TARIKH",40,361,p);c.drawText("PESANAN",300,361,p);c.drawText("JUALAN",434,361,p);
        int y=379;for(int day=0;day<daily.length;day++){if(daily[day]==0&&orders[day]==0)continue;
          p.setColor(day%2==0?0xff17314a:cream);c.drawRect(34,y-12,560,y+5,p);p.setColor(ink);p.setTypeface(Typeface.DEFAULT);p.setTextSize(10);c.drawText(String.format(Locale.US,"%02d/%02d/%04d",day+1,month,year),40,y,p);c.drawText(String.valueOf(orders[day]),300,y,p);c.drawText(money(daily[day]),434,y,p);y+=14;}
        p.setColor(muted);p.setTextSize(9);c.drawText("Jumlah harian dan carta berdasarkan bayaran yang telah direkod.",34,816,p);pdf.finishPage(page);
      }
      pdf.writeTo(out);
    }finally{pdf.close();}}
  void editPhone(){EditText input=new EditText(this);input.setSingleLine(true);input.setInputType(android.text.InputType.TYPE_CLASS_PHONE);input.setText(getPreferences(0).getString("receipt_phone",""));input.setHint("Contoh: 012-345 6789");styleInput(input);
    darkBuilder().setTitle("No. telefon pada resit").setView(input).setPositiveButton("Simpan",(d,w)->{getPreferences(0).edit().putString("receipt_phone",input.getText().toString().trim()).apply();message("No. telefon resit disimpan");}).setNegativeButton("Batal",null).show();}
  void drawPendingOrders(){
    JSONArray pending=entries("pending_orders");int open=0;for(int i=0;i<pending.length();i++){JSONObject o=pending.optJSONObject(i);if(o!=null&&!o.optBoolean("paid",false)&&!o.optBoolean("cancelled",false))open++;}
    if(open==0)return;
    for(int i=pending.length()-1;i>=0;i--){JSONObject o=pending.optJSONObject(i);if(o==null||o.optBoolean("paid",false)||o.optBoolean("cancelled",false))continue;final int index=i;LinearLayout card=col();card.setPadding(dp(13),dp(11),dp(13),dp(11));card.setBackground(shape(surface,13));LinearLayout top=row();top.addView(text("ORDER #"+String.format(Locale.US,"%04d",o.optInt("order")),14,ink,true),new LinearLayout.LayoutParams(0,-2,1));add(top,text(money(o.optInt("total")),16,gold,true),-2,-2);add(card,top,-1,-2);gap(card,5);add(card,text(orderItemsText(o.optJSONArray("qty")),12,muted,false),-1,-2);gap(card,8);LinearLayout buttons=row();TextView pay=chip("BAYAR",blue,Color.WHITE),cancel=chip("BATAL ORDER",0xffc83f46,Color.WHITE),reprint=chip("PRINT ORDER",0xff203f61,gold);pay.setGravity(Gravity.CENTER);cancel.setGravity(Gravity.CENTER);reprint.setGravity(Gravity.CENTER);buttons.addView(pay,new LinearLayout.LayoutParams(0,dp(40),1));LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(0,dp(40),1);cp.leftMargin=dp(6);buttons.addView(cancel,cp);LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(0,dp(40),1);rp.leftMargin=dp(6);buttons.addView(reprint,rp);add(card,buttons,-1,-2);pay.setOnClickListener(v->payPendingOrder(index));cancel.setOnClickListener(v->cancelPendingOrder(index));reprint.setOnClickListener(v->print(orderSlip(o)));add(body,card,-1,-2);gap(body,8);}
  }
  String orderItemsText(JSONArray q){StringBuilder b=new StringBuilder();if(q!=null)for(int i=0;i<Math.min(q.length(),names.length);i++){int n=q.optInt(i);if(n>0){if(b.length()>0)b.append("  •  ");b.append(names[i]).append(" × ").append(n);}}return b.toString();}
  String orderSlip(JSONObject o){StringBuilder b=new StringBuilder();b.append(centerReceipt(getPreferences(0).getString("shop_name","WARISAN FROZEN"))).append("--------------------------------\n").append(centerReceipt("SLIP ORDER")).append(line("Order",String.format(Locale.US,"#%04d",o.optInt("order")))).append(line("Masa",o.optString("time"))).append("--------------------------------\n");JSONArray q=o.optJSONArray("qty");if(q!=null)for(int i=0;i<Math.min(q.length(),names.length);i++){int n=q.optInt(i);if(n>0)b.append(names[i]).append("\n").append(line("  "+n+" x "+money(prices[i]),money(n*prices[i])));}return b.append("--------------------------------\n").append(line("JUMLAH",money(o.optInt("total")))).append(centerReceipt("BELUM BAYAR")).append("\n\n").toString();}
  void confirmOrder(){if(sum()==0){message("Tambah menu dahulu");return;}for(int i=0;i<qty.length;i++)if(!unlimited(i)&&qty[i]>stock(i)){message("Stok "+names[i]+" tidak cukup. Semak pesanan.");return;}try{String orderDay=businessDay();int order=getPreferences(0).getInt("order_seq_"+orderDay,0)+1;JSONObject o=new JSONObject();o.put("time",timestamp());o.put("businessDay",orderDay);o.put("order",order);o.put("total",sum());o.put("paid",false);o.put("cancelled",false);JSONArray q=new JSONArray();for(int n:qty)q.put(n);o.put("qty",q);JSONArray list=entries("pending_orders");list.put(o);if(!getPreferences(0).edit().putString("pending_orders",list.toString()).putInt("order_seq_"+orderDay,order).putInt("orders_"+orderDay,getPreferences(0).getInt("orders_"+orderDay,0)+1).commit())throw new IOException();String slip=orderSlip(o);Arrays.fill(qty,0);draw();showOrderConfirmed(order,slip);}catch(Exception e){message("Order gagal disimpan.");}}
  void showOrderConfirmed(int order,String slip){LinearLayout box=col();box.setPadding(dp(20),dp(18),dp(20),dp(8));box.setBackground(shape(surface,18));TextView title=text("ORDER #"+String.format(Locale.US,"%04d",order)+" DISAHKAN",20,ink,true);add(box,title,-1,-2);gap(box,8);add(box,text("Order disimpan sebagai BELUM BAYAR.",14,muted,false),-1,-2);add(box,text("Print slip untuk dapur / rujukan jika perlu.",13,muted,false),-1,-2);gap(box,12);AlertDialog dialog=darkBuilder().setView(box).setPositiveButton("PRINT ORDER",(d,w)->print(slip)).setNegativeButton("TERUSKAN TANPA PRINT",null).create();dialog.setOnShowListener(v->{styleDarkDialogNow(dialog);dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(ink);});dialog.show();}
  void cancelPendingOrder(int index){JSONArray list=entries("pending_orders");JSONObject o=list.optJSONObject(index);if(o==null||o.optBoolean("paid",false)||o.optBoolean("cancelled",false)){message("Order ini sudah dibayar, dibatalkan atau tidak dijumpai.");return;}int order=o.optInt("order");LinearLayout box=col();box.setPadding(dp(20),dp(18),dp(20),dp(8));box.setBackground(shape(surface,18));add(box,text("BATAL ORDER #"+String.format(Locale.US,"%04d",order),20,0xffff6b6b,true),-1,-2);gap(box,8);add(box,text(orderItemsText(o.optJSONArray("qty")),13,ink,true),-1,-2);gap(box,6);add(box,text("Order belum dibayar. Jika dibatalkan, stok yang ditempah akan dipulangkan semula.",13,muted,false),-1,-2);AlertDialog dialog=darkBuilder().setView(box).setPositiveButton("BATAL ORDER",null).setNegativeButton("KEMBALI",null).create();dialog.setOnShowListener(v->{styleDarkDialogNow(dialog);Button yes=dialog.getButton(AlertDialog.BUTTON_POSITIVE);yes.setTextColor(0xffff6b6b);dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(ink);yes.setOnClickListener(x->{try{JSONArray fresh=entries("pending_orders");JSONObject target=fresh.optJSONObject(index);if(target==null||target.optBoolean("paid",false)||target.optBoolean("cancelled",false)){dialog.dismiss();message("Order sudah berubah. Sila semak semula.");return;}target.put("cancelled",true);target.put("cancelledTime",timestamp());fresh.put(index,target);String day=entryDay(target);android.content.SharedPreferences.Editor ed=getPreferences(0).edit().putString("pending_orders",fresh.toString());if(!day.isEmpty())ed.putInt("orders_"+day,Math.max(0,getPreferences(0).getInt("orders_"+day,0)-1));if(!ed.commit())throw new IOException();dialog.dismiss();draw();message("Order #"+String.format(Locale.US,"%04d",order)+" dibatalkan. Stok dipulangkan.");}catch(Exception e){message("Order gagal dibatalkan.");}});});dialog.show();}
  void showDarkChoice(String titleText,String subtitle,String[] labels,int[] colors,java.util.function.IntConsumer onPick){
    LinearLayout box=col();box.setPadding(dp(20),dp(18),dp(20),dp(14));box.setBackground(shape(surface,18));
    add(box,text(titleText,21,ink,true),-1,-2);if(subtitle!=null&&!subtitle.isEmpty()){gap(box,5);add(box,text(subtitle,12,muted,false),-1,-2);}gap(box,14);
    AlertDialog dialog=darkBuilder().setView(box).setNegativeButton("BATAL",null).create();
    for(int i=0;i<labels.length;i++){final int which=i;TextView choice=text(labels[i],15,Color.WHITE,true);choice.setGravity(Gravity.CENTER_VERTICAL);choice.setPadding(dp(15),0,dp(15),0);choice.setBackground(shape(colors!=null&&i<colors.length?colors[i]:0xff203f61,12));add(box,choice,-1,50);if(i<labels.length-1)gap(box,8);choice.setOnClickListener(v->{dialog.dismiss();onPick.accept(which);});}
    dialog.setOnShowListener(v->{styleDarkDialogNow(dialog);Button b=dialog.getButton(AlertDialog.BUTTON_NEGATIVE);if(b!=null){b.setTextColor(gold);b.setTypeface(null,Typeface.BOLD);}});dialog.show();
  }
  void payPendingOrder(int index){JSONArray list=entries("pending_orders");JSONObject o=list.optJSONObject(index);if(o==null||o.optBoolean("paid",false)||o.optBoolean("cancelled",false)){message("Order ini sudah dibayar, dibatalkan atau tidak dijumpai.");return;}int due=o.optInt("total");showDarkChoice("BAYAR ORDER #"+String.format(Locale.US,"%04d",o.optInt("order")),money(due),new String[]{"TUNAI","QR"},new int[]{blue,0xff203f61},which->{if(which==0)cashPaymentPending(index,due);else chooseQrPending(index,due);});}
  void cashPaymentPending(int index,int due){LinearLayout box=col();box.setPadding(dp(20),dp(18),dp(20),dp(10));box.setBackground(shape(surface,18));add(box,text("BAYARAN TUNAI",20,ink,true),-1,-2);gap(box,4);add(box,text("Jumlah  "+money(due),16,gold,true),-1,-2);gap(box,12);EditText received=new EditText(this);received.setSingleLine(true);received.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);received.setHint("Contoh 50.00");styleInput(received);add(box,received,-1,-2);TextView change=text("Masukkan tunai diterima",14,muted,true);gap(box,8);add(box,change,-1,-2);AlertDialog dialog=darkBuilder().setView(box).setPositiveButton("BAYARAN DITERIMA",null).setNegativeButton("BATAL",null).create();dialog.setOnShowListener(v->{styleDarkDialogNow(dialog);Button ok=dialog.getButton(AlertDialog.BUTTON_POSITIVE),back=dialog.getButton(AlertDialog.BUTTON_NEGATIVE);ok.setTextColor(gold);back.setTextColor(muted);ok.setEnabled(false);received.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int b,int c){}public void onTextChanged(CharSequence s,int a,int b,int c){int tendered=cashCents(received);boolean enough=tendered>=due;ok.setEnabled(enough);change.setText(enough?"BAKI  "+money(tendered-due):tendered<0?"Masukkan tunai diterima":"Kurang  "+money(due-tendered));change.setTextColor(enough?gold:muted);}public void afterTextChanged(android.text.Editable e){}});ok.setOnClickListener(x->{int tendered=cashCents(received);if(tendered<due)return;dialog.dismiss();completePendingPayment(index,"Tunai",tendered);});});dialog.show();}
  void chooseQrPending(int index,int due){showDarkChoice("PILIH QR",money(due),new String[]{"TOUCH ’N GO  ·  MANUAL","BANK  ·  DYNAMIC / AUTO CONFIRM"},new int[]{0xff203f61,blue},which->{if(which==0)showTngQrPending(index,due);else showBankQrPending(index,due);});}
  void showTngQrPending(int index,int due){String saved=getPreferences(0).getString("tng_qr_uri","");if(saved.isEmpty()){message("QR Touch ’n Go belum dimasukkan. Pergi Setting → Payment / DuitNow → Upload / Tukar QR Touch ’n Go.");return;}try{LinearLayout box=col();box.setPadding(dp(18),dp(16),dp(18),dp(8));box.setBackground(shape(surface,18));add(box,text("TOUCH ’N GO",20,ink,true),-1,-2);add(box,text(money(due)+"  ·  Pengesahan manual",14,gold,true),-1,-2);gap(box,10);ImageView image=new ImageView(this);loadQrFullResolution(image,saved);add(box,image,-1,400);gap(box,7);add(box,text("Tekan Bayaran Diterima selepas semak bayaran customer.",12,muted,false),-1,-2);AlertDialog dialog=darkBuilder().setView(box).setPositiveButton("BAYARAN DITERIMA",(d,w)->completePendingPayment(index,"Touch ’n Go",due)).setNegativeButton("BATAL",null).create();dialog.setOnShowListener(v->{styleDarkDialogNow(dialog);});dialog.show();}catch(Exception e){message("QR Touch ’n Go tidak dapat dibuka. Upload semula di Setting.");}}
  void showBankQrPending(int index,int due){String base=getPreferences(0).getString("payment_base_url","").trim(),key=getPreferences(0).getString("payment_api_key","").trim(),merchant=getPreferences(0).getString("payment_merchant_id","").trim();if(base.isEmpty()||key.isEmpty()||merchant.isEmpty()){message("Bank Dynamic QR belum lengkap. Isi Merchant ID, API/Public Key dan API Base URL di Setting → Payment / DuitNow.");return;}LinearLayout box=col();box.setPadding(dp(20),dp(18),dp(20),dp(10));box.setBackground(shape(surface,18));add(box,text("BANK DYNAMIC QR",20,ink,true),-1,-2);add(box,text(money(due),17,gold,true),-1,-2);gap(box,10);add(box,text("Konfigurasi merchant sudah ada. Auto-confirm hanya akan aktif selepas endpoint create-payment dan semakan status/callback provider sebenar disambungkan.",13,muted,false),-1,-2);AlertDialog dialog=darkBuilder().setView(box).setPositiveButton("OK",null).create();dialog.setOnShowListener(v->{styleDarkDialogNow(dialog);});dialog.show();}
  String paymentReceiptFor(int[] purchased,String method,int due,int order,int tendered){int[] before=qty;try{qty=Arrays.copyOf(purchased,purchased.length);return receipt(method,due,order,tendered);}finally{qty=before;}}
  void completePendingPayment(int index,String method,int tendered){try{JSONArray pending=entries("pending_orders");JSONObject o=pending.optJSONObject(index);if(o==null||o.optBoolean("paid",false))throw new Exception();int due=o.optInt("total"),order=o.optInt("order");JSONArray q=o.optJSONArray("qty");int[] purchased=new int[names.length];for(int i=0;i<purchased.length;i++)purchased[i]=q==null?0:q.optInt(i);JSONObject sale=new JSONObject();sale.put("time",timestamp());String originalDay=entryDay(o);sale.put("businessDay",originalDay);sale.put("orderTime",o.optString("time",""));sale.put("paymentDate",date());sale.put("order",order);sale.put("total",due);sale.put("method",method);sale.put("refunded",false);sale.put("paid",true);int cost=0;for(int i=0;i<purchased.length;i++)if(purchased[i]>0&&!unlimited(i))cost+=restockCost(i,purchased[i]);sale.put("costTotal",cost);JSONArray sq=new JSONArray();for(int n:purchased)sq.put(n);sale.put("qty",sq);JSONArray sales=entries("stock_sales");sales.put(sale);o.put("paid",true);o.put("paidTime",timestamp());o.put("method",method);pending.put(index,o);if(!getPreferences(0).edit().putString("pending_orders",pending.toString()).putString("stock_sales",sales.toString()).putInt("sales_"+originalDay,getPreferences(0).getInt("sales_"+originalDay,0)+due).commit())throw new IOException();String printed=paymentReceiptFor(purchased,method,due,order,tendered);playPaymentSound();draw();previewReceipt(printed,purchased,method,due,order,tendered);}catch(Exception e){message("Bayaran gagal direkod. Cuba semula.");}}

  void pay(){if(sum()==0){Toast.makeText(this,"Tambah menu dahulu",Toast.LENGTH_SHORT).show();return;}for(int i=0;i<qty.length;i++)if(!unlimited(i)&&qty[i]>stock(i)){message("Stok "+names[i]+" tidak cukup. Semak pesanan.");draw();return;}final int due=sum();
    darkBuilder().setTitle("Bayaran "+money(due)).setItems(new String[]{"Tunai","QR / DuitNow"},(dialog,index)->{if(index==0)cashPayment(due);else showPaymentQr(due);}).setNegativeButton("Batal",null).show();}
  int cashCents(EditText input){try{return new java.math.BigDecimal(input.getText().toString().trim()).movePointRight(2).intValueExact();}catch(Exception e){return -1;}}
  void cashPayment(int due){LinearLayout form=col();form.setPadding(dp(20),dp(3),dp(20),0);
    add(form,text("JUMLAH BELIAN  "+money(due),16,blue,true),-1,-2);gap(form,10);
    EditText received=new EditText(this);received.setSingleLine(true);received.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);received.setHint("Duit pelanggan beri · contoh 50.00");styleInput(received);add(form,received,-1,-2);gap(form,10);
    TextView change=text("Masukkan jumlah tunai diterima",17,muted,true);add(form,change,-1,-2);
    AlertDialog dialog=darkBuilder().setTitle("Bayaran tunai").setView(form).setPositiveButton("Bayaran diterima",null).setNegativeButton("Batal",null).create();
    dialog.setOnShowListener(v->{styleDarkDialogNow(dialog);Button next=dialog.getButton(AlertDialog.BUTTON_POSITIVE);next.setEnabled(false);
      received.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){int tendered=cashCents(received);boolean enough=tendered>=due;next.setEnabled(enough);change.setText(enough?"BAKI PULANGAN  "+money(tendered-due):tendered<0?"Masukkan jumlah tunai diterima":"Tunai belum cukup · kurang "+money(due-tendered));change.setTextColor(enough?blue:muted);}public void afterTextChanged(android.text.Editable value){}});
      next.setOnClickListener(view->{int tendered=cashCents(received);if(tendered<due){message("Tunai diterima tidak mencukupi");return;}dialog.dismiss();completePayment("Tunai",due,tendered);});});dialog.show();}
  void showPaymentQr(int due){ScrollView scroll=new ScrollView(this);scroll.setVerticalScrollBarEnabled(false);scroll.setFillViewport(false);ImageView image=new ImageView(this);image.setImageResource(R.drawable.qr_frozen_ld);image.setAdjustViewBounds(true);image.setScaleType(ImageView.ScaleType.FIT_CENTER);scroll.addView(image,new ScrollView.LayoutParams(-1,-2));
    darkBuilder().setTitle("QR DuitNow · Frozen LD").setMessage("Jumlah: "+money(due)+"\nTunjukkan QR ini kepada pelanggan. Sahkan selepas bayaran diterima.").setView(scroll)
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
      String saleDay=businessDay();int order=getPreferences(0).getInt("orders_"+saleDay,0)+1;
      int[] purchased=Arrays.copyOf(qty,qty.length);
      String printed=receipt(method,due,order,tendered);
      try{JSONObject sale=new JSONObject();sale.put("time",timestamp());sale.put("businessDay",saleDay);sale.put("order",order);sale.put("total",due);sale.put("method",method);sale.put("refunded",false);int saleCost=0;for(int id=0;id<purchased.length;id++)if(purchased[id]>0&&!unlimited(id))saleCost+=restockCost(id,purchased[id]);sale.put("costTotal",saleCost);JSONArray soldItems=new JSONArray();for(int amount:purchased)soldItems.put(amount);sale.put("qty",soldItems);JSONArray sales=entries("stock_sales");sales.put(sale);
        if(!getPreferences(0).edit().putInt("sales_"+saleDay,getPreferences(0).getInt("sales_"+saleDay,0)+due).putInt("orders_"+saleDay,order).putString("stock_sales",sales.toString()).commit())throw new IOException("Gagal simpan");
      }catch(Exception e){message("Bayaran gagal direkod. Semak simpanan telefon.");return;}
      playPaymentSound();
      Arrays.fill(qty,0);draw();
      previewReceipt(printed,purchased,method,due,order,tendered);
  }

  int saleTotal(JSONObject sale){
    int stored=sale.optInt("total",-1);if(stored>=0)return stored;JSONArray q=sale.optJSONArray("qty");int total=0;if(q!=null)for(int i=0;i<Math.min(q.length(),prices.length);i++)total+=q.optInt(i)*prices[i];return total;
  }
  String saleItemsText(JSONObject sale){
    JSONArray q=sale.optJSONArray("qty");StringBuilder b=new StringBuilder();if(q!=null)for(int i=0;i<Math.min(q.length(),names.length);i++){int n=q.optInt(i);if(n>0){if(b.length()>0)b.append("\n");b.append(names[i]).append(" × ").append(n).append("  ·  ").append(money(n*prices[i]));}}return b.length()==0?"Tiada butiran item":b.toString();
  }
  void saleHistoryChooseDate(){pickDay("Pilih tarikh history",date(),value->saleHistory(value));}
  void saleHistory(String day){
    JSONArray sales=entries("stock_sales");ScrollView scroll=new ScrollView(this);scroll.setVerticalScrollBarEnabled(false);LinearLayout panel=col();panel.setPadding(dp(14),dp(14),dp(14),dp(14));panel.setBackgroundColor(cream);scroll.addView(panel);
    LinearLayout hero=col();hero.setPadding(dp(14),dp(12),dp(14),dp(12));hero.setBackground(shape(0xff102f4d,14));add(hero,text("HISTORY CUSTOMER",20,ink,true),-1,-2);add(hero,text(day,12,gold,true),-1,-2);add(hero,text("Tekan transaksi untuk lihat pembelian atau buat refund.",11,muted,false),-1,-2);add(panel,hero,-1,-2);gap(panel,10);
    int found=0;for(int i=sales.length()-1;i>=0;i--){JSONObject sale=sales.optJSONObject(i);if(sale==null||!day.equals(entryDay(sale)))continue;found++;final int saleIndex=i;int total=saleTotal(sale);boolean refunded=sale.optBoolean("refunded",false);
      LinearLayout card=col();card.setPadding(dp(13),dp(11),dp(13),dp(11));card.setBackground(shape(surface,13));LinearLayout top=row();String time=sale.optString("time");String clock=time.length()>=16?time.substring(11):time;top.addView(text("CUSTOMER #"+sale.optInt("order",found)+"  ·  "+clock,12,muted,true),new LinearLayout.LayoutParams(0,-2,1));add(top,text(money(total),15,refunded?0xffff6b6b:blue,true),-2,-2);add(card,top,-1,-2);gap(card,5);add(card,text(saleItemsText(sale),12,ink,false),-1,-2);gap(card,6);TextView status=chip(refunded?"REFUNDED":"LIHAT / REFUND",refunded?0xff4b2930:0xff203f61,refunded?0xffff6b6b:gold);status.setGravity(Gravity.CENTER);add(card,status,-1,35);if(!refunded)card.setOnClickListener(v->saleDetailRefund(saleIndex));add(panel,card,-1,-2);gap(panel,8);}
    if(found==0)add(panel,text("Tiada pembelian customer pada tarikh ini.",13,muted,false),-1,-2);
    AlertDialog dialog=darkBuilder().setView(scroll).setPositiveButton("TUTUP",null).setNeutralButton("TUKAR TARIKH",(d,w)->saleHistoryChooseDate()).create();applyDarkReportDialog(dialog);
  }
  void saleDetailRefund(int saleIndex){
    JSONArray sales=entries("stock_sales");JSONObject sale=sales.optJSONObject(saleIndex);if(sale==null)return;int total=saleTotal(sale);LinearLayout box=col();box.setPadding(dp(16),dp(12),dp(16),0);box.setBackgroundColor(cream);add(box,text(saleItemsText(sale),14,ink,false),-1,-2);gap(box,10);add(box,text("Jumlah bayaran: "+money(total),16,blue,true),-1,-2);add(box,text("Refund penuh akan direkod sebagai DUIT KELUAR dan item dipindahkan ke rekod STOK ROSAK dengan alasan Refund Customer.",11,muted,false),-1,-2);
    AlertDialog dialog=darkBuilder().setView(box).setPositiveButton("REFUND PENUH",null).setNegativeButton("BATAL",null).create();dialog.setOnShowListener(v->{styleDarkDialogNow(dialog);Button ok=dialog.getButton(AlertDialog.BUTTON_POSITIVE);ok.setTextColor(0xffff6b6b);dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(gold);ok.setOnClickListener(x->{refundSale(saleIndex);dialog.dismiss();});});dialog.show();
  }
  void refundSale(int saleIndex){
    try{
      JSONArray sales=entries("stock_sales");JSONObject sale=sales.optJSONObject(saleIndex);if(sale==null||sale.optBoolean("refunded",false)){message("Transaksi ini sudah direfund.");return;}int refund=saleTotal(sale);JSONArray q=sale.optJSONArray("qty");String now=timestamp();
      JSONArray damage=entries("stock_damage");if(q!=null)for(int i=0;i<Math.min(q.length(),names.length);i++){int n=q.optInt(i);if(n<=0||unlimited(i))continue;JSONObject d=new JSONObject();d.put("time",now);d.put("item",i);d.put("name",names[i]);d.put("qty",n);d.put("cost",restockCost(i,n));d.put("unitCost",costUnit(i));d.put("pack",costPack(i));d.put("note","Refund Customer");d.put("refund",true);damage.put(d);}
      JSONArray cash=entries("cash_entries");JSONObject out=new JSONObject();out.put("time",now);out.put("businessDay",entryDay(sale));out.put("month",entryDay(sale).substring(0,7));out.put("amount",-refund);out.put("category","Refund Customer");out.put("note","Refund transaksi "+sale.optString("time"));cash.put(out);
      sale.put("refunded",true);sale.put("refundTime",now);sale.put("refundAmount",refund);sales.put(saleIndex,sale);
      android.content.SharedPreferences.Editor ed=getPreferences(0).edit().putString("stock_sales",sales.toString()).putString("stock_damage",damage.toString()).putString("cash_entries",cash.toString());if(!ed.commit())throw new IOException();
      draw();message("Refund "+money(refund)+" direkod. Duit keluar dan stok rosak telah dikemas kini.");
    }catch(Exception e){message("Refund gagal direkod.");}
  }
  void receiptRule(LinearLayout sheet){View rule=new View(this);rule.setBackgroundColor(0xffe5e7e2);LinearLayout.LayoutParams lp=params(-1,1);lp.setMargins(0,dp(13),0,dp(13));sheet.addView(rule,lp);}
  void receiptRow(LinearLayout sheet,String label,String value,boolean highlight){
    LinearLayout r=row();int receiptText=ink,receiptMuted=muted;TextView left=text(label,highlight?17:13,highlight?blue:receiptMuted,highlight);TextView right=text(value,highlight?20:13,highlight?blue:receiptText,true);
    r.addView(left,new LinearLayout.LayoutParams(0,-2,1));add(r,right,-2,-2);add(sheet,r,-1,-2);
  }
  void receiptRowLight(LinearLayout sheet,String label,String value,boolean highlight){
    LinearLayout r=row();int receiptText=0xff26384c,receiptMuted=0xff66788a;TextView left=text(label,highlight?17:13,highlight?blue:receiptMuted,highlight);TextView right=text(value,highlight?20:13,highlight?blue:receiptText,true);
    r.addView(left,new LinearLayout.LayoutParams(0,-2,1));add(r,right,-2,-2);add(sheet,r,-1,-2);
  }
  void previewReceipt(String printed,int[] purchased,String method,int due,int order,int tendered){
    // Customer receipt uses the same printer-paper preview language as supplier receipts.
    LinearLayout previewFrame=col();previewFrame.setPadding(dp(12),dp(4),dp(12),dp(8));previewFrame.setBackground(shape(cream,20));
    ScrollView scroll=new ScrollView(this);scroll.setVerticalScrollBarEnabled(false);scroll.setFillViewport(true);previewFrame.addView(scroll,new LinearLayout.LayoutParams(-1,-2));
    int receiptText=0xff26384c,receiptMuted=0xff66788a;
    LinearLayout sheet=col();sheet.setPadding(dp(20),dp(14),dp(20),dp(16));sheet.setBackground(shape(Color.WHITE,10));scroll.addView(sheet);
    ImageView logo=new ImageView(this);logo.setImageResource(R.drawable.warisan_logo);makeCircle(logo);
    LinearLayout logoRow=row();logoRow.setGravity(Gravity.CENTER);add(logoRow,logo,74,74);add(sheet,logoRow,-1,-2);gap(sheet,5);
    TextView brand=text(getPreferences(0).getString("shop_name","WARISAN FROZEN"),17,blue,true);brand.setGravity(Gravity.CENTER);add(sheet,brand,-1,-2);
    String phone=getPreferences(0).getString("receipt_phone","");
    TextView contact=text(phone.isEmpty()?"No. telefon belum diisi":"Tel: "+phone,11,receiptMuted,false);contact.setGravity(Gravity.CENTER);add(sheet,contact,-1,-2);
    String email=getPreferences(0).getString("receipt_email","");if(!email.isEmpty()){TextView mail=text(email,11,receiptMuted,false);mail.setGravity(Gravity.CENTER);add(sheet,mail,-1,-2);}
    receiptRule(sheet);
    receiptRowLight(sheet,"RESIT BAYARAN",String.format(Locale.US,"#%04d",order),false);gap(sheet,4);
    receiptRowLight(sheet,"Tarikh",new SimpleDateFormat("dd/MM/yyyy HH:mm",Locale.US).format(new Date()),false);
    receiptRule(sheet);
    for(int i=0;i<purchased.length;i++)if(purchased[i]>0){receiptRowLight(sheet,names[i],money(prices[i]*purchased[i]),false);
      TextView details=text(purchased[i]+" × "+money(prices[i]),11,receiptMuted,false);add(sheet,details,-1,-2);gap(sheet,10);}
    receiptRule(sheet);receiptRowLight(sheet,"JUMLAH",money(due),true);gap(sheet,8);
    receiptRowLight(sheet,"Kaedah bayaran",method,false);if("Tunai".equals(method)){gap(sheet,6);receiptRowLight(sheet,"Tunai diterima",money(tendered),false);gap(sheet,6);receiptRowLight(sheet,"BAKI PULANGAN",money(tendered-due),true);}receiptRule(sheet);
    TextView thanks=text("TERIMA KASIH · SILA DATANG LAGI",12,receiptMuted,true);thanks.setGravity(Gravity.CENTER);add(sheet,thanks,-1,-2);
    String owner=ownerDisplay();if(!owner.isEmpty()){gap(sheet,8);TextView owned=text("DIMILIKI OLEH : "+owner,10,receiptMuted,true);owned.setGravity(Gravity.CENTER);add(sheet,owned,-1,-2);}
    AlertDialog dialog=darkBuilder().setTitle("PREVIEW RESIT CUSTOMER").setView(previewFrame)
      .setPositiveButton("Cetak resit",(d,b)->print(printed))
      .setNeutralButton("Share",(d,b)->shareReceiptImage(sheet,"resit-customer"))
      .setNegativeButton("Tutup",null).create();applyDarkReportDialog(dialog);
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
  void editShopInfo(String key,String title,String fallback){EditText input=new EditText(this);input.setSingleLine(!"company_hq".equals(key));input.setText(getPreferences(0).getString(key,fallback));input.setHint(title);styleInput(input);darkBuilder().setTitle(title).setView(input).setPositiveButton("Simpan",(d,w)->{getPreferences(0).edit().putString(key,input.getText().toString().trim()).apply();message(title+" disimpan");}).setNegativeButton("Batal",null).show();}
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
      darkBuilder().setTitle("Pilih printer yang sudah dipair").setItems(labels,(d,index)->{
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
    if(address.isEmpty()){pendingReceipt=receipt;darkBuilder().setTitle("Printer tidak dijumpai").setMessage("Order / bayaran sudah disimpan. Anda boleh pilih printer atau teruskan tanpa print. Proses tidak akan dibatalkan.").setPositiveButton("PILIH PRINTER",(d,w)->choosePrinter()).setNegativeButton("TERUSKAN TANPA PRINT",(d,w)->{pendingReceipt=null;}).show();return;}
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
      }catch(Exception e){String error=e.getMessage();runOnUiThread(()->darkBuilder().setTitle("Cetakan gagal").setMessage((error==null?"Printer tidak dapat disambungkan.":error)+"\n\nOrder / bayaran kekal disimpan. Anda boleh teruskan tanpa print.").setPositiveButton("TERUSKAN",null).show());}
      finally{if(socket!=null)try{socket.close();}catch(Exception ignored){}}
    }).start();
  }

  int damagedQty(int id){int n=0;JSONArray list=entries("stock_damage");for(int i=0;i<list.length();i++){JSONObject e=list.optJSONObject(i);if(e!=null&&!e.optBoolean("stockReset",false)&&e.optInt("item",-1)==id)n+=e.optInt("qty");}return n;}
  int damageTotal(String month){int n=0;JSONArray list=entries("stock_damage");for(int i=0;i<list.length();i++){JSONObject e=list.optJSONObject(i);if(e!=null&&entryDay(e).startsWith(month+"-"))n+=e.optInt("cost");}return n;}
  void resetStockRecords(){try{JSONArray losses=entries("stock_damage");for(int i=0;i<losses.length();i++)losses.getJSONObject(i).put("stockReset",true);getPreferences(0).edit().remove("stock_entries").remove("stock_sales").putString("stock_damage",losses.toString()).commit();}catch(Exception e){message("Reset gagal");}}
  void damageEntry(int id){if(costUnit(id)<=0){message("Tetapkan harga mentah dahulu untuk kira kerugian");editMenuPrice(id);return;}if(stock(id)==0){message("Tiada stok untuk direkod rosak");return;}LinearLayout form=col();form.setPadding(dp(18),dp(6),dp(18),0);
    add(form,text("Baki: "+stock(id)+" unit · Kos "+money(costUnit(id))+" / "+costPack(id)+" unit",13,ink,true),-1,-2);
    EditText units=new EditText(this);units.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);units.setHint("Bilangan stok rosak");styleInput(units);add(form,units,-1,-2);
    EditText reason=new EditText(this);reason.setHint("Sebab: basi / jatuh / terbakar");styleInput(reason);add(form,reason,-1,-2);
    TextView preview=text("Kerugian ikut harga mentah semasa",13,0xffb54743,true);add(form,preview,-1,-2);
    units.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void afterTextChanged(android.text.Editable e){}public void onTextChanged(CharSequence s,int a,int b,int c){try{int n=Integer.parseInt(s.toString());preview.setText(n>0&&n<=stock(id)?"Nilai stok rosak: "+money(restockCost(id,n)):"Bilangan mesti 1 hingga "+stock(id));}catch(Exception e){preview.setText("Masukkan bilangan stok rosak");}}});
    AlertDialog dialog=darkBuilder().setTitle("Stok rosak · "+names[id]).setView(form).setPositiveButton("Simpan",null).setNegativeButton("Batal",null).create();
    dialog.setOnShowListener(v->{styleDarkDialogNow(dialog);dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view->{try{int n=Integer.parseInt(units.getText().toString().trim());String note=reason.getText().toString().trim();if(n<=0||n>stock(id)||note.isEmpty()){message("Semak bilangan dan isi sebab rosak");return;}
      JSONObject e=new JSONObject();e.put("time",timestamp());e.put("businessDay",businessDay());e.put("item",id);e.put("name",names[id]);e.put("qty",n);e.put("cost",restockCost(id,n));e.put("unitCost",costUnit(id));e.put("pack",costPack(id));e.put("note",note);JSONArray list=entries("stock_damage");list.put(e);
      if(!getPreferences(0).edit().putString("stock_damage",list.toString()).commit())throw new IOException();qty[id]=Math.min(qty[id],stock(id));dialog.dismiss();draw();message("Stok rosak direkod · kerugian "+money(e.optInt("cost")));
     }catch(Exception e){message("Gagal simpan. Semak bilangan dan simpanan telefon.");}});});dialog.show();}
  void damageHistory(){JSONArray list=entries("stock_damage");ScrollView scroll=new ScrollView(this);scroll.setVerticalScrollBarEnabled(false);LinearLayout panel=col();panel.setPadding(dp(14),dp(10),dp(14),dp(14));panel.setBackgroundColor(cream);scroll.addView(panel);boolean dayMode=activePage==1||cashDailyMode;String day=activePage==1?selectedStockDay:selectedCashDay;String month=selectedMonth;int totalLoss=0,countLoss=0;
    for(int i=list.length()-1;i>=0;i--){JSONObject e=list.optJSONObject(i);if(e==null||e.optBoolean("stockReset",false))continue;String d=entryDay(e);if(dayMode?!day.equals(d):!d.startsWith(month+"-"))continue;countLoss++;totalLoss+=e.optInt("cost");LinearLayout card=col();card.setPadding(dp(12),dp(10),dp(12),dp(10));card.setBackground(shape(surface,11));LinearLayout r=row();r.addView(text(e.optString("name","Stok"),14,ink,true),new LinearLayout.LayoutParams(0,-2,1));add(r,text(money(e.optInt("cost")),14,0xffd45a55,true),-2,-2);add(card,r,-1,-2);add(card,text(e.optString("time")+"  ·  "+e.optInt("qty")+" unit",11,muted,true),-1,-2);String note=e.optString("note");if(!note.isEmpty())add(card,text(note,12,muted,false),-1,-2);add(panel,card,-1,-2);gap(panel,7);}
    LinearLayout summary=col();summary.setPadding(dp(12),dp(10),dp(12),dp(10));summary.setBackground(shape(0xff2b3f55,11));add(summary,text("JUMLAH KERUGIAN",11,muted,true),-1,-2);add(summary,text(money(totalLoss),21,totalLoss>0?0xffd45a55:blue,true),-1,-2);panel.addView(summary,0);if(countLoss==0){TextView empty=text("Tiada stok rosak untuk tempoh ini.",13,muted,false);empty.setPadding(0,dp(12),0,0);add(panel,empty,-1,-2);}AlertDialog reportDialog=darkBuilder().setTitle("Stok rosak · "+(dayMode?day:monthLabel(month))).setView(scroll).setPositiveButton("Tutup",null).create();applyDarkReportDialog(reportDialog);}
  int[][] cashSeries(String month){int days=daysInMonth(month);int[][] values=new int[3][days];for(int d=0;d<days;d++)values[0][d]=getPreferences(0).getInt("sales_"+month+String.format(Locale.US,"-%02d",d+1),0);
    for(String key:new String[]{"cash_entries","stock_damage"}){JSONArray list=entries(key);for(int i=0;i<list.length();i++){JSONObject e=list.optJSONObject(i);if(e==null||(key.equals("cash_entries")&&legacySupplierRestock(e)))continue;String day=entryDay(e);if(!day.startsWith(month+"-"))continue;try{int d=Integer.parseInt(day.substring(8,10))-1;if(d<0||d>=days)continue;if(key.equals("stock_damage"))values[2][d]+=e.optInt("cost");else{int n=e.optInt("amount");values[n>=0?0:1][d]+=Math.abs(n);}}catch(Exception ignored){}}}return values;}
  void cashDaily(String month){int[][] v=cashSeries(month);ScrollView scroll=new ScrollView(this);LinearLayout panel=col();panel.setPadding(dp(16),dp(12),dp(16),dp(14));panel.setBackgroundColor(cream);scroll.addView(panel);String[] labels=new String[v[0].length];for(int d=0;d<labels.length;d++)labels[d]=(d==0||(d+1)%5==0)?""+(d+1):"";add(panel,text("PECAHAN HARIAN · "+monthLabel(month),13,gold,true),-1,-2);add(panel,text("Graf harian: Masuk / Jualan",11,muted,false),-1,-2);add(panel,new SalesChart(v[0],labels),-1,150);gap(panel,8);add(panel,text("Graf harian: Duit Keluar",11,muted,false),-1,-2);add(panel,new SalesChart(v[1],labels),-1,150);gap(panel,8);add(panel,text("Graf harian: Stok Rosak",11,muted,false),-1,-2);add(panel,new SalesChart(v[2],labels),-1,150);gap(panel,10);for(int d=0;d<v[0].length;d++)if(v[0][d]!=0||v[1][d]!=0||v[2][d]!=0){LinearLayout c=col();c.setPadding(dp(11),dp(8),dp(11),dp(8));c.setBackground(shape(surface,10));add(c,text((d+1)+" "+monthLabel(month),11,muted,true),-1,-2);add(c,text("Masuk "+money(v[0][d])+"   •   Keluar "+money(v[1][d])+"   •   Rosak "+money(v[2][d]),11,ink,false),-1,-2);add(panel,c,-1,-2);gap(panel,5);}AlertDialog reportDialog=darkBuilder().setTitle("Pecahan harian").setView(scroll).setPositiveButton("Tutup",null).create();applyDarkReportDialog(reportDialog);}

  class SalesPieChart extends View{
    Paint p=new Paint(1);int[] sold;
    SalesPieChart(int[] values){super(MainActivity.this);sold=Arrays.copyOf(values,values.length);}
    protected void onDraw(Canvas c){super.onDraw(c);float w=getWidth(),h=getHeight(),cx=w*.30f,cy=h*.50f,r=Math.min(w*.22f,h*.38f);int[] colors={0xff2f80ed,0xffff8a34,0xff25c77a,0xffffcf4a,0xff9b6dff,0xffff5f70,0xff5bc0de};float total=0;float[] values=new float[names.length];for(int i=0;i<names.length;i++){values[i]=sold[i]*prices[i];total+=values[i];}if(total<=0){p.setColor(0xff294563);c.drawCircle(cx,cy,r,p);p.setColor(muted);p.setTextSize(dp(12));p.setTextAlign(Paint.Align.CENTER);c.drawText("Tiada jualan",cx,cy+dp(4),p);return;}float start=-90;for(int i=0;i<values.length;i++){if(values[i]<=0)continue;float sweep=values[i]*360f/total;p.setColor(colors[i%colors.length]);c.drawArc(cx-r,cy-r,cx+r,cy+r,start,sweep,true,p);start+=sweep;}p.setColor(surface);c.drawCircle(cx,cy,r*.52f,p);p.setColor(ink);p.setTextAlign(Paint.Align.CENTER);p.setTextSize(dp(13));p.setTypeface(Typeface.DEFAULT_BOLD);c.drawText(money((int)total),cx,cy+dp(5),p);p.setTextAlign(Paint.Align.LEFT);p.setTypeface(Typeface.DEFAULT);float y=dp(30);for(int i=0;i<names.length;i++){if(values[i]<=0)continue;p.setColor(colors[i%colors.length]);c.drawCircle(w*.57f,y-dp(4),dp(5),p);p.setColor(ink);p.setTextSize(dp(10));String label=(i<3?"Sate · ":"")+names[i].replace("Sate ","");c.drawText(label,w*.60f,y,p);p.setColor(muted);p.setTextAlign(Paint.Align.RIGHT);c.drawText(String.format(Locale.US,"%.1f%%",values[i]*100f/total),w-dp(12),y,p);p.setTextAlign(Paint.Align.LEFT);y+=dp(24);}}
  }
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

          darkBuilder()
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
  void readBackup(Uri uri){try(InputStream in=getContentResolver().openInputStream(uri)){if(in==null)throw new IOException();java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();byte[] bytes=new byte[8192];int n;while((n=in.read(bytes))!=-1){if(out.size()+n>32*1024*1024)throw new IOException("Fail terlalu besar");out.write(bytes,0,n);}JSONObject root=new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8));if(!"my.warisan.pos".equals(root.getString("app"))||root.getInt("format")!=1)throw new IOException("Format backup tidak serasi");JSONObject data=root.getJSONObject("preferences");validateBackup(data);darkBuilder().setTitle("Pulihkan backup?").setMessage("Backup: "+root.optString("created")+"\nData semasa akan digantikan dengan backup ini. Salinan sebelum pulih disimpan dalam telefon. Gambar menu mungkin perlu dipilih semula jika berpindah telefon.").setPositiveButton("Pulihkan",(d,w)->restoreBackup(data)).setNegativeButton("Batal",null).show();}catch(Exception e){message("Backup tidak sah. Data semasa tidak diubah.");}}
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
  void recoverInternal(){String[] files={"before-update-19.json","before-restore.json"};darkBuilder().setTitle("Pilih salinan dalaman").setItems(new String[]{"Sebelum update 3.15","Sebelum pemulihan terakhir"},(d,index)->{java.io.File file=new java.io.File(getFilesDir(),files[index]);if(!file.exists()){message("Salinan ini belum tersedia");return;}readBackup(Uri.fromFile(file));}).setNegativeButton("Batal",null).show();}

}
