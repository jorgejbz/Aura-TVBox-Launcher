package com.jajua.aura;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Rect;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

/**
 * A clipping window over one child laid out at its natural size, scrolled smoothly to keep
 * the focused card in view. Unlike ScrollView it never jumps on its own, so the D-pad
 * movement and the scroll animation do not fight each other.
 */
final class Viewport extends FrameLayout {
    private final boolean vertical;
    private final Rect tmp = new Rect();
    private ValueAnimator anim;

    Viewport(Context c, boolean vertical) {
        super(c);
        this.vertical = vertical;
        setFadingEdgeLength(Ui.u(vertical ? 60 : 44));
        if (vertical) setVerticalFadingEdgeEnabled(true);
        else setHorizontalFadingEdgeEnabled(true);
        setHorizontalScrollBarEnabled(false);
        setVerticalScrollBarEnabled(false);
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        View child = getChildAt(0);
        int w = MeasureSpec.getSize(wSpec);
        int unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        if (vertical) {
            child.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), unspecified);
            setMeasuredDimension(w, MeasureSpec.getSize(hSpec));
        } else {
            child.measure(unspecified, unspecified);
            setMeasuredDimension(w, child.getMeasuredHeight());
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        View child = getChildAt(0);
        child.layout(0, 0, child.getMeasuredWidth(), child.getMeasuredHeight());
        scrollTo(clamp(getScrollX(), maxX()), clamp(getScrollY(), maxY()));
    }

    /** Scrolls so that {@code v} (any descendant) is comfortably on screen. */
    void reveal(View v, boolean animate) {
        v.getDrawingRect(tmp);
        offsetDescendantRectToMyCoords(v, tmp);
        int x = getScrollX(), y = getScrollY();
        if (vertical) {
            // Keep the top of the page while the focus is in its upper part; otherwise
            // bring the focused row to just above the middle.
            y = tmp.bottom < getHeight() * 0.78f ? 0 : Math.round(tmp.centerY() - getHeight() * 0.42f);
        } else {
            int edge = Math.round(tmp.width() * 0.45f);
            if (tmp.right + edge > x + getWidth()) x = tmp.right + edge - getWidth();
            else if (tmp.left - edge < x) x = tmp.left - edge;
        }
        smoothTo(clamp(x, maxX()), clamp(y, maxY()), animate);
    }

    void reset() {
        smoothTo(0, 0, true);
    }

    private void smoothTo(int x, int y, boolean animate) {
        if (anim != null) anim.cancel();
        final int sx = getScrollX(), sy = getScrollY();
        if (sx == x && sy == y) return;
        if (!animate) {
            scrollTo(x, y);
            return;
        }
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(300);
        anim.setInterpolator(new DecelerateInterpolator(1.8f));
        anim.addUpdateListener(a -> {
            float f = (Float) a.getAnimatedValue();
            scrollTo(Math.round(sx + (x - sx) * f), Math.round(sy + (y - sy) * f));
        });
        anim.start();
    }

    private int maxX() {
        return getChildCount() == 0 ? 0 : Math.max(0, getChildAt(0).getWidth() - getWidth());
    }

    private int maxY() {
        return getChildCount() == 0 ? 0 : Math.max(0, getChildAt(0).getHeight() - getHeight());
    }

    private static int clamp(int v, int max) {
        return Math.max(0, Math.min(v, max));
    }

    @Override
    protected float getTopFadingEdgeStrength() {
        return vertical && getScrollY() > 0 ? 1f : 0f;
    }

    @Override
    protected float getBottomFadingEdgeStrength() {
        return vertical && getScrollY() < maxY() ? 1f : 0f;
    }

    @Override
    protected float getLeftFadingEdgeStrength() {
        return !vertical && getScrollX() > 0 ? 1f : 0f;
    }

    @Override
    protected float getRightFadingEdgeStrength() {
        return !vertical && getScrollX() < maxX() ? 1f : 0f;
    }
}
