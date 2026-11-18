package com.jone.snapsolve;

/**
 * 单位正方形 → 四边形 的单应矩阵（纯数学，无 Android 依赖，便于离线验证）。
 *
 * 系数含义：
 *   x = (a11*u + a21*v + a31) / (a13*u + a23*v + 1)
 *   y = (a12*u + a22*v + a32) / (a13*u + a23*v + 1)
 * 其中 (u,v) ∈ [0,1]²，四边形顶点顺序 TL(0,0) TR(1,0) BR(1,1) BL(0,1)。
 */
public final class Homography {

    private Homography() {}

    /** 返回 {a11,a21,a31,a12,a22,a32,a13,a23}；退化时返回 null。 */
    public static double[] squareToQuad(float[] px, float[] py) {
        double x0 = px[0], y0 = py[0], x1 = px[1], y1 = py[1];
        double x2 = px[2], y2 = py[2], x3 = px[3], y3 = py[3];

        double dx1 = x1 - x2, dx2 = x3 - x2, dx3 = x0 - x1 + x2 - x3;
        double dy1 = y1 - y2, dy2 = y3 - y2, dy3 = y0 - y1 + y2 - y3;

        double a11, a21, a31, a12, a22, a32, a13, a23;

        if (Math.abs(dx3) < 1e-9 && Math.abs(dy3) < 1e-9) {
            // 仿射（无透视）情形
            a11 = x1 - x0; a21 = x2 - x1; a31 = x0;
            a12 = y1 - y0; a22 = y2 - y1; a32 = y0;
            a13 = 0; a23 = 0;
        } else {
            double den = dx1 * dy2 - dx2 * dy1;
            if (Math.abs(den) < 1e-9) return null;
            a13 = (dx3 * dy2 - dx2 * dy3) / den;
            a23 = (dx1 * dy3 - dx3 * dy1) / den;
            a11 = x1 - x0 + a13 * x1;
            a21 = x3 - x0 + a23 * x3;
            a31 = x0;
            a12 = y1 - y0 + a13 * y1;
            a22 = y3 - y0 + a23 * y3;
            a32 = y0;
        }
        return new double[]{a11, a21, a31, a12, a22, a32, a13, a23};
    }

    /** 把 (u,v) 映射到目标点，结果写入 out[0]=x, out[1]=y。 */
    public static void map(double[] m, double u, double v, double[] out) {
        double den = m[6] * u + m[7] * v + 1.0;
        if (Math.abs(den) < 1e-12) den = 1e-12;
        out[0] = (m[0] * u + m[1] * v + m[2]) / den;
        out[1] = (m[3] * u + m[4] * v + m[5]) / den;
    }
}
