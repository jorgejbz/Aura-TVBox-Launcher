package com.jajua.aura;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** A rounded tile with its label underneath. Focus lifts it, outlines it and shows the label. */
final class Card extends LinearLayout {
    interface Listener {
        void onCardFocused(Card card);
        void onCardClicked(Card card);
        void onCardOptions(Card card);
    }

    private static final DecelerateInterpolator EASE = new DecelerateInterpolator(1.6f);
    private static final float LIFT = 1.1f;

    final Entry entry;
    final FrameLayout face;
    private final TextView label;
    private final GradientDrawable ring;
    private final float restLabelAlpha;
    private final int faceH;

    /**
     * @param glyph shortcut cards draw a monochrome glyph on glass instead of the app icon.
     */
    Card(Context c, Entry entry, int faceW, int faceH, float labelSize, boolean labelOnFocusOnly,
            Drawable glyph, Listener listener) {
        super(c);
        this.entry = entry;
        this.faceH = faceH;
        setId(View.generateViewId());
        setOrientation(VERTICAL);
        setClipChildren(false);
        setClipToPadding(false);
        setFocusable(true);
        // Phone-based TV firmwares can start in touch mode; the focus must still be visible.
        setFocusableInTouchMode(true);
        setClickable(true);
        setLongClickable(true);
        // Android 8+ paints a grey box on focused views without a focus state; we draw our own.
        if (android.os.Build.VERSION.SDK_INT >= 26) setDefaultFocusHighlightEnabled(false);

        float radius = faceH * 0.13f;
        face = new FrameLayout(c);
        GradientDrawable bg;
        if (glyph != null) {
            bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[] {0x33FFFFFF, 0x14FFFFFF});
            int s = Math.round(faceH * 0.42f);
            ImageView iv = new ImageView(c);
            iv.setImageDrawable(glyph);
            face.addView(iv, new FrameLayout.LayoutParams(s, s, Gravity.CENTER));
        } else if (entry.banner != null) {
            bg = new GradientDrawable();
            bg.setColor(Ui.blend(entry.tint, 0xFF000000, 0.6f));
            ImageView iv = new ImageView(c);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageDrawable(entry.banner);
            face.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        } else {
            bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] {
                    Ui.blend(entry.tint, 0xFF000000, 0.42f), Ui.blend(entry.tint, 0xFF000000, 0.74f)});
            int s = Math.round(faceH * 0.56f);
            ImageView iv = new ImageView(c);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setImageDrawable(entry.icon);
            face.addView(iv, new FrameLayout.LayoutParams(s, s, Gravity.CENTER));
        }
        bg.setCornerRadius(radius);
        face.setBackground(bg);
        face.setClipToOutline(true);

        // Glass sheen on top plus a focus ring that fades in.
        GradientDrawable sheen = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0x22FFFFFF, 0x00FFFFFF});
        sheen.setCornerRadius(radius);
        ring = new GradientDrawable();
        ring.setColor(0x00000000);
        ring.setCornerRadius(radius);
        ring.setStroke(Math.max(2, Ui.u(2.2f)), 0xF2FFFFFF);
        ring.setAlpha(0);
        face.setForeground(new LayerDrawable(new Drawable[] {sheen, ring}));
        addView(face, new LayoutParams(faceW, faceH));

        label = Ui.text(c, labelSize, Ui.medium, 1f);
        label.setText(entry.label);
        label.setGravity(Gravity.CENTER_HORIZONTAL);
        restLabelAlpha = labelOnFocusOnly ? 0f : 0.62f;
        label.setAlpha(restLabelAlpha);
        LayoutParams lp = new LayoutParams(faceW, LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.u(9);
        addView(label, lp);

        setContentDescription(entry.label);
        setOnFocusChangeListener((v, has) -> {
            animateFocus(has);
            if (has) listener.onCardFocused(this);
        });
        // With focusableInTouchMode a tap would only focus; open on the first tap instead.
        setOnTouchListener((v, ev) -> {
            if (ev.getAction() == android.view.MotionEvent.ACTION_UP && !hasFocus()) {
                requestFocus();
                performClick();
                return true;
            }
            return false;
        });
        setOnClickListener(v -> face.animate().scaleX(1.03f).scaleY(1.03f).setDuration(90)
                .withEndAction(() -> {
                    animateFocus(hasFocus());
                    listener.onCardClicked(this);
                }).start());
        setOnLongClickListener(v -> {
            listener.onCardOptions(this);
            return true;
        });
    }

    private void animateFocus(boolean focused) {
        float s = focused ? LIFT : 1f;
        face.animate().scaleX(s).scaleY(s).translationZ(focused ? Ui.uf(16) : 0f)
                .setDuration(focused ? 190 : 150).setInterpolator(EASE).start();
        label.animate().alpha(focused ? 1f : restLabelAlpha)
                .translationY(focused ? faceH * (LIFT - 1f) / 2f : 0f)
                .setDuration(170).setInterpolator(EASE).start();
        ObjectAnimator.ofInt(ring, "alpha", ring.getAlpha(), focused ? 255 : 0).setDuration(170).start();
    }
}
