package com.jone.snapsolve;

/**
 * 行段投影：把一块题目区域切成"文字行"，供批改标记纵向落位用。
 *
 * 背景：模型能可靠地说出「这个空在这道题的第 3 行、该行第 2 个」（row/col），
 * 但说不出像素坐标（让它给坐标会照抄示例）。所以纵向位置由这里算出来——
 * 统计每一行的墨迹比例，连续有墨迹的行合并成一段，段中心就是那一行文字的基线位置。
 */
public final class InkRows {

    /**
     * 一行里墨迹占比超过它才算"有字"。
     * 不能太低：JPEG 压缩在行间空隙里留下的噪点约有 0.5%~1%，会把整块连成一片。
     */
    private static final float INK_RATIO = 0.02f;

    /** 比纸面底色暗多少算墨迹。区域内文字清晰，比整页检测更敏感一些。 */
    private static final int INK_DELTA = 52;

    /** 小于该高度（占区域高度比例）的行段视为噪点丢掉。 */
    private static final float MIN_BAND = 0.012f;

    /** 相邻行段间隙小于该值就合并（相当于行距合并，避免一行字被切成上下两半）。 */
    private static final float MERGE_GAP = 0.010f;

    private InkRows() {}

    /**
     * @return 每个行段中心的 y（归一化 0~1，相对传入区域），按从上到下排序
     */
    public static float[] bands(byte[] gray, int w, int h) {
        float[][] lines = lines(gray, w, h);
        float[] out = new float[lines.length];
        for (int i = 0; i < lines.length; i++) out[i] = lines[i][0];
        return out;
    }

    /**
     * 行段 + 该行文字的水平范围。
     *
     * @return 每段 {中心 y, 左边界 x, 右边界 x}，全部归一化 0~1（相对传入区域）
     */
    public static float[][] lines(byte[] gray, int w, int h) {
        if (gray == null || w < 8 || h < 8) return new float[0][];

        // 1) 估纸面底色：取 80 分位（纸面占绝大多数像素）
        int[] hist = new int[256];
        for (int i = 0; i < w * h; i++) hist[gray[i] & 0xFF]++;
        int acc = 0, paper = 200;
        int target = (int) (w * h * 0.80);
        for (int v = 255; v >= 0; v--) {
            acc += hist[v];
            if (acc >= target) {
                paper = v;
                break;
            }
        }
        int inkLevel = paper - INK_DELTA;

        // 2) 逐行墨迹比例
        boolean[] hasInk = new boolean[h];
        for (int y = 0; y < h; y++) {
            int row = y * w;
            int hits = 0;
            for (int x = 0; x < w; x++) {
                if ((gray[row + x] & 0xFF) < inkLevel) hits++;
            }
            hasInk[y] = hits >= Math.max(1, (int) (w * INK_RATIO));
        }

        // 3) 连续段 → 合并 → 过滤
        java.util.List<int[]> bands = new java.util.ArrayList<>();
        int start = -1;
        for (int y = 0; y <= h; y++) {
            boolean in = y < h && hasInk[y];
            if (in && start < 0) start = y;
            if (!in && start >= 0) {
                bands.add(new int[]{start, y - 1});
                start = -1;
            }
        }
        if (bands.isEmpty()) return new float[0][];

        int mergeGap = Math.max(1, (int) (h * MERGE_GAP));
        java.util.List<int[]> merged = new java.util.ArrayList<>();
        int[] cur = bands.get(0);
        for (int i = 1; i < bands.size(); i++) {
            int[] nx = bands.get(i);
            if (nx[0] - cur[1] - 1 <= mergeGap) {
                cur = new int[]{cur[0], nx[1]};
            } else {
                merged.add(cur);
                cur = nx;
            }
        }
        merged.add(cur);

        int minBand = Math.max(2, (int) (h * MIN_BAND));
        java.util.List<float[]> out = new java.util.ArrayList<>();
        for (int[] b : merged) {
            if (b[1] - b[0] + 1 < minBand) continue;

            // 该段内文字的左右边界：只看这一段的墨迹列
            int left = -1, right = -1;
            for (int x = 0; x < w; x++) {
                boolean ink = false;
                for (int y = b[0]; y <= b[1]; y++) {
                    if ((gray[y * w + x] & 0xFF) < inkLevel) {
                        ink = true;
                        break;
                    }
                }
                if (ink) {
                    if (left < 0) left = x;
                    right = x;
                }
            }
            if (left < 0) continue;

            out.add(new float[]{
                    (b[0] + b[1]) * 0.5f / h,
                    left / (float) w,
                    right / (float) w
            });
        }

        return out.toArray(new float[0][]);
    }

    /**
     * 取第 row 个行段（row 从 1 开始）。
     * 越界时按比例夹到有效范围——模型偶尔会把行数说多一行，夹住比丢弃好。
     */
    /** 取第 row 个行段（row 从 1 开始），越界时夹住。 */
    public static float[] pickLine(float[][] lines, int row) {
        if (lines.length == 0) return null;
        int idx = row - 1;
        if (idx < 0) idx = 0;
        if (idx >= lines.length) idx = lines.length - 1;
        return lines[idx];
    }

    public static float pick(float[] bands, int row) {
        if (bands.length == 0) return -1f;
        int idx = row - 1;
        if (idx < 0) idx = 0;
        if (idx >= bands.length) idx = bands.length - 1;
        return bands[idx];
    }
}
