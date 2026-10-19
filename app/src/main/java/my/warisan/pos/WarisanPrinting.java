package my.warisan.pos;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;
import android.print.PrintManager;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Locale;

/** A4 printing through Android's printer discovery and driver system. */
final class WarisanPrinting {
    interface PdfWriter { void write(OutputStream out) throws IOException; }
    private WarisanPrinting() {}

    static void textA4(Activity activity, String text, String title) {
        print(activity, title, out -> {
            PdfDocument pdf = new PdfDocument();
            try {
                String[] raw = text.replace("\r", "").split("\n", -1);
                ArrayList<String> lines = new ArrayList<>();
                // Preserve receipt alignment with a monospaced font, split long lines.
                for (String s : raw) {
                    if (s.isEmpty()) { lines.add(""); continue; }
                    for (int p=0;p<s.length();p+=72)
                        lines.add(s.substring(p, Math.min(s.length(),p+72)));
                }
                int pageNo=0;
                for (int from=0;from<lines.size();from+=62) {
                    PdfDocument.Page page=pdf.startPage(new PdfDocument.PageInfo.Builder(595,842,++pageNo).create());
                    Canvas canvas=page.getCanvas();canvas.drawColor(Color.WHITE);
                    Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
                    paint.setColor(Color.BLACK);paint.setTextSize(10.2f);paint.setTypeface(Typeface.MONOSPACE);
                    for (int index=from; index<Math.min(lines.size(),from+62);index++)
                        canvas.drawText(lines.get(index),38,49+(index-from)*12.0f,paint);
                    pdf.finishPage(page);
                }
                if (pageNo==0) {
                    PdfDocument.Page page=pdf.startPage(new PdfDocument.PageInfo.Builder(595,842,1).create());
                    page.getCanvas().drawColor(Color.WHITE);pdf.finishPage(page);
                }
                pdf.writeTo(out);
            } finally { pdf.close(); }
        });
    }

    static void monthA4(MainActivity activity, String month) {
        print(activity, "Warisan POS Bulanan "+month, out -> {
            PdfDocument pdf = new PdfDocument();
            try {
                PdfDocument.Page page=pdf.startPage(new PdfDocument.PageInfo.Builder(595,842,1).create());
                Canvas c=page.getCanvas();c.drawColor(Color.WHITE);
                Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
                p.setColor(Color.rgb(12,40,66));p.setTypeface(Typeface.DEFAULT_BOLD);p.setTextSize(22);
                c.drawText("WARISAN POS",40,55,p);
                p.setTextSize(14);c.drawText("LAPORAN JUALAN BULAN "+month,40,82,p);
                p.setColor(Color.DKGRAY);p.setTextSize(11);
                c.drawText("TARIKH",42,127,p);c.drawText("ORDER",310,127,p);c.drawText("JUALAN",426,127,p);
                int total=0, count=0;int y=152;
                android.content.SharedPreferences pref=activity.posPrefs();
                int days=activity.daysInMonth(month);
                for (int day=1;day<=days;day++) {
                    String date=String.format(Locale.US,"%s-%02d",month,day);
                    int sale=pref.getInt("sales_"+date,0),orders=pref.getInt("orders_"+date,0);
                    if(sale==0&&orders==0)continue;
                    total+=sale;count+=orders;
                    p.setTypeface(Typeface.DEFAULT);p.setColor(Color.BLACK);
                    c.drawText(date,42,y,p);
                    c.drawText(String.valueOf(orders),310,y,p);
                    c.drawText(activity.money(sale),426,y,p);
                    y+=21;
                }
                y=Math.max(y+18,170);
                p.setColor(Color.rgb(12,40,66));p.setTypeface(Typeface.DEFAULT_BOLD);p.setTextSize(13);
                c.drawText("JUMLAH",42,y,p);c.drawText(String.valueOf(count),310,y,p);c.drawText(activity.money(total),426,y,p);
                p.setColor(Color.GRAY);p.setTypeface(Typeface.DEFAULT);p.setTextSize(9);
                c.drawText("Laporan A4 mengikut branch aktif. Angka berdasarkan transaksi direkod dalam POS.",40,810,p);
                pdf.finishPage(page);pdf.writeTo(out);
            } finally {pdf.close();}
        });
    }


    /**
     * Graph-first Owner report. Combined Live/legacy sales are not treated as
     * local profit. Android Print Service also offers "Save as PDF".
     */
    static void branchGraphA4(MainActivity activity, String branchName, String month,
            java.util.TreeMap<String,OwnerCashflow.Day> days, String fullReport,
            boolean localAvailable, long localSales, long localCost,
            long localOperating, long localProfit) {
        final java.util.TreeMap<String,OwnerCashflow.Day> snapshot=new java.util.TreeMap<>(days);
        print(activity,"Warisan Laporan "+branchName+" "+month,out -> {
            PdfDocument pdf=new PdfDocument();
            try {
                PdfDocument.Page page=pdf.startPage(new PdfDocument.PageInfo.Builder(595,842,1).create());
                Canvas c=page.getCanvas();c.drawColor(Color.WHITE);
                Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
                int navy=Color.rgb(19,43,68),blue=Color.rgb(55,117,186),
                    gold=Color.rgb(202,146,35),gray=Color.rgb(95,105,115);
                p.setTypeface(Typeface.DEFAULT_BOLD);p.setColor(navy);p.setTextSize(22);
                c.drawText("WARISAN POS",40,56,p);
                p.setTextSize(14);c.drawText("LAPORAN BRANCH · "+branchName,40,82,p);
                p.setColor(gray);p.setTextSize(10);
                c.drawText("Bulan: "+month+"   |   Rekod Cloud + rekod lama tanpa pendua",40,102,p);
                long sales=0,cash=0,qr=0,unknown=0,transactions=0,max=1;
                for(OwnerCashflow.Day d:snapshot.values()){
                    sales+=d.sales;cash+=d.cash;qr+=d.qr;unknown+=d.unknown;
                    transactions+=d.transactions;max=Math.max(max,d.sales);
                }
                p.setColor(navy);p.setTextSize(12);p.setTypeface(Typeface.DEFAULT_BOLD);
                c.drawText("JUMLAH JUALAN",40,147,p);
                c.drawText("TUNAI",228,147,p);c.drawText("QR / DUITNOW",406,147,p);
                p.setColor(blue);p.setTextSize(17);
                c.drawText(OwnerCashflow.rm(sales),40,171,p);
                p.setColor(navy);c.drawText(OwnerCashflow.rm(cash),228,171,p);
                p.setColor(gold);c.drawText(OwnerCashflow.rm(qr),406,171,p);
                p.setColor(gray);p.setTextSize(10);p.setTypeface(Typeface.DEFAULT);
                c.drawText("Transaksi: "+transactions+"   |   Belum dikelaskan: "+OwnerCashflow.rm(unknown),40,194,p);
                p.setColor(navy);p.setTypeface(Typeface.DEFAULT_BOLD);p.setTextSize(14);
                c.drawText("GRAF JUALAN HARIAN",40,232,p);
                final float graphLeft=44,graphRight=553,graphTop=265,graphBottom=444;
                p.setColor(Color.rgb(228,232,239));p.setStrokeWidth(1);
                for(int i=0;i<4;i++){
                    float y=graphTop+(graphBottom-graphTop)*i/3f;
                    c.drawLine(graphLeft,y,graphRight,y,p);
                }
                int count=snapshot.size(),index=0;
                float step=(graphRight-graphLeft)/Math.max(1,count);
                for(java.util.Map.Entry<String,OwnerCashflow.Day> e:snapshot.entrySet()){
                    float center=graphLeft+step*(index+.5f);
                    float height=(float)(e.getValue().sales*(graphBottom-graphTop)/max);
                    p.setColor(index==count-1?gold:blue);
                    c.drawRoundRect(center-step*.32f,graphBottom-height,
                        center+step*.32f,graphBottom,2,2,p);
                    if(index==0||index==count-1||index%Math.max(1,count/7)==0){
                        p.setColor(gray);p.setTypeface(Typeface.DEFAULT);p.setTextSize(9);
                        p.setTextAlign(Paint.Align.CENTER);
                        String date=e.getKey();
                        c.drawText(date.length()>=10?date.substring(8):date,center,460,p);
                        p.setTextAlign(Paint.Align.LEFT);
                    }
                    index++;
                }
                p.setColor(navy);p.setTextSize(13);p.setTypeface(Typeface.DEFAULT_BOLD);
                c.drawText("PECAHAN KAEDAH BAYARAN",40,499,p);
                long denominator=Math.max(1,cash+qr+unknown);
                float cursor=40,bandWidth=513;
                long[] segments={cash,qr,unknown};
                int[] colors={blue,gold,gray};
                for(int i=0;i<segments.length;i++){
                    float w=(float)(bandWidth*segments[i]/denominator);
                    p.setColor(colors[i]);c.drawRect(cursor,518,cursor+w,539,p);cursor+=w;
                }
                p.setTypeface(Typeface.DEFAULT);p.setTextSize(11);
                p.setColor(blue);c.drawText("■ Tunai",40,562,p);
                p.setColor(gold);c.drawText("■ QR / TnG / DuitNow",166,562,p);
                p.setColor(gray);c.drawText("■ Tidak dikelaskan",368,562,p);
                if(localAvailable){
                    p.setColor(navy);p.setTextSize(13);p.setTypeface(Typeface.DEFAULT_BOLD);
                    c.drawText("KOS & UNTUNG POS TEMPATAN SAHAJA",40,613,p);
                    p.setTypeface(Typeface.DEFAULT);p.setTextSize(11);
                    c.drawText("Jualan lokal: "+OwnerCashflow.rm(localSales),40,640,p);
                    c.drawText("Kos produk: "+OwnerCashflow.rm(localCost),310,640,p);
                    c.drawText("Kos operasi: "+OwnerCashflow.rm(localOperating),40,662,p);
                    c.drawText("Untung lokal: "+OwnerCashflow.rm(localProfit),310,662,p);
                    p.setColor(gray);p.setTextSize(9);
                    c.drawText("Untung lokal bukan untung semua branch/Cloud.",40,684,p);
                }else{
                    p.setColor(gray);p.setTextSize(11);
                    c.drawText("Kos branch lain belum diselaraskan; untung tidak dianggarkan.",40,626,p);
                }
                p.setColor(gray);p.setTextSize(9);
                c.drawText("Rekod tempatan dari telefon lain mungkin belum sync. Rujuk pecahan di halaman berikut.",40,794,p);
                c.drawText("1",545,812,p);
                pdf.finishPage(page);

                java.util.ArrayList<String> lines=new java.util.ArrayList<>();
                for(String s:fullReport.replace("\\r","").split("\\n",-1)){
                    if(s.isEmpty()){lines.add("");continue;}
                    for(int i=0;i<s.length();i+=77)
                        lines.add(s.substring(i,Math.min(s.length(),i+77)));
                }
                for(int from=0;from<lines.size();from+=55){
                    int pageNo=2+from/55;
                    PdfDocument.Page detail=pdf.startPage(
                        new PdfDocument.PageInfo.Builder(595,842,pageNo).create());
                    Canvas d=detail.getCanvas();d.drawColor(Color.WHITE);
                    Paint ink=new Paint(Paint.ANTI_ALIAS_FLAG);
                    ink.setColor(navy);ink.setTypeface(Typeface.DEFAULT_BOLD);ink.setTextSize(14);
                    d.drawText("PECAHAN LAPORAN · "+branchName,38,43,ink);
                    ink.setColor(Color.BLACK);ink.setTypeface(Typeface.MONOSPACE);ink.setTextSize(9.3f);
                    for(int j=from;j<Math.min(lines.size(),from+55);j++)
                        d.drawText(lines.get(j),38,72+(j-from)*13,ink);
                    ink.setColor(gray);ink.setTextSize(9);
                    d.drawText("WARISAN POS · "+month,38,805,ink);
                    d.drawText(""+pageNo,545,805,ink);
                    pdf.finishPage(detail);
                }
                pdf.writeTo(out);
            }finally{pdf.close();}
        });
    }

    static void yearA4(MainActivity activity, int year) {
        print(activity, "Warisan POS Laporan "+year, out -> activity.writePdf(out,year));
    }

    static void print(Activity activity, String title, PdfWriter writer) {
        PrintManager manager=(PrintManager)activity.getSystemService(Context.PRINT_SERVICE);
        if(manager==null) {
            android.widget.Toast.makeText(activity,"Sistem cetakan Android tiada",android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        PrintDocumentAdapter adapter=new PrintDocumentAdapter() {
            @Override public void onLayout(PrintAttributes oldAttributes, PrintAttributes newAttributes,
                    CancellationSignal cancellation, LayoutResultCallback callback, android.os.Bundle extras) {
                if(cancellation.isCanceled()){callback.onLayoutCancelled();return;}
                callback.onLayoutFinished(new PrintDocumentInfo.Builder(title+".pdf")
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN).build(),true);
            }
            @Override public void onWrite(PageRange[] pages, ParcelFileDescriptor destination,
                    CancellationSignal cancellation, WriteResultCallback callback) {
                if(cancellation.isCanceled()){callback.onWriteCancelled();return;}
                try (OutputStream stream=new FileOutputStream(destination.getFileDescriptor())) {
                    writer.write(stream);stream.flush();
                    callback.onWriteFinished(new PageRange[]{PageRange.ALL_PAGES});
                } catch (Exception e) {
                    callback.onWriteFailed(e.getMessage()==null?"Tidak dapat menjana PDF":e.getMessage());
                }
            }
        };
        PrintAttributes attrs=new PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
            .build();
        // Android's Print Service discovers A4/Wi-Fi/full-colour printers. No raw ESC/POS is sent.
        manager.print(title,adapter,attrs);
    }
}
