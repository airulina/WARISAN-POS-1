package my.warisan.pos;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Five display-only themes. A single device preference is used regardless of
 * Owner/Staff/branch; this never touches POS stock, sales, Firestore, receipts,
 * payments, or any branch-scoped preferences.
 */
final class WarisanTheme {
  private static final String PREFS = "warisan_theme";
  private static final String KEY = "selected_theme";
  static final String DEFAULT = "classic";

  static final class Palette {
    final String id,name,description;
    final int background,card,cardAlt,text,subtle,accent,primary;
    final int onPrimary,onAccent,navUnselected,navUnselectedText;
    final boolean light;
    Palette(String id,String name,String description,String background,String card,
      String cardAlt,String text,String subtle,String accent,String primary,
      String onPrimary,String onAccent,String navUnselected,String navUnselectedText,
      boolean light){
      this.id=id;this.name=name;this.description=description;
      this.background=Color.parseColor(background);
      this.card=Color.parseColor(card);
      this.cardAlt=Color.parseColor(cardAlt);
      this.text=Color.parseColor(text);
      this.subtle=Color.parseColor(subtle);
      this.accent=Color.parseColor(accent);
      this.primary=Color.parseColor(primary);
      this.onPrimary=Color.parseColor(onPrimary);
      this.onAccent=Color.parseColor(onAccent);
      this.navUnselected=Color.parseColor(navUnselected);
      this.navUnselectedText=Color.parseColor(navUnselectedText);
      this.light=light;
    }
  }

  private static final Palette[] THEMES = new Palette[]{
    new Palette("classic","Warisan Classic","Biru gelap + emas",
      "#071422","#122840","#203F61","#EEF4FC","#A6B7CC",
      "#DAA630","#356BBC","#FFFFFF","#131822","#143A65","#FFFFFF",false),
    new Palette("black","Black Gold","Hitam + emas premium",
      "#090A0C","#1B1D22","#303136","#FFF8E8","#B4B0A4",
      "#F6C653","#B58A36","#151515","#191919","#25262A","#E3D5BA",false),
    new Palette("carbon","Carbon Red","Graphite + merah",
      "#101215","#23272C","#393D42","#F8F8F8","#B3BAC3",
      "#FF685D","#D83735","#FFFFFF","#1A1A1A","#2D3136","#E5E9ED",false),
    new Palette("jade","Jade Dark","Hijau zamrud",
      "#061C19","#12352D","#225345","#F0FFF8","#A9D0BF",
      "#B4E3AE","#0C765D","#FFFFFF","#08241E","#194339","#E2F7EA",false),
    new Palette("light","Clean Blue","Putih + biru moden",
      "#EAF1F9","#FFFFFF","#DBE7F6","#1B2E49","#647895",
      "#1D5FC0","#276ECF","#FFFFFF","#FFFFFF","#DBE7F6","#254467",true)
  };

  static Palette byId(String id){
    for(Palette p:THEMES)if(p.id.equals(id))return p;
    return THEMES[0];
  }
  static Palette current(Context context){
    return byId(context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
      .getString(KEY,DEFAULT));
  }
  private static double linear(int component){
    double v=component/255.0;
    return v<=0.04045?v/12.92:Math.pow((v+0.055)/1.055,2.4);
  }
  private static double luminance(int color){
    return 0.2126*linear(Color.red(color))+
      0.7152*linear(Color.green(color))+0.0722*linear(Color.blue(color));
  }
  static double contrastRatio(int first,int second){
    double a=luminance(first),b=luminance(second);
    return (Math.max(a,b)+0.05)/(Math.min(a,b)+0.05);
  }
  private static int blend(int from,int to,float fraction){
    return Color.rgb(
      Math.round(Color.red(from)*(1-fraction)+Color.red(to)*fraction),
      Math.round(Color.green(from)*(1-fraction)+Color.green(to)*fraction),
      Math.round(Color.blue(from)*(1-fraction)+Color.blue(to)*fraction));
  }
  /** Protects text on every palette, including legacy literal foreground colors. */
  static int readableText(int preferred,int background,int fallback){
    if(contrastRatio(preferred,background)>=4.5)return preferred;
    int max=Math.max(Color.red(preferred),Math.max(Color.green(preferred),Color.blue(preferred)));
    int min=Math.min(Color.red(preferred),Math.min(Color.green(preferred),Color.blue(preferred)));
    // Neutral old white/grey lettering should use the current theme's text.
    if(max-min<30&&contrastRatio(fallback,background)>=4.5)return fallback;
    // Preserve warning/accent hue, but lighten or darken it until readable.
    int target=luminance(background)>0.179?Color.BLACK:Color.WHITE;
    for(int step=1;step<=20;step++){
      int adjusted=blend(preferred,target,step/20f);
      if(contrastRatio(adjusted,background)>=4.5)return adjusted;
    }
    return contrastRatio(fallback,background)>=4.5?fallback:target;
  }
  static void apply(MainActivity host){
    Palette p=current(host);
    host.cream=p.background;
    host.surface=p.card;
    host.surfaceAlt=p.cardAlt;
    host.ink=p.text;
    host.muted=p.subtle;
    host.gold=p.accent;
    host.blue=p.primary;
    host.onPrimary=p.onPrimary;
    host.onAccent=p.onAccent;
    host.navOff=p.navUnselected;
    host.navOffText=p.navUnselectedText;
    host.themeLight=p.light;
  }
  static void choose(MainActivity host){
    Palette selected=current(host);
    ScrollView scroll=new ScrollView(host);
    LinearLayout list=host.col();
    list.setPadding(host.dp(14),host.dp(10),host.dp(14),host.dp(10));
    list.setBackgroundColor(host.surface);
    scroll.addView(list);
    AlertDialog dialog=host.darkBuilder().setTitle("TEMA WARISAN POS")
      .setView(scroll).setNegativeButton("TUTUP",null).create();
    for(Palette p:THEMES){
      LinearLayout tile=host.row();
      tile.setGravity(Gravity.CENTER_VERTICAL);
      tile.setPadding(host.dp(11),host.dp(10),host.dp(11),host.dp(10));
      GradientDrawable tileBackground=host.shape(p.background,13);
      tileBackground.setStroke(host.dp(p.id.equals(selected.id)?3:1),
        p.id.equals(selected.id)?p.accent:p.cardAlt);
      tile.setBackground(tileBackground);

      LinearLayout preview=host.col();
      preview.setGravity(Gravity.CENTER);
      preview.setBackground(host.shape(p.card,9));
      preview.setPadding(host.dp(8),host.dp(7),host.dp(8),host.dp(7));
      TextView sample=host.text("Aa 123",12,readableText(p.accent,p.card,p.text),true);
      sample.setTextColor(readableText(p.accent,p.card,p.text));
      sample.setGravity(Gravity.CENTER);
      host.add(preview,sample,-1,-2);
      host.gap(preview,4);
      LinearLayout swatches=host.row();
      for(int color:new int[]{p.background,p.card,p.accent,p.primary}){
        View swatch=new View(host);
        GradientDrawable shape=new GradientDrawable();
        shape.setColor(color);shape.setCornerRadius(host.dp(5));
        shape.setStroke(host.dp(1),p.subtle);
        swatch.setBackground(shape);
        LinearLayout.LayoutParams params=host.params(16,16);
        params.setMargins(host.dp(2),0,host.dp(2),0);
        swatches.addView(swatch,params);
      }
      host.add(preview,swatches,-2,-2);
      host.add(tile,preview,-2,36);

      LinearLayout info=host.col();
      info.setPadding(host.dp(12),0,0,0);
      TextView name=host.text(p.name,15,p.text,true);
      host.add(info,name,-1,-2);
      host.add(info,host.text(p.description,11,p.subtle,false),-1,-2);
      tile.addView(info,new LinearLayout.LayoutParams(0,-2,1));
      if(p.id.equals(selected.id)){
        TextView check=host.text("✓",22,p.accent,true);
        host.add(tile,check,-2,-2);
      }
      host.add(list,tile,-1,-2);
      host.gap(list,8);
      tile.setOnClickListener(v->{
        SharedPreferences settings=host.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        if(settings.edit().putString(KEY,p.id).commit()){
          apply(host);
          dialog.dismiss();
          host.draw();
          host.message("Tema ditukar kepada "+p.name+".");
        }else host.message("Tema gagal disimpan. Cuba semula.");
      });
    }
    host.applyDarkReportDialog(dialog);
  }
}
