package dev.zips.clientbase.host;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Resource-free host styling, usable without resolving our resource IDs against guest assets. */
final class ClientUi {
    static final int BG = Color.rgb(10, 15, 22), CARD = Color.rgb(20, 29, 40), LINE = Color.rgb(40, 55, 69);
    static final int TEXT = Color.rgb(234, 243, 249), MUTED = Color.rgb(151, 172, 189), ACCENT = Color.rgb(93, 235, 205);
    static int dp(Context c, float value) { return Math.round(value * c.getResources().getDisplayMetrics().density); }
    static GradientDrawable shape(int color, int stroke, Context c, int radius) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(c, radius));
        if (stroke != 0) d.setStroke(dp(c, 1), stroke); return d;
    }
    static TextView label(Context c, String text, int size, int color) {
        TextView v = new TextView(c); v.setText(text); v.setTextSize(size); v.setTextColor(color);
        v.setPadding(0, dp(c, 7), 0, dp(c, 7)); return v;
    }
    static LinearLayout column(Context c) { LinearLayout v = new LinearLayout(c); v.setOrientation(LinearLayout.VERTICAL); return v; }
    static LinearLayout card(Context c) {
        LinearLayout v = column(c); int p = dp(c, 16); v.setPadding(p,p,p,p);
        v.setBackground(shape(CARD, LINE, c, 18)); return v;
    }
    static Button button(Context c, String text, boolean primary) {
        Button b = new Button(c); b.setText(text); b.setAllCaps(false); b.setTextSize(14);
        b.setTextColor(primary ? BG : TEXT); b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setBackgroundTintList(null); b.setBackground(shape(primary ? ACCENT : CARD, primary ? 0 : LINE, c, 12));
        b.setMinHeight(dp(c,48)); b.setMinimumHeight(dp(c,48));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0,dp(c,5),0,dp(c,5)); b.setLayoutParams(params); return b;
    }
    static void gap(LinearLayout target, int amount) {
        target.addView(new View(target.getContext()), new LinearLayout.LayoutParams(1, dp(target.getContext(),amount)));
    }
}
