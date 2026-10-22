package my.warisan.pos;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/** Seven-day local POS skewer quantities. Never represents cloud-wide branch totals. */
final class ProductSalesChart extends View {
  private static final int DAYS=7;
  private final MainActivity host;
  private final int[][] quantity=new int[2][DAYS];
  private final String[] dates=new String[DAYS];
  private final String[] labels=new String[DAYS];
  private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);

  ProductSalesChart(MainActivity activity,String endingDay){
    super(activity);
    host=activity;
    SimpleDateFormat iso=new SimpleDateFormat("yyyy-MM-dd",Locale.US);
    SimpleDateFormat shortDate=new SimpleDateFormat("d/M",Locale.US);
    iso.setLenient(false);
    Calendar calendar=Calendar.getInstance();
    try{calendar.setTime(iso.parse(endingDay));}
    catch(Exception ignored){calendar.setTimeInMillis(System.currentTimeMillis());}
    calendar.add(Calendar.DATE,-DAYS+1);
    HashMap<String,Integer> index=new HashMap<>();
    for(int i=0;i<DAYS;i++){
      dates[i]=iso.format(calendar.getTime());
      labels[i]=shortDate.format(calendar.getTime());
      index.put(dates[i],i);
      calendar.add(Calendar.DATE,1);
    }
    // One pass over local sales; cancelled/refunded receipts do not count.
    JSONArray records=host.entries("stock_sales");
    for(int i=0;i<records.length();i++){
      JSONObject receipt=records.optJSONObject(i);
      if(receipt==null||receipt.optBoolean("refunded",false))continue;
      Integer day=index.get(host.entryDay(receipt));
      if(day==null)continue;
      JSONArray items=receipt.optJSONArray("qty");
      if(items==null)continue;
      for(int product=0;product<2&&product<items.length();product++)
        quantity[product][day]+=Math.max(0,items.optInt(product));
    }
  }

  private float px(float units){return units*getResources().getDisplayMetrics().density;}

  private int niceCeiling(int largest){
    if(largest<=4)return 4;
    int step=1;
    while((long)step*4<largest){
      if(step>100000000)return largest;
      step*=step==1?2:step==2?2:step==4?5:2;
    }
    return step*4;
  }

  @Override protected void onDraw(Canvas canvas){
    super.onDraw(canvas);
    float width=getWidth(),height=getHeight();
    if(width<=0||height<=0)return;
    int chicken=host.themeLight?0xff087F65:0xff4ADEB9;
    int beef=host.themeLight?0xffA75D0B:0xffFFC46C;
    int grid=host.themeLight?0xffD7E2EE:host.surfaceAlt;
    int foreground=host.muted;

    // Same actual seven-day skewer quantities, presented as short horizontal
    // bars. No second chart, landscape screen, or extra report window needed.
    paint.setStyle(Paint.Style.FILL);
    paint.setTextAlign(Paint.Align.LEFT);
    paint.setTypeface(Typeface.create(Typeface.DEFAULT,Typeface.BOLD));
    paint.setTextSize(px(10));
    float legend=px(15);
    paint.setColor(chicken);canvas.drawCircle(px(8),legend-px(3),px(4),paint);
    paint.setColor(foreground);canvas.drawText(host.english()?"CHICKEN":"AYAM",px(17),legend,paint);
    paint.setColor(beef);canvas.drawCircle(px(106),legend-px(3),px(4),paint);
    paint.setColor(foreground);canvas.drawText(host.english()?"BEEF":"DAGING",px(115),legend,paint);
    paint.setTextAlign(Paint.Align.RIGHT);
    canvas.drawText(host.english()?"SKEWERS":"CUCUK",width-px(6),legend,paint);

    float left=px(45),right=width-px(16),top=px(28),bottom=height-px(15);
    if(right<=left||bottom<=top)return;
    int max=0;
    for(int product=0;product<2;product++)
      for(int day=0;day<DAYS;day++)max=Math.max(max,quantity[product][day]);
    int upper=niceCeiling(max);
    float rowHeight=(bottom-top)/DAYS;
    float chartWidth=right-left;

    paint.setStrokeWidth(px(.75f));
    for(int step=1;step<=4;step++){
      float x=left+chartWidth*step/4;
      paint.setColor(grid);
      canvas.drawLine(x,top,x,bottom,paint);
    }
    paint.setTypeface(Typeface.DEFAULT);
    paint.setTextSize(px(9));
    for(int day=0;day<DAYS;day++){
      float y=top+rowHeight*day;
      if(day==DAYS-1){
        paint.setColor(host.themeLight?0xffEDF5FA:0xff294257);
        canvas.drawRoundRect(new RectF(px(1),y,width-px(1),y+rowHeight),
          px(6),px(6),paint);
      }
      paint.setTextAlign(Paint.Align.LEFT);
      paint.setTypeface(day==DAYS-1?Typeface.DEFAULT_BOLD:Typeface.DEFAULT);
      paint.setColor(day==DAYS-1?host.ink:foreground);
      canvas.drawText(labels[day],px(3),y+rowHeight*.58f,paint);
      float barHeight=Math.max(px(3),Math.min(px(6),rowHeight*.29f));
      for(int product=0;product<2;product++){
        int count=quantity[product][day];
        if(count<=0)continue;
        float length=Math.max(px(2),chartWidth*count/(float)upper);
        float barY=y+rowHeight*.2f+product*(barHeight+px(2));
        paint.setColor(product==0?chicken:beef);
        canvas.drawRoundRect(new RectF(left,barY,left+length,barY+barHeight),
          px(3),px(3),paint);
      }
    }
    if(max==0){
      paint.setTextAlign(Paint.Align.CENTER);
      paint.setTextSize(px(10));
      paint.setColor(foreground);
      canvas.drawText(host.english()?"No local skewer sales yet.":
        "Belum ada jualan sate tempatan.",(left+right)/2,(top+bottom)/2,paint);
    }
  }
}
