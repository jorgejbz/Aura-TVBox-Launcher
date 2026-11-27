package com.jajua.aura;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Aura's side panel: the same shape as the TV settings panel (right edge, 270 x 540 on Aura's
 * canvas, dark header, rows with a white focus veil) in Aura's colours, so Aura's own settings
 * screens and the system ones look like one menu.
 */
final class SidePanel {
    static final int PANEL = 0xFF101218;
    static final int FOCUS = 0x59FFFFFF;

    final LinearLayout list;
    final TextView subtitle;

    private SidePanel(LinearLayout list, TextView subtitle) {
        this.list = list;
        this.subtitle = subtitle;
    }

    /** Fills the activity with a dimmed backdrop and the panel; tapping outside closes it. */
    static SidePanel show(Activity a, CharSequence title) {
        Ui.init(a);
        FrameLayout root = new FrameLayout(a);
        root.setBackgroundColor(0x99000000);
        root.setOnClickListener(v -> a.finish());

        LinearLayout side = new LinearLayout(a);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setBackgroundColor(PANEL);
        side.setClickable(true);
        root.addView(side, new FrameLayout.LayoutParams(Ui.u(270), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END));

        LinearLayout head = new LinearLayout(a);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(Ui.u(18), 0, Ui.u(18), 0);
        head.setBackgroundColor(Ui.BG);
        TextView t = Ui.text(a, 15f, Ui.regular, 1f);
        t.setText(title);
        TextView sub = Ui.text(a, 8.5f, Ui.regular, 0.55f);
        sub.setPadding(0, Ui.u(4), 0, 0);
        sub.setVisibility(View.GONE);
        head.addView(t);
        head.addView(sub);
        side.addView(head, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.u(68)));

        ScrollView scroll = new ScrollView(a);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, Ui.u(4), 0, Ui.u(16));
        scroll.addView(list);
        side.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        a.setContentView(root);
        return new SidePanel(list, sub);
    }

    void setSubtitle(CharSequence s) {
        subtitle.setText(s);
        subtitle.setVisibility(s == null ? View.GONE : View.VISIBLE);
    }

    /** A small lavender section title, like "Dispositivo" in the TV settings. */
    void section(Context c, int text) {
        TextView t = Ui.text(c, 8f, Ui.medium, 1f);
        t.setTextColor(Ui.ACCENT);
        t.setText(text);
        t.setPadding(Ui.u(18), Ui.u(12), Ui.u(18), Ui.u(5));
        list.addView(t);
    }

    /** A focusable row: icon, title, optional subtitle, optional trailing view (a {@link Pill}). */
    Row row(Context c, Drawable icon, CharSequence title, CharSequence sub, View trailing, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.u(18), Ui.u(6), Ui.u(18), Ui.u(6));
        row.setMinimumHeight(Ui.u(38));
        row.setFocusable(true);
        row.setClickable(true);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[] {android.R.attr.state_focused}, new ColorDrawable(FOCUS));
        bg.addState(new int[0], new ColorDrawable(0));
        row.setBackground(bg);
        row.setOnClickListener(click);

        if (icon != null) {
            ImageView iv = new ImageView(c);
            iv.setImageDrawable(icon);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(Ui.u(17), Ui.u(17));
            ip.rightMargin = Ui.u(13);
            row.addView(iv, ip);
        }
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView name = Ui.text(c, 10.5f, Ui.regular, 1f);
        name.setText(title);
        TextView s = Ui.text(c, 8f, Ui.regular, 0.6f);
        s.setPadding(0, Ui.u(3), 0, 0);
        texts.addView(name);
        texts.addView(s);
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (trailing != null) row.addView(trailing, new LinearLayout.LayoutParams(Ui.u(26), Ui.u(14)));
        list.addView(row);
        Row r = new Row(row, name, s);
        r.setSub(sub);
        return r;
    }

    static final class Row {
        final View view;
        final TextView title, sub;

        Row(View view, TextView title, TextView sub) {
            this.view = view;
            this.title = title;
            this.sub = sub;
        }

        void setSub(CharSequence text) {
            sub.setText(text);
            sub.setVisibility(text == null ? View.GONE : View.VISIBLE);
        }
    }

    /** Aura's switch: lavender track when on, a dim one when off, white knob. */
    static final class Pill extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private boolean on;

        Pill(Context c) {
            super(c);
        }

        void setOn(boolean on) {
            this.on = on;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float h = getHeight(), w = getWidth(), rad = h / 2f;
            r.set(0, 0, w, h);
            paint.setColor(on ? Ui.ACCENT : 0x4DFFFFFF);
            c.drawRoundRect(r, rad, rad, paint);
            paint.setColor(0xFFFFFFFF);
            c.drawCircle(on ? w - rad : rad, rad, rad - Ui.uf(2), paint);
        }
    }
}
