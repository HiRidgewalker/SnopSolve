package com.jone.snapsolve;

import android.graphics.Bitmap;

/**
 * 清晰度检测（上传前拦截废图）。
 *
 * 原 App 用的是 `assets/solar_blur_check/model-v6_2.tflite` 训练出来的模糊分类器；
 * 这里不搬它的模型，改用经典无参考指标：拉普拉斯响应的方差。
 * 语义等价——都是"上传前判断这张图糊不糊，糊就别传了"，只是判据不同。
 *
 * 方差越小越糊：清晰文字图通常在 150 以上，明显失焦多在 40 以下。
 */
public final class Sharpness {

    private static final int SAMPLE_LONG_SIDE = 200;

    /**
     * 默认模糊阈值。注意：这是经验值，不是标定值。
     * 拉普拉斯方差与机型、光照、纸面纹理强相关，必须按真机实拍微调，
     * 因此在设置页做成了可改项（设 0 即关闭拦截）。
     */
    public static final int DEFAULT_THRESHOLD = 80;

    private Sharpness() {}

    public static float score(Bitmap src) {
        if (src == null) return 0f;

        int w = src.getWidth(), h = src.getHeight();
        int step = Math.max(1, Math.max(w, h) / SAMPLE_LONG_SIDE);
        int sw = Math.max(8, w / step), sh = Math.max(8, h / step);

        Bitmap small = Bitmap.createScaledBitmap(src, sw, sh, true);
        int[] pix = new int[sw * sh];
        small.getPixels(pix, 0, sw, 0, 0, sw, sh);
        if (small != src) small.recycle();

        float[] gray = new float[sw * sh];
        for (int i = 0; i < pix.length; i++) {
            int c = pix[i];
            gray[i] = 0.299f * ((c >> 16) & 0xFF) + 0.587f * ((c >> 8) & 0xFF) + 0.114f * (c & 0xFF);
        }

        double sum = 0, sumSq = 0;
        int n = 0;
        for (int y = 1; y < sh - 1; y++) {
            for (int x = 1; x < sw - 1; x++) {
                int i = y * sw + x;
                float lap = gray[i - 1] + gray[i + 1] + gray[i - sw] + gray[i + sw] - 4f * gray[i];
                sum += lap;
                sumSq += lap * lap;
                n++;
            }
        }
        if (n == 0) return 0f;

        double mean = sum / n;
        return (float) (sumSq / n - mean * mean);
    }

    public static boolean isBlurry(Bitmap src, int threshold) {
        if (threshold <= 0) return false;
        return score(src) < threshold;
    }
}
