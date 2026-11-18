package com.jone.snapsolve;

/**
 * 四边形时间稳定器。
 *
 * 1) 四个角点各自做 EMA 平滑（跟手但不抖）
 * 2) 角点跳变过大（换了目标）时直接吸附，避免"拖着框飞过去"
 * 3) 记录连续稳定帧数，供"稳定后自动拍照"使用
 */
public final class QuadTracker {

    private static final float ALPHA = 0.35f;
    private static final float STABLE_EPS = 0.012f;   // 角点最大位移阈值（归一化）
    private static final float SNAP_EPS = 0.25f;      // 超过此位移视为换目标，直接吸附
    private static final int MAX_MISS = 15;

    private Quad current;
    private int miss;
    private int stable;

    public synchronized Quad update(Quad detected) {
        if (detected == null) {
            miss++;
            stable = 0;
            if (miss > MAX_MISS) current = null;
            return current == null ? null : current.copy();
        }

        miss = 0;

        if (current == null) {
            current = detected.copy();
            stable = 0;
            return current.copy();
        }

        float jump = current.maxCornerMove(detected);
        Quad next;
        if (jump > SNAP_EPS) {
            next = detected.copy();
            stable = 0;
        } else {
            next = current.lerp(detected, ALPHA);
            float move = next.maxCornerMove(current);
            if (move < STABLE_EPS) stable++;
            else stable = 0;
        }

        current = next;
        return current.copy();
    }

    public synchronized int stableFrames() {
        return stable;
    }

    public synchronized Quad get() {
        return current == null ? null : current.copy();
    }

    public synchronized void reset() {
        current = null;
        miss = 0;
        stable = 0;
    }

    /** 拍完一张后调用：保留画面但清空稳定计数，避免立刻二次触发。 */
    public synchronized void pauseAuto() {
        stable = 0;
    }
}
