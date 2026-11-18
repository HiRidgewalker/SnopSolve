package com.jone.snapsolve;

import android.graphics.Bitmap;

import java.io.ByteArrayOutputStream;

/**
 * 压缩到"体积上限"。
 *
 * 对应原 App 的 `ImageUploadConfig`：不只有长边上限，还有一个字节数上限
 * （`imageMaxSize`），超了就降质、再不行就缩尺寸，直到进线。
 *
 * 这样做的好处：弱网下不会因为某张图特别"花"而传出去几百 KB。
 */
public final class ImageCompressor {

    /** 长边上限 */
    public static final int MAX_SIDE = 1600;
    /** 字节上限：300 KB（base64 后约 400 KB） */
    public static final int MAX_BYTES = 300 * 1024;
    /** 最低质量，低于这个就不降质了，改缩尺寸 */
    private static final int MIN_QUALITY = 50;

    private ImageCompressor() {}

    public static byte[] compress(Bitmap src) {
        Bitmap cur = capSide(src, MAX_SIDE);

        int quality = 88;
        byte[] data = encode(cur, quality);
        if (data.length <= MAX_BYTES) return data;

        for (quality = 80; quality >= MIN_QUALITY; quality -= 10) {
            data = encode(cur, quality);
            if (data.length <= MAX_BYTES) return data;
        }

        // 降质到下限还超标 → 缩尺寸重来
        float scale = 0.85f;
        for (int i = 0; i < 5; i++) {
            int w = (int) (cur.getWidth() * scale);
            int h = (int) (cur.getHeight() * scale);
            if (w < 320 || h < 320) break;
            Bitmap scaled = Bitmap.createScaledBitmap(cur, w, h, true);
            data = encode(scaled, 78);
            if (data.length <= MAX_BYTES) return data;
            scale *= 0.85f;
        }
        return data;
    }

    private static Bitmap capSide(Bitmap src, int maxSide) {
        int w = src.getWidth(), h = src.getHeight();
        int longSide = Math.max(w, h);
        if (longSide <= maxSide) return src;
        float s = maxSide / (float) longSide;
        return Bitmap.createScaledBitmap(src,
                Math.max(1, (int) (w * s)), Math.max(1, (int) (h * s)), true);
    }

    private static byte[] encode(Bitmap bmp, int quality) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, bos);
        return bos.toByteArray();
    }
}
