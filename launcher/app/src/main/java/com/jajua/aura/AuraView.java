package com.jajua.aura;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * Near-black backdrop lit by a soft glow in the colour of the focused app.
 * It only redraws while the colour cross-fades, so it costs nothing when idle.
 */
final class AuraView extends View {
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint glow2 = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint shade = new Paint(Paint.DITHER_FLAG);
    private int color = Ui.ACCENT;
    private ValueAnimator anim;

    AuraView(Context c) {
        super(c);
    }

    void setTint(int target) {
        if (anim != null) anim.cancel();
        if (target == color) return;
        anim = ValueAnimator.ofObject(new ArgbEvaluator(), color, target);
        anim.setDuration(520);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(a -> {
            color = (Integer) a.getAnimatedValue();
            buildShaders();
            invalidate();
        });
        anim.start();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        buildShaders();
        shade.setShader(new LinearGradient(0, h * 0.45f, 0, h,
                Color.TRANSPARENT, 0xCC000000, Shader.TileMode.CLAMP));
    }

    private void buildShaders() {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        glow.setShader(new RadialGradient(w * 0.16f, -h * 0.12f, w * 0.78f,
                new int[] {Ui.withAlpha(color, 0.46f), Ui.withAlpha(color, 0.12f), Color.TRANSPARENT},
                new float[] {0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        glow2.setShader(new RadialGradient(w * 1.02f, h * 0.18f, w * 0.5f,
                Ui.withAlpha(Ui.blend(color, 0xFFFFFFFF, 0.15f), 0.16f), Color.TRANSPARENT,
                Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(Ui.BG);
        canvas.drawPaint(glow);
        canvas.drawPaint(glow2);
        canvas.drawPaint(shade);
    }
}
