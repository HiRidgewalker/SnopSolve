package com.jone.snapsolve;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * 端侧四边形检测：每帧在本地算出纸面/题目的四个角点。
 *
 * 两条路径：
 *   A. 亮面分割（主路径）——Otsu 二值化取亮区 → 最大连通域 → 凸包 → 从凸包拟合四角
 *      → 得到带透视的四边形，拍照时可直接做单应矫正。
 *   B. 文字密度（兜底）——题干占满画面、或纸面与桌面亮度接近时，
 *      退化成"文字最密区域"的轴对齐矩形（四角即其四角），不会没框。
 *
 * 单帧成本约 1~2ms（240px 降采样），不联网。
 */
public final class QuadDetector {

    private static final int TARGET_LONG_SIDE = 240;
    private static final int CELL = 8;
    private static final int GRAD_THRESHOLD = 25;
    private static final float MIN_DENSITY = 0.06f;
    private static final int MIN_CELLS = 6;

    private QuadDetector() {}


    public static Quad detect(ByteBuffer yBuffer, int width, int height,
                              int rowStride, int pixelStride, int rotationDegrees) {
        return detect(yBuffer, width, height, rowStride, pixelStride, rotationDegrees, true);
    }

    /**
     * @param splitQuestions 是否在多题页面上把框收紧到单题
     */
    public static Quad detect(ByteBuffer yBuffer, int width, int height,
                              int rowStride, int pixelStride, int rotationDegrees,
                              boolean splitQuestions) {
        if (yBuffer == null || width < 32 || height < 32) return null;

        int step = Math.max(1, Math.max(width, height) / TARGET_LONG_SIDE);
        int sw = width / step;
        int sh = height / step;
        if (sw < 32 || sh < 32) return null;

        // 1) 原始方向的降采样
        byte[] raw = new byte[sw * sh];
        int limit = yBuffer.limit();
        for (int y = 0; y < sh; y++) {
            int srcRow = y * step * rowStride;
            int dstRow = y * sw;
            for (int x = 0; x < sw; x++) {
                int idx = srcRow + (x * step) * pixelStride;
                if (idx < limit) raw[dstRow + x] = yBuffer.get(idx);
            }
        }

        // 2) 转到"摆正"坐标系再算，后续全部在正立图上进行
        boolean swap = (rotationDegrees == 90 || rotationDegrees == 270);
        int uw = swap ? sh : sw;
        int uh = swap ? sw : sh;
        byte[] up = new byte[uw * uh];
        for (int y = 0; y < uh; y++) {
            for (int x = 0; x < uw; x++) {
                int rx, ry;
                switch (((rotationDegrees % 360) + 360) % 360) {
                    case 90:
                        rx = y;
                        ry = sh - 1 - x;
                        break;
                    case 180:
                        rx = sw - 1 - x;
                        ry = sh - 1 - y;
                        break;
                    case 270:
                        rx = sw - 1 - y;
                        ry = x;
                        break;
                    case 0:
                    default:
                        rx = x;
                        ry = y;
                        break;
                }
                if (rx >= 0 && rx < sw && ry >= 0 && ry < sh) {
                    up[y * uw + x] = raw[ry * sw + rx];
                }
            }
        }

        // 3) 先找纸面（带透视的四角），失败则退化成内容矩形
        Quad q = detectPageQuad(up, uw, uh);
        if (q == null) q = contentRect(up, uw, uh);
        if (q == null) return null;

        float[] nx = new float[4], ny = new float[4];
        for (int i = 0; i < 4; i++) {
            nx[i] = q.x[i] / uw;
            ny[i] = q.y[i] / uh;
        }
        Quad page = Quad.order(nx, ny);

        // 4) 一页多题时，收紧到最靠近画面中心的那一题
        if (splitQuestions) {
            Quad one = QuestionSplitter.refine(up, uw, uh, page);
            if (one != null) return one;
        }
        return page;
    }

    // ---------------------------------------------------------------- 路径 A

    private static Quad detectPageQuad(byte[] gray, int w, int h) {
        int threshold = Math.max(96, otsu(gray));

        boolean[] mask = new boolean[w * h];
        int bright = 0;
        for (int i = 0; i < mask.length; i++) {
            if ((gray[i] & 0xFF) > threshold) {
                mask[i] = true;
                bright++;
            }
        }

        float ratio = bright / (float) (w * h);
        if (ratio < 0.10f || ratio > 0.92f) return null;   // 亮区太小/满屏都亮 → 交给兜底

        boolean[] seen = new boolean[w * h];
        ArrayDeque<Integer> stack = new ArrayDeque<>();

        int bestCount = 0;
        List<int[]> bestBoundary = null;

        for (int i = 0; i < mask.length; i++) {
            if (!mask[i] || seen[i]) continue;

            seen[i] = true;
            stack.clear();
            stack.push(i);
            int count = 0;
            List<int[]> boundary = new ArrayList<>();

            while (!stack.isEmpty()) {
                int cur = stack.pop();
                count++;
                int cx = cur % w, cy = cur / w;

                boolean edge = cx == 0 || cy == 0 || cx == w - 1 || cy == h - 1;
                if (edge
                        || !mask[cur - 1] || !mask[cur + 1]
                        || !mask[cur - w] || !mask[cur + w]) {
                    boundary.add(new int[]{cx, cy});
                }

                if (cx > 0 && mask[cur - 1] && !seen[cur - 1]) { seen[cur - 1] = true; stack.push(cur - 1); }
                if (cx < w - 1 && mask[cur + 1] && !seen[cur + 1]) { seen[cur + 1] = true; stack.push(cur + 1); }
                if (cy > 0 && mask[cur - w] && !seen[cur - w]) { seen[cur - w] = true; stack.push(cur - w); }
                if (cy < h - 1 && mask[cur + w] && !seen[cur + w]) { seen[cur + w] = true; stack.push(cur + w); }
            }

            if (count > bestCount) {
                bestCount = count;
                bestBoundary = boundary;
            }
        }

        if (bestBoundary == null || bestCount < (int) (0.06f * w * h)) return null;

        List<int[]> hull = hull(bestBoundary);
        if (hull.size() < 4) return null;

        float[] xs = new float[4], ys = new float[4];
        if (hull.size() == 4) {
            // 正放的矩形/平行四边形，凸包恰好 4 个点，直接用
            for (int i = 0; i < 4; i++) {
                xs[i] = hull.get(i)[0];
                ys[i] = hull.get(i)[1];
            }
        } else if (!quadFromHull(hull, xs, ys)) {
            return null;
        }

        Quad q = Quad.order(xs, ys);
        return valid(q, w, h) ? q : null;
    }

    /** 从凸包里挑出四个角：最远点对做对角线，两侧最远点补齐。 */
    private static boolean quadFromHull(List<int[]> hull, float[] xs, float[] ys) {
        int n = hull.size();

        float cx = 0, cy = 0;
        for (int[] p : hull) { cx += p[0]; cy += p[1]; }
        cx /= n; cy /= n;

        int ia = 0;
        float best = -1;
        for (int i = 0; i < n; i++) {
            float d = sq(hull.get(i)[0] - cx, hull.get(i)[1] - cy);
            if (d > best) { best = d; ia = i; }
        }

        int ic = 0;
        best = -1;
        for (int i = 0; i < n; i++) {
            float d = sq(hull.get(i)[0] - hull.get(ia)[0], hull.get(i)[1] - hull.get(ia)[1]);
            if (d > best) { best = d; ic = i; }
        }
        if (ia == ic) return false;

        float ax = hull.get(ia)[0], ay = hull.get(ia)[1];
        float bx = hull.get(ic)[0], by = hull.get(ic)[1];
        float dx = bx - ax, dy = by - ay;
        float len = (float) Math.hypot(dx, dy);
        if (len < 8f) return false;

        int ib = -1, id = -1;
        float maxPos = 0, maxNeg = 0;
        for (int i = 0; i < n; i++) {
            if (i == ia || i == ic) continue;
            float px = hull.get(i)[0] - ax, py = hull.get(i)[1] - ay;
            float cross = (dx * py - dy * px) / len;   // 到对角线 AC 的有符号距离
            if (cross > maxPos) { maxPos = cross; ib = i; }
            if (cross < maxNeg) { maxNeg = cross; id = i; }
        }
        if (ib < 0 || id < 0) return false;
        if (maxPos < 4f || -maxNeg < 4f) return false;   // 退化：几乎共线

        xs[0] = ax;          ys[0] = ay;
        xs[1] = hull.get(ib)[0]; ys[1] = hull.get(ib)[1];
        xs[2] = bx;          ys[2] = by;
        xs[3] = hull.get(id)[0]; ys[3] = hull.get(id)[1];
        return true;
    }

    /** Andrew monotone chain 凸包。 */
    private static List<int[]> hull(List<int[]> pts) {
        List<int[]> p = new ArrayList<>(pts);
        p.sort((a, b) -> a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(a[1], b[1]));

        List<int[]> lower = new ArrayList<>();
        for (int[] pt : p) {
            while (lower.size() >= 2 && cross(lower.get(lower.size() - 2), lower.get(lower.size() - 1), pt) <= 0)
                lower.remove(lower.size() - 1);
            lower.add(pt);
        }
        List<int[]> upper = new ArrayList<>();
        for (int i = p.size() - 1; i >= 0; i--) {
            int[] pt = p.get(i);
            while (upper.size() >= 2 && cross(upper.get(upper.size() - 2), upper.get(upper.size() - 1), pt) <= 0)
                upper.remove(upper.size() - 1);
            upper.add(pt);
        }
        lower.remove(lower.size() - 1);
        upper.remove(upper.size() - 1);
        lower.addAll(upper);
        return lower;
    }

    private static float cross(int[] o, int[] a, int[] b) {
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
    }

    // ---------------------------------------------------------------- 路径 B

    public static Quad contentRect(byte[] gray, int w, int h) {
        int cw = Math.max(1, w / CELL);
        int ch = Math.max(1, h / CELL);
        float[] density = new float[cw * ch];
        int[] counts = new int[cw * ch];
        int[] pixels = new int[cw * ch];

        for (int y = 1; y < h - 1; y++) {
            int cy = Math.min(ch - 1, y / CELL);
            for (int x = 1; x < w - 1; x++) {
                int i = y * w + x;
                int gx = Math.abs((gray[i + 1] & 0xFF) - (gray[i - 1] & 0xFF));
                int gy = Math.abs((gray[i + w] & 0xFF) - (gray[i - w] & 0xFF));
                int ci = cy * cw + Math.min(cw - 1, x / CELL);
                pixels[ci]++;
                if (gx + gy > GRAD_THRESHOLD) counts[ci]++;
            }
        }

        float sum = 0f;
        int valid = 0;
        for (int i = 0; i < density.length; i++) {
            if (pixels[i] > 0) {
                density[i] = counts[i] / (float) pixels[i];
                sum += density[i];
                valid++;
            }
        }
        if (valid == 0) return null;

        float dt = Math.max(MIN_DENSITY, (sum / valid) * 1.6f);

        boolean[] on = new boolean[cw * ch];
        for (int i = 0; i < on.length; i++) on[i] = density[i] >= dt;

        boolean[] seen = new boolean[cw * ch];
        int bestCount = 0, bl = 0, bt = 0, br = 0, bb = 0;

        ArrayDeque<Integer> q = new ArrayDeque<>();
        for (int i = 0; i < on.length; i++) {
            if (!on[i] || seen[i]) continue;
            seen[i] = true;
            q.clear();
            q.add(i);
            int n = 0, minX = cw, minY = ch, maxX = -1, maxY = -1;

            while (!q.isEmpty()) {
                int cur = q.poll();
                int cx = cur % cw, cy = cur / cw;
                n++;
                if (cx < minX) minX = cx;
                if (cx > maxX) maxX = cx;
                if (cy < minY) minY = cy;
                if (cy > maxY) maxY = cy;

                if (cx > 0 && on[cur - 1] && !seen[cur - 1]) { seen[cur - 1] = true; q.add(cur - 1); }
                if (cx < cw - 1 && on[cur + 1] && !seen[cur + 1]) { seen[cur + 1] = true; q.add(cur + 1); }
                if (cy > 0 && on[cur - cw] && !seen[cur - cw]) { seen[cur - cw] = true; q.add(cur - cw); }
                if (cy < ch - 1 && on[cur + cw] && !seen[cur + cw]) { seen[cur + cw] = true; q.add(cur + cw); }
            }

            if (n > bestCount) {
                bestCount = n; bl = minX; bt = minY; br = maxX; bb = maxY;
            }
        }

        if (bestCount < MIN_CELLS) return null;

        float l = Math.max(0, (bl - 1) * CELL);
        float t = Math.max(0, (bt - 1) * CELL);
        float r = Math.min(w, (br + 2) * CELL);
        float b = Math.min(h, (bb + 2) * CELL);

        Quad q2 = Quad.fromRect(l, t, r, b);
        float area = (r - l) * (b - t) / (float) (w * h);
        return (area < 0.02f || area > 0.995f) ? null : q2;
    }

    // ---------------------------------------------------------------- 校验 / 工具

    /** 面积、边长、凸性、内角四项校验，任何一项离谱就当没检测到。 */
    private static boolean valid(Quad q, int w, int h) {
        float areaRatio = q.area() / (float) (w * h);
        if (areaRatio < 0.05f || areaRatio > 0.96f) return false;

        float minEdge = Math.min(w, h) * 0.06f;
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) & 3;
            float d = (float) Math.hypot(q.x[j] - q.x[i], q.y[j] - q.y[i]);
            if (d < minEdge) return false;
        }

        if (!q.isConvex()) return false;

        for (int i = 0; i < 4; i++) {
            int p = (i + 3) & 3, n = (i + 1) & 3;
            float ax = q.x[p] - q.x[i], ay = q.y[p] - q.y[i];
            float bx = q.x[n] - q.x[i], by = q.y[n] - q.y[i];
            float na = (float) Math.hypot(ax, ay), nb = (float) Math.hypot(bx, by);
            if (na < 1e-3f || nb < 1e-3f) return false;
            float cos = (ax * bx + ay * by) / (na * nb);
            cos = Math.max(-1f, Math.min(1f, cos));
            float deg = (float) Math.toDegrees(Math.acos(cos));
            if (deg < 30f || deg > 150f) return false;
        }
        return true;
    }

    private static int otsu(byte[] gray) {
        int[] hist = new int[256];
        for (byte b : gray) hist[b & 0xFF]++;

        int total = gray.length;
        float sumAll = 0;
        for (int i = 0; i < 256; i++) sumAll += i * hist[i];

        float sumB = 0;
        int wB = 0;
        float best = -1;
        int threshold = 127;

        for (int t = 0; t < 256; t++) {
            wB += hist[t];
            if (wB == 0) continue;
            int wF = total - wB;
            if (wF == 0) break;

            sumB += t * hist[t];
            float mB = sumB / wB;
            float mF = (sumAll - sumB) / wF;
            float between = (float) wB * wF * (mB - mF) * (mB - mF);

            if (between > best) { best = between; threshold = t; }
        }
        return threshold;
    }

    private static float sq(float a, float b) {
        return a * a + b * b;
    }

}
