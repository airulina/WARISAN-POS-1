package my.warisan.pos;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Graphical A4 overall report, with gross sales only while branch costs remain local. */
final class OwnerOverallReportPdf {
  private static final int NAVY=Color.rgb(13,35,58);
  private static final int BLUE=Color.rgb(61,123,198);
  private static final int GOLD=Color.rgb(201,150,51);
  private static final int SOFT=Color.rgb(234,242,250);
  private static final int GRAY=Color.rgb(105,121,136);
  private OwnerOverallReportPdf(){}

  static final class BranchLine {
    final String name;
    final long sales,cash,qr,transactions;
    BranchLine(String name,OwnerCashflow.Day d){
      this.name=name;this.sales=d.sales;cash=d.cash;qr=d.qr;transactions=d.transactions;
    }
  }

  static void print(MainActivity host,String month,
      List<OwnerCashflow.BranchRow> branchRows,TreeMap<String,OwnerCashflow.Day> totalsByDay){
    // Take immutable report snapshots before Android's asynchronous Print Adapter writes PDF.
    final ArrayList<BranchLine> branches=new ArrayList<>();
    for(OwnerCashflow.BranchRow branch:branchRows)
      branches.add(new BranchLine(branch.name,branch.totals()));
    final TreeMap<String,OwnerCashflow.Day> days=new TreeMap<>();
    final OwnerCashflow.Day overall=new OwnerCashflow.Day();
    for(Map.Entry<String,OwnerCashflow.Day> e:totalsByDay.entrySet()){
      OwnerCashflow.Day src=e.getValue();
      days.put(e.getKey(),new OwnerCashflow.Day(src.sales,src.cash,src.qr,src.transactions,false));
      overall.add(src);
    }
    WarisanPrinting.print(host,"Warisan POS · Semua Branch · "+month,out->{
      PdfDocument pdf=new PdfDocument();
      try{
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        int number=1;
        PdfDocument.Page page=newPage(pdf,number);
        Canvas c=page.getCanvas();
        header(c,p,"LAPORAN KESELURUHAN · SEMUA BRANCH",month);
        metric(c,p,40,121,"JUMLAH JUALAN",money(overall.sales),BLUE);
        metric(c,p,306,121,"TRANSAKSI",String.valueOf(overall.transactions),NAVY);
        metric(c,p,40,196,"TUNAI",money(overall.cash),NAVY);
        metric(c,p,306,196,"QR / DUITNOW",money(overall.qr),GOLD);
        label(c,p,"GRAF JUALAN SEMUA BRANCH",40,301,14,NAVY,true);
        chart(c,p,days,month,40,319,515,195);
        label(c,p,"PECAHAN SETIAP BRANCH",40,552,13,NAVY,true);
        int y=573;
        tableHeader(c,p,y,"BRANCH","JUALAN","TRANSAKSI");y+=28;
        for(BranchLine branch:branches){
          if(y>759){
            pdf.finishPage(page);
            page=newPage(pdf,++number);c=page.getCanvas();
            header(c,p,"SAMBUNGAN · PECAHAN BRANCH",month);
            y=136;tableHeader(c,p,y,"BRANCH","JUALAN","TRANSAKSI");y+=28;
          }
          if(y%2==0){p.setColor(SOFT);c.drawRect(40,y-15,555,y+8,p);}
          label(c,p,trim(branch.name,27),44,y,10,NAVY,false);
          label(c,p,money(branch.sales),293,y,10,NAVY,false);
          label(c,p,String.valueOf(branch.transactions),479,y,10,NAVY,false);
          y+=24;
        }
        label(c,p,"Belum ada kos semua branch yang lengkap: ini laporan JUALAN KASAR, bukan UNTUNG.",40,790,9,GRAY,false);
        label(c,p,"Data Live dan rekod lama digabung tanpa kira resit Live dua kali.",40,814,9,GRAY,false);
        pdf.finishPage(page);
        page=newPage(pdf,++number);c=page.getCanvas();
        header(c,p,"REKOD JUALAN MENGIKUT TARIKH · SEMUA BRANCH",month);
        y=136;
        tableHeader(c,p,y,"TARIKH","JUALAN","TUNAI / QR");y+=28;
        for(Map.Entry<String,OwnerCashflow.Day> e:days.entrySet()){
          if(y>758){
            pdf.finishPage(page);
            page=newPage(pdf,++number);c=page.getCanvas();
            header(c,p,"SAMBUNGAN REKOD HARIAN",month);
            y=136;tableHeader(c,p,y,"TARIKH","JUALAN","TUNAI / QR");y+=28;
          }
          OwnerCashflow.Day d=e.getValue();
          label(c,p,e.getKey(),44,y,10,NAVY,false);
          label(c,p,money(d.sales),190,y,10,NAVY,false);
          label(c,p,shortMoney(d.cash)+" / "+shortMoney(d.qr),344,y,10,NAVY,false);
          y+=23;
        }
        if(y>767){
          pdf.finishPage(page);
          page=newPage(pdf,++number);c=page.getCanvas();
          header(c,p,"JUMLAH KESELURUHAN",month);
          y=155;
        }
        label(c,p,"JUMLAH JUALAN: "+money(overall.sales),44,y+22,14,NAVY,true);
        label(c,p,"Kaedah bayaran tidak dikelaskan: "+money(overall.unknown),44,788,9,GRAY,false);
        label(c,p,"Kos pembekal/operasi/rosak branch lain belum semuanya diselaraskan.",44,812,9,GRAY,false);
        pdf.finishPage(page);
        pdf.writeTo(out);
      }finally{pdf.close();}
    });
  }

  private static PdfDocument.Page newPage(PdfDocument pdf,int number){
    PdfDocument.Page page=pdf.startPage(new PdfDocument.PageInfo.Builder(595,842,number).create());
    page.getCanvas().drawColor(Color.WHITE);
    return page;
  }
  private static void header(Canvas c,Paint p,String title,String period){
    p.setColor(NAVY);c.drawRect(0,0,595,96,p);
    label(c,p,"WARISAN POS",40,38,20,Color.WHITE,true);
    label(c,p,title,40,62,12,Color.WHITE,true);
    label(c,p,period,40,82,11,Color.WHITE,false);
  }
  private static void metric(Canvas c,Paint p,int x,int y,String name,String amount,int tone){
    p.setColor(SOFT);c.drawRoundRect(new RectF(x,y,x+248,y+64),9,9,p);
    label(c,p,name,x+11,y+23,10,GRAY,true);
    label(c,p,amount,x+11,y+49,16,tone,true);
  }
  private static void tableHeader(Canvas c,Paint p,int y,String first,String second,String third){
    p.setColor(NAVY);c.drawRoundRect(new RectF(40,y-19,555,y+6),5,5,p);
    label(c,p,first,44,y,10,Color.WHITE,true);
    label(c,p,second,293,y,10,Color.WHITE,true);
    label(c,p,third,459,y,10,Color.WHITE,true);
  }
  private static void chart(Canvas c,Paint p,TreeMap<String,OwnerCashflow.Day> days,
      String month,int x,int y,int width,int height){
    p.setColor(SOFT);c.drawRoundRect(new RectF(x,y,x+width,y+height),9,9,p);
    long max=0;int count=0;
    for(Map.Entry<String,OwnerCashflow.Day> e:days.entrySet())
      if(e.getKey().startsWith(month+"-")){max=Math.max(max,e.getValue().sales);count++;}
    if(count==0||max<=0){
      label(c,p,"Tiada jualan pada bulan ini.",x+15,y+95,12,GRAY,false);return;
    }
    float left=x+48,right=x+width-15,top=y+16,bottom=y+height-35;
    for(int k=0;k<=3;k++){
      float h=bottom-(bottom-top)*k/3f;
      p.setColor(Color.rgb(205,218,232));p.setStrokeWidth(1f);
      c.drawLine(left,h,right,h,p);
      label(c,p,String.format(Locale.US,"%.0f",max*k/300.0),x+6,h+3,8,GRAY,false);
    }
    int i=0;float space=(right-left)/count;
    for(Map.Entry<String,OwnerCashflow.Day> entry:days.entrySet()){
      if(!entry.getKey().startsWith(month+"-"))continue;
      float xx=left+i*space+space*.15f;
      float bar=(bottom-top)*entry.getValue().sales/max;
      p.setColor(BLUE);
      c.drawRoundRect(new RectF(xx,bottom-bar,xx+Math.max(2,space*.7f),bottom),2,2,p);
      if(count<=16||i%5==0)
        label(c,p,entry.getKey().substring(8),xx,bottom+14,8,GRAY,false);
      i++;
    }
    label(c,p,"RM · Jualan harian gabungan",left,y+height-6,9,GRAY,false);
  }
  private static void label(Canvas c,Paint p,String value,float x,float y,float size,int color,boolean bold){
    p.setColor(color);
    p.setTextSize(size);
    p.setTypeface(Typeface.create("sans-serif",bold?Typeface.BOLD:Typeface.NORMAL));
    c.drawText(value,x,y,p);
  }
  private static String money(long cents){return String.format(Locale.US,"RM %,.2f",cents/100.0);}
  private static String shortMoney(long cents){return String.format(Locale.US,"%.2f",cents/100.0);}
  private static String trim(String text,int max){
    if(text==null)return "Branch";
    return text.length()<=max?text:text.substring(0,max-3)+"...";
  }
}
