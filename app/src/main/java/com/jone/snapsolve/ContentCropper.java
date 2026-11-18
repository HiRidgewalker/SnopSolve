package com.jone.snapsolve;

import android.graphics.Bitmap;

/**
 * 第二级裁剪：把已经拉正的整页图，再收紧到"真正有内容的那一块"。
 *
 * 这是"切小图上传"的关键一步——只做透视矫正的话，传的还是整页；
 * 这里再跑一次文字密度检测，裁到题干区域，上传体积能再降一大截。
 *
 * 判定保守：只在结果合理时才裁（避免把单行字裁成一条，或裁掉页眉条件）。
 */
public final class ContentCropper {

    private static final int SAMPLE_LONG_SIDE = 200;
    private static final float MIN_KEEP_RATIO = 0.10f;  // 裁完只剩 10% 以下 → 太狠，放弃
    private static final float MAX_KEEP_RATIO = 0.92f;  // 几乎没裁 → 没意义，放弃
    private static final float MIN_SHORT_SIDE = 0.15f;  // 任一边小于 15% → 是细条，放弃
    private static final float MARGIN = 0.03f;          // 四周留白

    /**
     * 横向最多允许裁掉这么多（左右合计）。
     * 试卷/整页练习册的正文是通栏的，密度检测容易只框到中间一段，
     * 一旦裁多就会把每行两头的字切掉——宁可多传几十 KB，也不能切断题目。
     */
    private static final float MAX_H_CROP = 0.22f;
    /** 纵向允许裁得更狠一些：上下大片空白很常见。 */
    private static final float MAX_V_CROP = 0.55f;

    private ContentCropper() {}

    public static Bitmap tighten(Bitmap src) {
        if (src == null) return null;

        int w = src.getWidth(), h = src.getHeight();
        if (w < 64 || h < 64) return src;

        int step = Math.max(1, Math.max(w, h) / SAMPLE_LONG_SIDE);
        int sw = Math.max(32, w / step);
        int sh = Math.max(32, h / step);

        Bitmap small = Bitmap.createScaledBitmap(src, sw, sh, false);
        int[] pix = new int[sw * sh];
        small.getPixels(pix, 0, sw, 0, 0, sw, sh);
        if (small != src) small.recycle();

        byte[] gray = new byte[sw * sh];
        for (int i = 0; i < pix.length; i++) {
            int c = pix[i];
            int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
            gray[i] = (byte) ((299 * r + 587 * g + 114 * b) / 1000);
        }

        Quad content = QuadDetector.contentRect(gray, sw, sh);
        if (content == null) return src;

        // contentRect 返回的是"采样图"的像素坐标，先归一化
        float[] bb = content.bbox();
        float l = bb[0] / sw - MARGIN, t = bb[1] / sh - MARGIN;
        float r = bb[2] / sw + MARGIN, b = bb[3] / sh + MARGIN;

        l = Math.max(0f, l);
        t = Math.max(0f, t);
        r = Math.min(1f, r);
        b = Math.min(1f, b);

        // 横向裁太多说明密度检测没覆盖整页正文，直接放弃横向裁剪，只保留纵向
        if (1f - (r - l) > MAX_H_CROP) {
            l = 0f;
            r = 1f;
        }
        if (1f - (b - t) > MAX_V_CROP) {
            t = 0f;
            b = 1f;
        }

        android.util.Log.d("SnapSolve", String.format(java.util.Locale.US,
                "tighten bbox=[%.2f,%.2f,%.2f,%.2f] -> crop=[%.2f,%.2f,%.2f,%.2f]",
                bb[0] / sw, bb[1] / sh, bb[2] / sw, bb[3] / sh, l, t, r, b));

        float keep = (r - l) * (b - t);
        if (keep < MIN_KEEP_RATIO || keep > MAX_KEEP_RATIO) return src;
        if (Math.min(r - l, b - t) < MIN_SHORT_SIDE) return src;

        int x = Math.max(0, (int) (l * w));
        int y = Math.max(0, (int) (t * h));
        int cw = Math.min(w - x, Math.max(8, (int) ((r - l) * w)));
        int ch = Math.min(h - y, Math.max(8, (int) ((b - t) * h)));

        try {
            return Bitmap.createBitmap(src, x, y, cw, ch);
        } catch (IllegalArgumentException e) {
            return src;
        }
    }
}
