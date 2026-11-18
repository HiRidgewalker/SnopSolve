package com.jone.snapsolve;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * 四边形取景框叠加层：
 * 框外压暗（Path 差集）、四边形描边、四角圆点，稳定时整体转绿。
 */
public class OverlayView extends View {

    private final Paint dim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path quadPath = new Path();
    private final Path dimPath = new Path();
    private final RectF full = new RectF();
    private final float[] vx = new float[4];
    private final float[] vy = new float[4];

    private Quad quad;
    private float imageAspect = 16f / 9f;
    private boolean stable;

    private static final int COLOR_IDLE = Color.parseColor("#4DD0E1");
    private static final int COLOR_STABLE = Color.parseColor("#4ADE80");

    public OverlayView(Context c) { this(c, null); }

    public OverlayView(Context c, AttributeSet a) {
        super(c, a);

        dim.setColor(Color.parseColor("#99000000"));
        dim.setStyle(Paint.Style.FILL);

        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(dp(2.5f));
        border.setStrokeJoin(Paint.Join.ROUND);

        dot.setStyle(Paint.Style.FILL);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    public void setQuad(Quad q, float aspect, boolean isStable) {
        this.quad = q == null ? null : q.copy();
        if (aspect > 0.01f) this.imageAspect = aspect;
        this.stable = isStable;
        invalidate();
    }

    /** 归一化坐标 → View 坐标（FILL_CENTER 语义）。 */
    private void project() {
        float vw = getWidth(), vh = getHeight();
        float iw = imageAspect, ih = 1f;
        float scale = Math.max(vw / iw, vh / ih);
        float dw = iw * scale, dh = ih * scale;
        float ox = (vw - dw) * 0.5f, oy = (vh - dh) * 0.5f;

        for (int i = 0; i < 4; i++) {
            vx[i] = ox + quad.x[i] * dw;
            vy[i] = oy + quad.y[i] * dh;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (quad == null || getWidth() == 0 || getHeight() == 0) return;

        project();

        int color = stable ? COLOR_STABLE : COLOR_IDLE;

        quadPath.reset();
        quadPath.moveTo(vx[0], vy[0]);
        quadPath.lineTo(vx[1], vy[1]);
        quadPath.lineTo(vx[2], vy[2]);
        quadPath.lineTo(vx[3], vy[3]);
        quadPath.close();

        // 框外压暗：整屏 减去 四边形
        full.set(0, 0, getWidth(), getHeight());
        dimPath.reset();
        dimPath.addRect(full, Path.Direction.CW);
        dimPath.op(quadPath, Path.Op.DIFFERENCE);
        canvas.drawPath(dimPath, dim);

        border.setColor(color);
        canvas.drawPath(quadPath, border);

        dot.setColor(color);
        float r = dp(5f);
        for (int i = 0; i < 4; i++) {
            canvas.drawCircle(vx[i], vy[i], r, dot);
        }

        // 稳定时加一圈淡淡的光晕，提示"马上拍"
        if (stable) {
            dot.setColor(Color.parseColor("#334ADE80"));
            for (int i = 0; i < 4; i++) {
                canvas.drawCircle(vx[i], vy[i], r * 2.4f, dot);
            }
        }
    }
}
