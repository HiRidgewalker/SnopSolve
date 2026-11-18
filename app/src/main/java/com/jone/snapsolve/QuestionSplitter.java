package com.jone.snapsolve;

/**
 * 一页多题时，把"整页框"收紧到"单题框"。
 *
 * 做法是纯投影分析，不需要模型：在摆正后的纸面上按行统计墨迹密度，
 * 用题目之间的大段空白把纸面切成若干横带，再取最靠近画面中心（也就是用户
 * 正对着的那道题）的一带。
 *
 * 对标 leo 的分学科模型在这件事上的作用：它靠版面先验锁定单题边界，
 * 我们靠空白带分割，判据不同但目标一致。
 */
public final class QuestionSplitter {

    /** 一行中有多少比例的像素属于"墨迹"才算有字（2%）。 */
    private static final float INK_RATIO = 0.02f;
    /** 比纸面底色暗多少算墨迹。 */
    private static final int INK_DELTA = 45;
    /**
     * 小于这个高度的空白不当作分题间隙（相对纸面高度）。
     * 太小会把题内行间距当成题间空白，切出一堆碎片；太大又会把相邻两题并掉。
     * 4% 是"行距约 1~2%、题距约 8% 以上"这类常见版面下的折中值。
     */
    private static final float MIN_GAP_RATIO = 0.04f;
    /** 太矮的横带直接忽略（相对画面高度），避免噪声。 */
    private static final float MIN_BAND_RATIO = 0.05f;
    /** 切出来的题块必须明显小于整页，否则不切（相对纸面高度）。 */
    private static final float MAX_BAND_RATIO = 0.62f;

    private QuestionSplitter() {}


    /**
     * @param gray   摆正后的灰度图
     * @param quad   当前整页四边形（归一化）
     * @return 收紧后的单题四边形；不需要/不适用时返回 null
     */
    public static Quad refine(byte[] gray, int w, int h, Quad quad) {
        if (gray == null || quad == null) return null;

        float[] bb = quad.bbox();
        int l = Math.max(0, (int) (bb[0] * w));
        int r = Math.min(w - 1, (int) (bb[2] * w));
        int t = Math.max(0, (int) (bb[1] * h));
        int b = Math.min(h - 1, (int) (bb[3] * h));
        if (r - l < 24 || b - t < 24) return null;

        int pageH = b - t;
        int bandCount = pageH;
        float[] ink = new float[bandCount];

        // 先估纸面底色（隔点采样求均值），再按"明显比底色暗"判墨迹。
        // 这样不依赖笔画方向：实心横条、公式、表格线都能算进去。
        long sum = 0;
        int n = 0;
        for (int y = t; y < b; y += 3) {
            int row = y * w;
            for (int x = l; x < r; x += 3) {
                sum += gray[row + x] & 0xFF;
                n++;
            }
        }
        if (n == 0) return null;
        int bg = (int) (sum / n);
        int inkLevel = bg - INK_DELTA;

        for (int y = t; y < b; y++) {
            int row = y * w;
            int hits = 0, total = 0;
            for (int x = l; x < r; x++) {
                if ((gray[row + x] & 0xFF) < inkLevel) hits++;
                total++;
            }
            ink[y - t] = total == 0 ? 0f : hits / (float) total;
        }

        // 找连续有字的行段（band）
        java.util.ArrayList<int[]> bands = new java.util.ArrayList<>();
        int start = -1;
        for (int i = 0; i < bandCount; i++) {
            boolean hasInk = ink[i] >= INK_RATIO;
            if (hasInk && start < 0) start = i;
            if (!hasInk && start >= 0) {
                bands.add(new int[]{start, i - 1});
                start = -1;
            }
        }
        if (start >= 0) bands.add(new int[]{start, bandCount - 1});
        if (bands.isEmpty()) return null;

        // 合并靠得太近的横带（题内的行间距不该被当成题间空白）
        int minGap = Math.max(2, (int) (pageH * MIN_GAP_RATIO));
        java.util.ArrayList<int[]> merged = new java.util.ArrayList<>();
        int[] cur = bands.get(0);
        for (int i = 1; i < bands.size(); i++) {
            int[] next = bands.get(i);
            if (next[0] - cur[1] - 1 <= minGap) {
                cur = new int[]{cur[0], next[1]};
            } else {
                merged.add(cur);
                cur = next;
            }
        }
        merged.add(cur);

        // 过滤太矮的
        int minBand = (int) (h * MIN_BAND_RATIO);
        java.util.ArrayList<int[]> solid = new java.util.ArrayList<>();
        for (int[] band : merged) {
            if (band[1] - band[0] + 1 >= minBand) solid.add(band);
        }
        if (solid.size() < 2) return null;   // 只有一块，本来就是单题

        // 选最靠近画面中心的那一带（用户通常正对着要拍的那道题）
        float centerY = (t + b) * 0.5f;
        int[] chosen = null;
        float best = Float.MAX_VALUE;
        for (int[] band : solid) {
            float mid = t + (band[0] + band[1]) * 0.5f;
            float d = Math.abs(mid - centerY);
            if (d < best) {
                best = d;
                chosen = band;
            }
        }
        if (chosen == null) return null;

        int chosenH = chosen[1] - chosen[0] + 1;
        if (chosenH > pageH * MAX_BAND_RATIO) return null;   // 切了等于没切

        float pad = Math.max(2f, chosenH * 0.06f);
        float top = t + chosen[0] - pad;
        float bottom = t + chosen[1] + pad;

        float nl = l / (float) w;
        float nr = r / (float) w;
        float nt = Math.max(0f, top / h);
        float nb = Math.min(1f, bottom / h);

        if (nb - nt < 0.03f) return null;
        return Quad.fromRect(nl, nt, nr, nb);
    }
}
