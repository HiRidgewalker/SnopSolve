package com.jone.snapsolve;

import android.graphics.Bitmap;

/**
 * 透视矫正：把任意凸四边形"拉正"成矩形。
 *
 * 单应矩阵由 {@link Homography} 提供；这里负责按目标图逐像素反查源图并做双线性采样。
 */
public final class Perspective {

    private Perspective() {}

    /**
     * @param src     原图
     * @param px,py   源四边形角点（像素坐标），顺序 TL/TR/BR/BL
     * @param maxSide 输出长边上限
     */
    public static Bitmap warp(Bitmap src, float[] px, float[] py, int maxSide) {
        double[] m = Homography.squareToQuad(px, py);
        if (m == null) return null;

        int sw = src.getWidth(), sh = src.getHeight();

        float wTop = dist(px[0], py[0], px[1], py[1]);
        float wBot = dist(px[3], py[3], px[2], py[2]);
        float hLeft = dist(px[0], py[0], px[3], py[3]);
        float hRight = dist(px[1], py[1], px[2], py[2]);

        int outW = Math.max(32, Math.round(Math.max(wTop, wBot)));
        int outH = Math.max(32, Math.round(Math.max(hLeft, hRight)));

        int longSide = Math.max(outW, outH);
        if (longSide > maxSide) {
            float s = maxSide / (float) longSide;
            outW = Math.max(32, Math.round(outW * s));
            outH = Math.max(32, Math.round(outH * s));
        }

        int[] srcPix = new int[sw * sh];
        src.getPixels(srcPix, 0, sw, 0, 0, sw, sh);
        int[] out = new int[outW * outH];

        float invW = outW > 1 ? 1f / (outW - 1) : 0f;
        float invH = outH > 1 ? 1f / (outH - 1) : 0f;
        double[] pt = new double[2];

        for (int oy = 0; oy < outH; oy++) {
            double v = oy * invH;
            int row = oy * outW;
            for (int ox = 0; ox < outW; ox++) {
                Homography.map(m, ox * invW, v, pt);
                out[row + ox] = sample(srcPix, sw, sh, pt[0], pt[1]);
            }
        }

        return Bitmap.createBitmap(out, outW, outH, Bitmap.Config.ARGB_8888);
    }

    private static int sample(int[] pix, int w, int h, double fx, double fy) {
        if (fx < 0) fx = 0; else if (fx > w - 1) fx = w - 1;
        if (fy < 0) fy = 0; else if (fy > h - 1) fy = h - 1;

        int x0 = (int) fx, y0 = (int) fy;
        int x1 = Math.min(w - 1, x0 + 1), y1 = Math.min(h - 1, y0 + 1);
        float tx = (float) (fx - x0), ty = (float) (fy - y0);

        return bilinear(pix[y0 * w + x0], pix[y0 * w + x1],
                pix[y1 * w + x0], pix[y1 * w + x1], tx, ty);
    }

    private static int bilinear(int p00, int p10, int p01, int p11, float tx, float ty) {
        int r = lerp(ch(p00, 16), ch(p10, 16), tx, ch(p01, 16), ch(p11, 16), ty);
        int g = lerp(ch(p00, 8), ch(p10, 8), tx, ch(p01, 8), ch(p11, 8), ty);
        int b = lerp(ch(p00, 0), ch(p10, 0), tx, ch(p01, 0), ch(p11, 0), ty);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int ch(int c, int shift) {
        return (c >> shift) & 0xFF;
    }

    private static int lerp(float v00, float v10, float tx, float v01, float v11, float ty) {
        float top = v00 + (v10 - v00) * tx;
        float bot = v01 + (v11 - v01) * tx;
        return Math.round(top + (bot - top) * ty);
    }

    private static float dist(float x1, float y1, float x2, float y2) {
        return (float) Math.hypot(x2 - x1, y2 - y1);
    }
}
