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
