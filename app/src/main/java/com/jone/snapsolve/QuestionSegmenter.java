package com.jone.snapsolve;

import java.util.ArrayList;
import java.util.List;

/**
 * 页内切题：把整页切分成"一道题一块"，用于拍照后的多框标注与逐题解答。
 *
 * 纯投影分析，不需要模型：
 * 1. 用纸面底色做基准，逐行统计"墨迹占比"（比底色暗 INK_DELTA 灰阶以上）
 * 2. 连续有墨迹的行构成横带
 * 3. 小于题间距阈值的空白合并掉（保住题内行距）
 * 4. 每个横带再做列投影，得到左右边界
 *
 * 对标 leo 的分学科检测模型的输出：它也是给一页返回多个题目框，
 * 只是我们用几何判据而非学习特征。
 */
public final class QuestionSegmenter {

    /**
     * 一行中墨迹占比超过它才算"有字"。
     * 实拍照片的行间噪点约为 0.2%~0.4%，取 0.4% 能滤掉纸纹又不漏掉细笔画。
     */
    private static final float INK_RATIO = 0.004f;
    /**
     * 比纸面底色暗多少算墨迹。实测 45 会把浅灰阴影全算进去（整页 100% 行都有墨迹），
     * 80 能把纸纹、折痕、阴影挡在外面，只留真正的笔画。
     */
    private static final int INK_DELTA = 80;
    /**
     * 小于这个高度（相对纸面）的空白不切分。
     * 行距通常 1.5%~2.5%，题间距 5%~8%，取 3.5% 折中。
     */
    private static final float MIN_GAP_RATIO = 0.003f;
    /** 太矮的横带直接丢弃（噪声、装订线之类）。 */
    private static final float MIN_BAND_RATIO = 0.008f;
    /** 一列中有多少墨迹才算"有内容"，用于定左右边界。 */
    private static final float COL_INK_RATIO = 0.006f;
    /** 局部阈值至少要低于局部均值这么多，防止纯白块里的压缩噪点被当成字。 */
    private static final int MIN_CONTRAST = 22;
    /** 框四周留白（相对整页）。 */
    private static final float PAD_X = 0.012f;
    private static final float PAD_Y = 0.0015f;
    /** 一页最多返回多少道题，防止误检刷屏。 */
    private static final int MAX_QUESTIONS = 40;

    private QuestionSegmenter() {}

    /**
     * @param gray 摆正后的灰度图（宽 w 高 h）
     * @param page 整页四边形（归一化）；null 表示全图
     * @return 题目框列表（归一化，按从上到下排序）
     */
    public static List<Quad> segment(byte[] gray, int w, int h, Quad page) {
        List<Quad> out = new ArrayList<>();
        if (gray == null || w < 32 || h < 32) return out;

        float[] bb = page == null ? new float[]{0f, 0f, 1f, 1f} : page.bbox();
        int l = (int) (bb[0] * w), r = (int) (bb[2] * w);
        int t = (int) (bb[1] * h), b = (int) (bb[3] * h);
        l = Math.max(0, Math.min(w - 2, l));
        r = Math.max(l + 8, Math.min(w - 1, r));
        t = Math.max(0, Math.min(h - 2, t));
        b = Math.max(t + 8, Math.min(h - 1, b));
        if (r - l < 32 || b - t < 32) return out;

        int pageW = r - l, pageH = b - t;

        // 1) 分块局部二值化（Sauvola 思路）。
        // 手机拍的照片光照很不均匀——同一个"纸白"在亮处 210、暗处可能只有 160，
        // 用全局阈值或逐行阈值都会让暗侧整片被判成墨迹，最后整页连成一块（实测就是这样）。
        // 这里按 BLOCK×BLOCK 的格子各自估局部均值和标准差，再按局部阈值判墨迹。
        byte[] inkMask = new byte[pageW * pageH];
        final int block = 32;
        final float k = 0.34f, R = 128f;
        for (int by = 0; by < pageH; by += block) {
            int yEnd = Math.min(pageH, by + block);
            for (int bx = 0; bx < pageW; bx += block) {
                int xEnd = Math.min(pageW, bx + block);
                long sum = 0, sumSq = 0;
                int cnt = (xEnd - bx) * (yEnd - by);
                for (int y = by; y < yEnd; y++) {
                    int row = (t + y) * w + l + bx;
                    for (int x = bx; x < xEnd; x++) {
                        int v = gray[row + (x - bx)] & 0xFF;
                        sum += v;
                        sumSq += (long) v * v;
                    }
                }
                if (cnt == 0) continue;
                float mean = sum / (float) cnt;
                float var = sumSq / (float) cnt - mean * mean;
                float std = var > 0 ? (float) Math.sqrt(var) : 0f;

                float sauvola = mean * (1f - k * (1f - std / R));
                // 同时要求"明显比局部纸色暗"，避免纯白格里把压缩噪点当成字
                float th = Math.min(sauvola, mean - MIN_CONTRAST);

                for (int y = by; y < yEnd; y++) {
                    int row = (t + y) * w + l + bx;
                    int dst = y * pageW + bx;
                    for (int x = bx; x < xEnd; x++) {
                        if ((gray[row + (x - bx)] & 0xFF) < th) inkMask[dst + (x - bx)] = 1;
                    }
                }
            }
        }

        // 2) 行投影：每行墨迹像素占比
        float[] rowInk = new float[pageH];
        for (int y = 0; y < pageH; y++) {
            int row = y * pageW;
            int hits = 0;
            for (int x = 0; x < pageW; x++) hits += inkMask[row + x];
            rowInk[y] = hits / (float) pageW;
        }

        // 3) 连续墨迹带
        List<int[]> bands = new ArrayList<>();
        int start = -1;
        for (int i = 0; i < pageH; i++) {
            boolean hasInk = rowInk[i] >= INK_RATIO;
            if (hasInk && start < 0) start = i;
            if (!hasInk && start >= 0) {
                bands.add(new int[]{start, i - 1});
                start = -1;
            }
        }
        if (start >= 0) bands.add(new int[]{start, pageH - 1});
        if (bands.isEmpty()) return out;

        // 4) 合并题内行距造成的碎带
        int minGap = Math.max(2, (int) (pageH * MIN_GAP_RATIO));
        List<int[]> merged = new ArrayList<>();
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

        // 5) 每个横带做列投影，定左右边界
        int minBand = Math.max(6, (int) (pageH * MIN_BAND_RATIO));
        for (int[] band : merged) {
            int bh = band[1] - band[0] + 1;
            if (bh < minBand) continue;

            int yTop = t + band[0], yBot = t + band[1];

            int colMin = Math.max(1, (int) (bh * COL_INK_RATIO));
            int left = Integer.MAX_VALUE, right = -1;
            for (int x = l; x < r; x++) {
                int hits = 0;
                for (int y = band[0]; y <= band[1]; y++) {
                    hits += inkMask[y * pageW + (x - l)];
                }
                if (hits >= colMin) {
                    if (x < left) left = x;
                    if (x > right) right = x;
                }
            }
            if (right <= left) {   // 投影失败，用整幅宽度
                left = l;
                right = r - 1;
            }

            float nl = Math.max(0f, (left - pageW * PAD_X) / w);
            float nr = Math.min(1f, (right + pageW * PAD_X) / w);
            float nt = Math.max(0f, (yTop - pageH * PAD_Y) / h);
            float nb = Math.min(1f, (yBot + pageH * PAD_Y) / h);

            if (nr - nl < 0.05f || nb - nt < 0.01f) continue;

            out.add(Quad.fromRect(nl, nt, nr, nb));
            if (out.size() >= MAX_QUESTIONS) break;
        }

        return out;
    }

    /**
     * 从直方图取 80 分位亮度，作为"这一行的纸色"参考。
     * 纸面在行内占绝大多数像素，80 分位足以跳过文字与线条，又不会被小块阴影带偏。
     */
    private static int percentile80(int[] hist, int total) {
        int target = Math.max(1, (int) (total * 0.80f));
        int acc = 0;
        for (int v = 255; v >= 0; v--) {
            acc += hist[v];
            if (acc >= target) return v;
        }
        return 255;
    }

    /**
     * 实时预览用：从一页的所有题目框里，挑最靠近画面中心的那个。
     * 用户通常正对着要拍的那道题。
     */
    public static Quad pickCenter(List<Quad> boxes) {
        if (boxes == null || boxes.isEmpty()) return null;
        Quad best = null;
        float bestDist = Float.MAX_VALUE;
        for (Quad q : boxes) {
            float[] bb = q.bbox();
            float cy = (bb[1] + bb[3]) * 0.5f;
            float d = Math.abs(cy - 0.5f);
            if (d < bestDist) {
                bestDist = d;
                best = q;
            }
        }
        return best;
    }
}
