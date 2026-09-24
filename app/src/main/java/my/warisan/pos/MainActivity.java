package my.warisan.pos;

import android.app.*;
import android.os.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.content.*;
import android.view.*;
import android.widget.*;
import java.text.*;
import java.util.*;

public class MainActivity extends Activity {

    LinearLayout root, menuBox, cartBox;
    TextView totalText, itemText;
    LinkedHashMap<String,Integer> cart = new LinkedHashMap<>();
    LinkedHashMap<String,Double> prices = new LinkedHashMap<>();

    int green = Color.rgb(19,55,39);
    int cream = Color.rgb(250,246,236);
    int gold = Color.rgb(213,155,45);
    int red = Color.rgb(180,55,45);

    int dp(int n){
        return (int)(n * getResources().getDisplayMetrics().density);
    }

    TextView text(String s,int size,boolean bold){
        TextView t=new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(green);
        t.setPadding(dp(8),dp(8),dp(8),dp(8));
        if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        return t;
    }

    Button btn(String s){
        Button b=new Button(this);
        b.setText(s);
        b.setTextSize(15);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(green);
        return b;
    }

    @Override
    public void onCreate(Bundle b){
        super.onCreate(b);

        prices.put("Sate Ayam",1.60);
        prices.put("Sate Daging",1.80);
        prices.put("Laksa Utara",7.00);
        prices.put("Kuih Siput",5.00);

        draw();
    }

    void draw(){
        ScrollView scroll=new ScrollView(this);

        root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16),dp(18),dp(16),dp(30));
        root.setBackgroundColor(cream);

        TextView title=text("WARISAN POS",30,true);
        title.setTextColor(green);
        root.addView(title);

        TextView sub=text("Kios Warisan • Sistem Jualan",14,false);
        sub.setTextColor(Color.DKGRAY);
        root.addView(sub);

        TextView jual=text("MENU JUALAN",19,true);
        jual.setPadding(dp(8),dp(25),dp(8),dp(10));
        root.addView(jual);

        menuBox=new LinearLayout(this);
        menuBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(menuBox);

        for(String name:prices.keySet()) addProduct(name);

        root.addView(text("🔥 CADANGAN PANTAS SATE AYAM",18,true));

        LinearLayout quick=new LinearLayout(this);
        quick.setOrientation(LinearLayout.HORIZONTAL);

        for(int q:new int[]{10,20,30}){
            Button x=btn(q+" cucuk");
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(55),1);
            p.setMargins(dp(3),dp(3),dp(3),dp(3));
            quick.addView(x,p);
            x.setOnClickListener(v->{
                cart.put("Sate Ayam",cart.getOrDefault("Sate Ayam",0)+q);
                refreshCart();
            });
        }
        root.addView(quick);

        root.addView(text("PESANAN",20,true));

        cartBox=new LinearLayout(this);
        cartBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(cartBox);

        itemText=text("0 item",15,false);
        totalText=text("JUMLAH  RM0.00",27,true);
        totalText.setTextColor(red);

        root.addView(itemText);
        root.addView(totalText);

        Button pay=btn("💵 BAYAR / CHECKOUT");
        root.addView(pay,new LinearLayout.LayoutParams(-1,dp(65)));
        pay.setOnClickListener(v->checkout());

        Button qr=btn("📱 BAYAR QR");
        root.addView(qr,new LinearLayout.LayoutParams(-1,dp(60)));
        qr.setOnClickListener(v->toast("Paparkan QR pembayaran pelanggan"));

        Button print=btn("🖨 CETAK RESIT / PRINTER");
        root.addView(print,new LinearLayout.LayoutParams(-1,dp(60)));
        print.setOnClickListener(v->toast("Modul printer Bluetooth akan disambungkan"));

        Button whatsapp=btn("💬 WHATSAPP / QR WHATSAPP");
        root.addView(whatsapp,new LinearLayout.LayoutParams(-1,dp(60)));
        whatsapp.setOnClickListener(v->toast("QR WhatsApp Warisan"));

        Button history=btn("📊 JUALAN HARI INI & SEJARAH");
        root.addView(history,new LinearLayout.LayoutParams(-1,dp(60)));
        history.setOnClickListener(v->toast("Rekod jualan akan dipaparkan di sini"));

        scroll.addView(root);
        setContentView(scroll);

        refreshCart();
    }

    void addProduct(String name){
        LinearLayout row=new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(5),dp(5),dp(5),dp(5));

        TextView info=text(name+"\nRM "+String.format(Locale.US,"%.2f",prices.get(name)),17,true);

        Button minus=btn("−");
        Button plus=btn("+");

        row.addView(info,new LinearLayout.LayoutParams(0,dp(70),1));
        row.addView(minus,new LinearLayout.LayoutParams(dp(60),dp(55)));
        row.addView(plus,new LinearLayout.LayoutParams(dp(60),dp(55)));

        minus.setOnClickListener(v->{
            int q=cart.getOrDefault(name,0);
            if(q>1)cart.put(name,q-1);
            else cart.remove(name);
            refreshCart();
        });

        plus.setOnClickListener(v->{
            cart.put(name,cart.getOrDefault(name,0)+1);
            refreshCart();
        });

        menuBox.addView(row);
    }

    void refreshCart(){
        cartBox.removeAllViews();

        double total=0;
        int items=0;

        for(String name:cart.keySet()){
            int q=cart.get(name);
            double amount=q*prices.get(name);
            total+=amount;
            items+=q;

            TextView t=text(
                name+"  × "+q+"     RM "+String.format(Locale.US,"%.2f",amount),
                16,true
            );
            cartBox.addView(t);
        }

        if(cart.isEmpty())
            cartBox.addView(text("Belum ada pesanan.",15,false));

        itemText.setText(items+" item");
        totalText.setText("JUMLAH  RM"+String.format(Locale.US,"%.2f",total));
    }

    void checkout(){
        if(cart.isEmpty()){
            toast("Tambah pesanan dahulu");
            return;
        }

        double total=0;
        for(String n:cart.keySet())
            total+=cart.get(n)*prices.get(n);

        final double amount=total;

        new AlertDialog.Builder(this)
            .setTitle("Bayaran")
            .setMessage("Jumlah RM"+String.format(Locale.US,"%.2f",amount))
            .setPositiveButton("TUNAI",(d,w)->complete("Tunai",amount))
            .setNegativeButton("QR",(d,w)->complete("QR",amount))
            .setNeutralButton("Batal",null)
            .show();
    }

    void complete(String method,double total){
        new AlertDialog.Builder(this)
            .setTitle("✓ BAYARAN BERJAYA")
            .setMessage(
                "Kaedah: "+method+
                "\nJumlah: RM"+String.format(Locale.US,"%.2f",total)+
                "\n\nTerima kasih!"
            )
            .setPositiveButton("Pesanan Baru",(d,w)->{
                cart.clear();
                refreshCart();
            })
            .setNegativeButton("Cetak Resit",(d,w)->toast("Sedia untuk printer"))
            .show();
    }

    void toast(String s){
        Toast.makeText(this,s,Toast.LENGTH_SHORT).show();
    }
}
