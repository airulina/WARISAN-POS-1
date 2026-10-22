package my.warisan.pos;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Locale;
import java.util.TreeMap;

/** Seven-day, real-data sales trend on the Owner's branch home card. */
final class OwnerBranchTrendChart extends View {
  private final ArrayList<Long> sales=new ArrayList<>();
  private final ArrayList<String> days=new ArrayList<>();
  private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
  OwnerBranchTrendChart(MainActivity host,TreeMap<String,OwnerCashflow.Day> history,String selectedDay){
    super(host);
    try{
      SimpleDateFormat iso=new SimpleDateFormat("yyyy-MM-dd",Locale.US);
      iso.setLenient(false);
      SimpleDateFormat label=new SimpleDateFormat("EEE",new Locale("ms","MY"));
      Calendar cal=Calendar.getInstance();cal.setTime(iso.parse(selectedDay));
      String selectedMonth=selectedDay.substring(0,7);
      ArrayList<Long> backwards=new ArrayList<>();
      ArrayList<String> backwardsLabels=new ArrayList<>();
      for(int index=0;index<7;index++){
        String current=iso.format(cal.getTime());
        if(!current.startsWith(selectedMonth+"-"))break;
        OwnerCashflow.Day data=history.get(current);
        backwards.add(data==null?0:Math.max(0,data.sales));
        backwardsLabels.add(label.format(cal.getTime()));
        cal.add(Calendar.DATE,-1);
      }
      for(int i=backwards.size()-1;i>=0;i--){
        sales.add(backwards.get(i));days.add(backwardsLabels.get(i));
      }
    }catch(Exception ignored){}
  }
  @Override protected void onDraw(Canvas canvas){
    super.onDraw(canvas);
    MainActivity host=(MainActivity)getContext();
    float density=getResources().getDisplayMetrics().density;
    float width=getWidth(),height=getHeight();
    float left=host.dp(10),right=width-host.dp(10);
    float top=host.dp(15),bottom=height-host.dp(25);
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(density);
    paint.setColor(host.themeLight?0xffCFDBE7:0xff2B4560);
    canvas.drawLine(left,bottom,right,bottom,paint);
    paint.setStyle(Paint.Style.FILL);
    if(sales.isEmpty())return;
    long maximum=0;
    for(Long value:sales)maximum=Math.max(maximum,value);
    if(maximum==0){
      paint.setColor(host.muted);paint.setTextSize(12*density);
      canvas.drawText(host.english()?"No sales in this period":"Tiada jualan dalam tempoh ini",
        left,top+host.dp(45),paint);
      return;
    }
    float step=(right-left)/sales.size();
    float[] centerX=new float[sales.size()],centerY=new float[sales.size()];
    for(int index=0;index<sales.size();index++){
      float x=left+step*(index+.5f);
      float barHeight=(bottom-top)*sales.get(index)/(float)maximum*.81f;
      float barWidth=Math.min(host.dp(24),step*.63f);
      float y=bottom-barHeight;
      centerX[index]=x;centerY[index]=y;
      paint.setColor(host.gold);
      canvas.drawRoundRect(new RectF(x-barWidth/2,y,x+barWidth/2,bottom),
        host.dp(3),host.dp(3),paint);
      paint.setColor(host.muted);paint.setTextSize(10*density);
      paint.setTextAlign(Paint.Align.CENTER);
      canvas.drawText(days.get(index),x,bottom+host.dp(16),paint);
    }
    paint.setColor(host.themeLight?0xff087E4F:0xff46E48A);
    paint.setStrokeWidth(host.dp(2));paint.setStyle(Paint.Style.STROKE);
    for(int index=1;index<centerX.length;index++)
      canvas.drawLine(centerX[index-1],centerY[index-1],centerX[index],centerY[index],paint);
    paint.setStyle(Paint.Style.FILL);
    for(int index=0;index<centerX.length;index++)
      canvas.drawCircle(centerX[index],centerY[index],host.dp(3),paint);
    paint.setTextAlign(Paint.Align.LEFT);
  }
}
