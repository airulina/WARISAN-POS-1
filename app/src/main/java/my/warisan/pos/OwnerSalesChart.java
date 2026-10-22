package my.warisan.pos;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;
import java.util.Locale;

/** Compact branch-only daily sales chart displayed above the Owner PDF action. */
final class OwnerSalesChart extends View {
  private final String month;
  private final ArrayList<Long> values=new ArrayList<>();
  private final ArrayList<String> labels=new ArrayList<>();
  private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
  OwnerSalesChart(Context context,TreeMap<String,OwnerCashflow.Day> days,String month){
    super(context);this.month=month;
    for(Map.Entry<String,OwnerCashflow.Day> entry:days.entrySet()){
      if(!entry.getKey().startsWith(month+"-"))continue;
      labels.add(entry.getKey().substring(8));
      values.add(entry.getValue().sales);
    }
    setMinimumHeight((int)(context.getResources().getDisplayMetrics().density*205));
  }
  private String display(String text){
    Context current=getContext();
    return WarisanLanguage.translate(text,
      current instanceof MainActivity && ((MainActivity)current).english());
  }
  @Override protected void onDraw(Canvas c){
    super.onDraw(c);
    MainActivity host=getContext() instanceof MainActivity?
      (MainActivity)getContext():null;
    int background=host==null?Color.rgb(18,43,68):host.surface;
    int title=host==null?Color.rgb(230,237,245):host.ink;
    int muted=host==null?Color.rgb(166,184,202):host.muted;
    int bar=host==null?Color.rgb(73,135,215):host.blue;
    int grid=host==null?Color.rgb(53,78,102):
      (host.themeLight?0xffD1DFEF:host.surfaceAlt);
    float density=getResources().getDisplayMetrics().density;
    float w=getWidth(),h=getHeight();
    p.setColor(background);
    c.drawRoundRect(new RectF(0,0,w,h),14*density,14*density,p);
    p.setTextSize(12*density);p.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
    p.setColor(title);c.drawText(display("GRAF JUALAN HARIAN · "+month),14*density,24*density,p);
    if(values.isEmpty()){
      p.setColor(muted);p.setTextSize(12*density);
      c.drawText(display("Tiada jualan untuk bulan dipilih."),14*density,h/2,p);return;
    }
    long max=0;for(long v:values)max=Math.max(max,v);
    if(max<=0)max=1;
    float left=42*density,right=w-12*density,top=48*density,bottom=h-32*density;
    p.setTypeface(android.graphics.Typeface.DEFAULT);p.setTextSize(9*density);
    for(int i=0;i<=3;i++){
      float y=bottom-(bottom-top)*i/3f;
      p.setColor(grid);p.setStrokeWidth(density);
      c.drawLine(left,y,right,y,p);
      p.setColor(muted);
      c.drawText(String.format(Locale.US,"%.0f",max*i/300.0),4*density,y+3*density,p);
    }
    float step=(right-left)/values.size();
    for(int i=0;i<values.size();i++){
      float tall=(bottom-top)*(values.get(i)/(float)max);
      float x=left+step*i+step*.16f;
      p.setColor(bar);
      c.drawRoundRect(new RectF(x,bottom-tall,x+Math.max(2*density,step*.68f),bottom),2*density,2*density,p);
      if(values.size()<=16||i%5==0){
        p.setColor(muted);p.setTextSize(8*density);
        c.drawText(labels.get(i),x,bottom+14*density,p);
      }
    }
  }
}
