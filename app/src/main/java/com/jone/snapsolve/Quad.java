package com.jone.snapsolve;

/**
 * 四边形（4 个角点，归一化坐标，坐标系为"摆正后"的画面）。
 * 顶点顺序固定：0=左上 1=右上 2=右下 3=左下（顺时针）。
 */
public final class Quad {

    public final float[] x = new float[4];
    public final float[] y = new float[4];

    private Quad() {}

    public static Quad of(float[] xs, float[] ys) {
        Quad q = new Quad();
        System.arraycopy(xs, 0, q.x, 0, 4);
        System.arraycopy(ys, 0, q.y, 0, 4);
        return q;
    }

    /** 用外接矩形构造（矩形检测兜底时使用）。 */
    public static Quad fromRect(float l, float t, float r, float b) {
        return of(new float[]{l, r, r, l}, new float[]{t, t, b, b});
    }

    public Quad copy() {
        return of(x.clone(), y.clone());
    }

    public float area() {
        float s = 0f;
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) & 3;
            s += x[i] * y[j] - x[j] * y[i];
        }
        return Math.abs(s) * 0.5f;
    }

    public float cx() {
        return (x[0] + x[1] + x[2] + x[3]) * 0.25f;
    }

    public float cy() {
        return (y[0] + y[1] + y[2] + y[3]) * 0.25f;
    }

    /** 两帧之间角点的最大位移（归一化），用于判断"稳没稳"。 */
    public float maxCornerMove(Quad o) {
        if (o == null) return Float.MAX_VALUE;
        float m = 0f;
        for (int i = 0; i < 4; i++) {
            float dx = x[i] - o.x[i], dy = y[i] - o.y[i];
            m = Math.max(m, (float) Math.hypot(dx, dy));
        }
        return m;
    }

    public Quad lerp(Quad to, float k) {
        float[] nx = new float[4], ny = new float[4];
        for (int i = 0; i < 4; i++) {
            nx[i] = x[i] + (to.x[i] - x[i]) * k;
            ny[i] = y[i] + (to.y[i] - y[i]) * k;
        }
        return of(nx, ny);
    }

    public Quad expand(float rate) {
        float c1 = cx(), c2 = cy();
        float[] nx = new float[4], ny = new float[4];
        for (int i = 0; i < 4; i++) {
            nx[i] = c1 + (x[i] - c1) * (1f + rate);
            ny[i] = c2 + (y[i] - c2) * (1f + rate);
        }
        return of(nx, ny);
    }

    /** 外接矩形，用于"是否需要重新取景"之类的粗判断。 */
    public String bboxString() {
        float[] b = bbox();
        return String.format(java.util.Locale.US, "[%.3f,%.3f,%.3f,%.3f]", b[0], b[1], b[2], b[3]);
    }

    public float[] bbox() {
        float l = Math.min(Math.min(x[0], x[1]), Math.min(x[2], x[3]));
        float r = Math.max(Math.max(x[0], x[1]), Math.max(x[2], x[3]));
        float t = Math.min(Math.min(y[0], y[1]), Math.min(y[2], y[3]));
        float b = Math.max(Math.max(y[0], y[1]), Math.max(y[2], y[3]));
        return new float[]{l, t, r, b};
    }

    /**
     * 把任意 4 个点规范成 TL/TR/BR/BL 顺时针顺序。
     */
    public static Quad order(float[] xs, float[] ys) {
        float cx = (xs[0] + xs[1] + xs[2] + xs[3]) * 0.25f;
        float cy = (ys[0] + ys[1] + ys[2] + ys[3]) * 0.25f;

        Integer[] idx = {0, 1, 2, 3};
        java.util.Arrays.sort(idx, (a, b) -> {
            double aa = Math.atan2(ys[a] - cy, xs[a] - cx);
            double bb = Math.atan2(ys[b] - cy, xs[b] - cx);
            return Double.compare(aa, bb);
        });

        float[] ox = new float[4], oy = new float[4];
        for (int i = 0; i < 4; i++) {
            ox[i] = xs[idx[i]];
            oy[i] = ys[idx[i]];
        }

        // 保证顺时针（屏幕坐标系下叉积为负则反向）
        float sign = 0f;
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) & 3;
            sign += ox[i] * oy[j] - ox[j] * oy[i];
        }
        if (sign < 0) {
            for (int i = 0; i < 2; i++) {
                float tx = ox[1 + i], ty = oy[1 + i];
                ox[1 + i] = ox[3 - i];
                oy[1 + i] = oy[3 - i];
                ox[3 - i] = tx;
                oy[3 - i] = ty;
            }
        }

        // 旋转到"和最小"的点作为左上
        int start = 0;
        float best = Float.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            float s = ox[i] + oy[i];
            if (s < best) { best = s; start = i; }
        }
        float[] rx = new float[4], ry = new float[4];
        for (int i = 0; i < 4; i++) {
            int k = (start + i) & 3;
            rx[i] = ox[k];
            ry[i] = oy[k];
        }
        return of(rx, ry);
    }

    public boolean isConvex() {
        float sign = 0f;
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) & 3, k = (i + 2) & 3;
            float cr = (x[j] - x[i]) * (y[k] - y[j]) - (y[j] - y[i]) * (x[k] - x[j]);
            if (Math.abs(cr) < 1e-6f) continue;
            if (sign == 0f) sign = Math.signum(cr);
            else if (Math.signum(cr) != sign) return false;
        }
        return true;
    }
}
