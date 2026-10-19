package my.warisan.pos;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import java.io.OutputStream;
import java.util.Map;
import java.util.TreeMap;
import java.util.Locale;

/** A4 visual report for a single owner-selected branch. Never merges another branch's costs. */
final class OwnerReportPdf {
  // Printing happens in background. Thread-local language prevents reports
  // printed concurrently by separate jobs from changing each other's labels.
  private static final ThreadLocal<Boolean> PDF_ENGLISH=new ThreadLocal<>();
  private static String localized(String label){
    return WarisanLanguage.translate(label,Boolean.TRUE.equals(PDF_ENGLISH.get()));
  }
  private static final int NAVY=Color.rgb(13,35,58);
  private static final int BLUE=Color.rgb(54,116,191);
  private static final int GOLD=Color.rgb(207,152,41);
  private static final int GRAY=Color.rgb(94,109,127);
  private static final int PALE=Color.rgb(235,242,249);
  private OwnerReportPdf(){}

  static void print(MainActivity host,OwnerCashflow.BranchRow row,String period,boolean local){
    // Snapshot everything before entering Android's asynchronous print callbacks.
    final String name=row.name,month=host.selectedMonth,monthTitle=period;
    final TreeMap<String,OwnerCashflow.Day> days=row.combined();
    final OwnerCashflow.Day total=row.totals();
    final int sale,cost,operating,profit;
    final int[] sold;
    if(local){
      sale=host.saleTotalBetween(month+"-01",month+"-31");
      cost=host.productCostBetween(month+"-01",month+"-31");
      operating=host.operatingExpenseTotal(month,false);
      profit=host.businessProfit(month,false);
      sold=host.soldNetBetween(month+"-01",month+"-31");
    }else{sale=0;cost=0;operating=0;profit=0;sold=new int[0];}
    final String[] names=host.names.clone();
    WarisanPrinting.print(host,"WARISAN POS · "+name+" · "+month, out->{
      PDF_ENGLISH.set(host.english());
      PdfDocument pdf=new PdfDocument();
      try{
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        PdfDocument.Page page=pdf.startPage(new PdfDocument.PageInfo.Builder(595,842,1).create());
        Canvas c=page.getCanvas();c.drawColor(Color.WHITE);
        header(c,p,name,monthTitle,"LAPORAN KEWANGAN & GRAF JUALAN");
        card(c,p,40,128,248,79,"JUMLAH JUALAN",money(total.sales),BLUE);
        card(c,p,306,128,248,79,"TRANSAKSI",String.valueOf(total.transactions),NAVY);
        card(c,p,40,222,248,79,"TUNAI",money(total.cash),NAVY);
        card(c,p,306,222,248,79,"QR / DUITNOW",money(total.qr),GOLD);
        title(c,p,"GRAF JUALAN MENGIKUT HARI",40,333,14,NAVY);
        drawSalesChart(c,p,days,month,40,352,515,214);
        if(local){
          title(c,p,"KOS & UNTUNG POS TEMPATAN",40,607,14,NAVY);
          card(c,p,40,623,248,66,"KOS PRODUK",money(cost),NAVY);
          card(c,p,306,623,248,66,"BELANJA OPERASI",money(operating),NAVY);
          card(c,p,40,699,248,66,"JUALAN TEMPATAN",money(sale),BLUE);
          card(c,p,306,699,248,66,"UNTUNG TEMPATAN",money(profit),GOLD);
          note(c,p,"Kos/untung: rekod pada telefon branch aktif sahaja; bukan untung keseluruhan Cloud.",40,793);
        }else{
          note(c,p,"Kos dan untung branch lain belum diselaraskan daripada telefon branch tersebut.",40,624);
          note(c,p,"Tidak memaparkan RM0 kerana data kos branch ini belum disahkan.",40,643);
        }
        note(c,p,"Rekod Live dan rekod lama digabung mengikut tarikh tanpa menggandakan resit Live.",40,817);
        pdf.finishPage(page);
        int pageNo=2;
        page=pdf.startPage(new PdfDocument.PageInfo.Builder(595,842,pageNo).create());
        c=page.getCanvas();c.drawColor(Color.WHITE);
        header(c,p,name,monthTitle,"PECAHAN JUALAN & REKOD HARIAN");
        title(c,p,"ITEM TERJUAL · REKOD TEMPATAN",40,124,12,NAVY);
        int y=147,productCount=0;
        if(local){
          for(int i=0;i<sold.length&&i<names.length;i++){
            if(sold[i]<=0)continue;
            label(c,p,names[i],40,y,11,NAVY);
            label(c,p,String.valueOf(sold[i])+(i<3?" cucuk":" unit"),390,y,11,NAVY);
            y+=17;productCount++;
          }
        }
        if(productCount==0){label(c,p,"Tiada pecahan produk tempatan untuk branch ini.",40,y,10,GRAY);y+=20;}
        y=Math.max(258,y+20);
        title(c,p,"JUALAN MENGIKUT TARIKH",40,y,13,NAVY);y+=25;
        tableHeading(c,p,y);y+=22;
        for(Map.Entry<String,OwnerCashflow.Day> entry:days.entrySet()){
          if(y>752){
            pdf.finishPage(page);
            page=pdf.startPage(new PdfDocument.PageInfo.Builder(595,842,++pageNo).create());
            c=page.getCanvas();c.drawColor(Color.WHITE);
            header(c,p,name,monthTitle,"SAMBUNGAN REKOD HARIAN");
            y=135;tableHeading(c,p,y);y+=24;
          }
          OwnerCashflow.Day d=entry.getValue();
          if((y/22)%2==0){p.setColor(PALE);c.drawRect(39,y-14,555,y+6,p);}
          label(c,p,entry.getKey(),43,y,10,NAVY);
          label(c,p,shortMoney(d.sales),183,y,10,NAVY);
          label(c,p,shortMoney(d.cash),301,y,10,NAVY);
          label(c,p,shortMoney(d.qr),412,y,10,NAVY);
          y+=22;
        }
        if(y>762){
          pdf.finishPage(page);
          page=pdf.startPage(new PdfDocument.PageInfo.Builder(595,842,++pageNo).create());
          c=page.getCanvas();c.drawColor(Color.WHITE);header(c,p,name,monthTitle,"JUMLAH KESELURUHAN");
          y=140;
        }
        title(c,p,"JUMLAH: "+money(total.sales)+"   |   "+total.transactions+" transaksi",40,y+21,12,NAVY);
        note(c,p,"Baki kaedah bayaran belum dikenal pasti: "+money(total.unknown),40,796);
        note(c,p,"Rekod lama pada telefon lain mungkin belum diselaraskan ke Cloud.",40,817);
        pdf.finishPage(page);
        pdf.writeTo(out);
      }finally{pdf.close();PDF_ENGLISH.remove();}
    });
  }
  private static void header(Canvas c,Paint p,String branch,String month,String subtitle){
    p.setColor(NAVY);c.drawRect(0,0,595,94,p);
    title(c,p,"WARISAN POS",40,39,20,Color.WHITE);
    label(c,p,subtitle,40,61,10,Color.WHITE);
    label(c,p,branch+"   •   "+month,40,83,10,Color.WHITE);
  }
  private static void card(Canvas c,Paint p,int x,int y,int w,int h,String label,String value,int valueColor){
    p.setColor(PALE);c.drawRoundRect(new RectF(x,y,x+w,y+h),12,12,p);
    label(c,p,label,x+13,y+23,10,GRAY);
    title(c,p,value,x+13,y+51,17,valueColor);
  }
  private static void title(Canvas c,Paint p,String text,float x,float y,float size,int color){
    p.setColor(color);p.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));
    p.setTextSize(size);c.drawText(localized(text),x,y,p);
  }
  private static void label(Canvas c,Paint p,String text,float x,float y,float size,int color){
    p.setColor(color);p.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));
    p.setTextSize(size);c.drawText(localized(text),x,y,p);
  }
  private static void note(Canvas c,Paint p,String text,int x,int y){
    label(c,p,text,x,y,8.5f,GRAY);
  }
  private static void drawSalesChart(Canvas c,Paint p,TreeMap<String,OwnerCashflow.Day> rows,
      String month,int left,int top,int width,int height){
    long max=0;int length=0;
    for(Map.Entry<String,OwnerCashflow.Day> e:rows.entrySet()){
      if(e.getKey().startsWith(month+"-")){max=Math.max(max,e.getValue().sales);length++;}
    }
    p.setColor(PALE);c.drawRoundRect(new RectF(left,top,left+width,top+height),10,10,p);
    if(max<=0||length==0){label(c,p,"Tiada jualan direkodkan bagi bulan ini.",left+20,top+105,11,GRAY);return;}
    float graphLeft=left+49,graphTop=top+18,graphBottom=top+height-36;
    for(int i=0;i<=3;i++){
      float yy=graphBottom-i*(graphBottom-graphTop)/3f;
      p.setColor(Color.rgb(206,216,227));p.setStrokeWidth(1);c.drawLine(graphLeft,yy,left+width-16,yy,p);
      label(c,p,String.format(Locale.US,"%.0f",max*i/300f),left+8,yy+3,8,GRAY);
    }
    int d=0;float step=(width-75f)/Math.max(1,length);
    for(Map.Entry<String,OwnerCashflow.Day> e:rows.entrySet()){
      if(!e.getKey().startsWith(month+"-"))continue;
      float x=graphLeft+d*step+step*.17f;
      float bh=(graphBottom-graphTop)*e.getValue().sales/max;
      p.setColor(BLUE);
      c.drawRoundRect(new RectF(x,graphBottom-bh,x+Math.max(2,step*.64f),graphBottom),2,2,p);
      if(length<=16||d%5==0)
        label(c,p,e.getKey().substring(8),x,graphBottom+15,8,GRAY);
      d++;
    }
    label(c,p,"RM · Jualan harian ("+month+")",graphLeft,top+height-8,9,GRAY);
  }
  private static void tableHeading(Canvas c,Paint p,int y){
    p.setColor(NAVY);c.drawRoundRect(new RectF(39,y-16,555,y+8),5,5,p);
    label(c,p,"TARIKH",43,y,10,Color.WHITE);
    label(c,p,"JUMLAH",183,y,10,Color.WHITE);
    label(c,p,"TUNAI",301,y,10,Color.WHITE);
    label(c,p,"QR",412,y,10,Color.WHITE);
  }
  private static String money(long cents){return String.format(Locale.US,"RM %,.2f",cents/100d);}
  private static String shortMoney(long cents){return String.format(Locale.US,"%.2f",cents/100d);}
}
