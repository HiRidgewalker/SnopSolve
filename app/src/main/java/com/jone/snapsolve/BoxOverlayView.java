package com.jone.snapsolve;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * 拍照后的多题标注层：每道题一个框 + 左上角编号。
 * 坐标按 fitCenter 规则映射，与 ImageView 的显示区域严格对齐。
 */
public class BoxOverlayView extends View {

    private final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private List<Quad> boxes = new ArrayList<>();
    private List<String> verdicts = new ArrayList<>();
    private List<List<Boolean>> marksList = new ArrayList<>();
    /** 每个标记的落点（归一化 0~1）。有它就直接按点画，没它就退回题内等分。 */
    private List<List<float[]>> pointList = new ArrayList<>();
    private int imgW, imgH;
    private int highlight = -1;
    private boolean gradingStyle;   // 批改模式：不画整题框，勾圈为主
    private final java.util.List<RectF> screenRects = new java.util.ArrayList<>();
    private float lastDx, lastDy, lastOx, lastOy;
    private OnBoxTapListener tapListener;

    /** 点中某一题（点框内或点标记都算）。 */
    public interface OnBoxTapListener {
        void onBoxTap(int index);
    }

    public void setOnBoxTapListener(OnBoxTapListener l) {
        tapListener = l;
    }

    private final Paint mark = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Path path = new android.graphics.Path();
    private final RectF oval = new RectF();
    private final Paint markBg = new Paint(Paint.ANTI_ALIAS_FLAG);

    private static final int COLOR_IDLE = Color.parseColor("#4DD0E1");
    private static final int COLOR_DONE = Color.parseColor("#4ADE80");
    private static final int COLOR_HL = Color.parseColor("#FFB020");
    private static final int COLOR_BAD = Color.parseColor("#FF4D4F");

    public BoxOverlayView(Context c) { this(c, null); }

    public BoxOverlayView(Context c, AttributeSet a) {
        super(c, a);
        frame.setStyle(Paint.Style.STROKE);
        frame.setStrokeWidth(dp(1.6f));

        badge.setStyle(Paint.Style.FILL);
        text.setColor(Color.parseColor("#101418"));
        text.setTextAlign(Paint.Align.CENTER);
        text.setFakeBoldText(true);
        text.setTextSize(dp(9f));

        mark.setStyle(Paint.Style.FILL);
        mark.setTextAlign(Paint.Align.CENTER);
        mark.setFakeBoldText(true);
        mark.setTextSize(dp(15f));
        mark.setColor(Color.WHITE);

        markBg.setStyle(Paint.Style.FILL);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    public void setImageSize(int w, int h) {
        imgW = w;
        imgH = h;
        invalidate();
    }

    public void setBoxes(List<Quad> list) {
        boxes = list == null ? new ArrayList<>() : list;
        verdicts = new ArrayList<>();
        invalidate();
    }

    /** 批改模式：给每个框附带判定（right / wrong / partial），框色和对错角标都跟着变。 */
    public void setBoxes(List<Quad> list, List<String> verdictList) {
        boxes = list == null ? new ArrayList<>() : list;
        verdicts = verdictList == null ? new ArrayList<>() : verdictList;
        marksList = new ArrayList<>();
        pointList = new ArrayList<>();
        invalidate();
    }

    /**
     * 逐空标注：marksList[i] 是第 i 题每个作答点的对错。
     * 有逐空数据时，勾和圈画在题目内部按空位等分的地方（模拟老师逐个批改），
     * 而不是整道题画一个。
     */
    public void setBoxes(List<Quad> list, List<String> verdictList,
                         List<List<Boolean>> marks) {
        setBoxes(list, verdictList, marks, null);
    }

    /**
     * @param points 每题每个标记的归一化落点 (x, y)；为空则按题内等分推算
     */
    public void setBoxes(List<Quad> list, List<String> verdictList,
                         List<List<Boolean>> marks, List<List<float[]>> points) {
        boxes = list == null ? new ArrayList<>() : list;
        verdicts = verdictList == null ? new ArrayList<>() : verdictList;
        marksList = marks == null ? new ArrayList<>() : marks;
        pointList = points == null ? new ArrayList<>() : points;
        gradingStyle = false;
        for (String v : verdicts) {
            if (v != null && !v.isEmpty()) { gradingStyle = true; break; }
        }
        invalidate();
    }

    public void setHighlight(int index) {
        highlight = index;
        invalidate();
    }

    /** 逐空用的小勾。 */
    private void drawSmallCheck(Canvas canvas, float cx, float cy) {
        mark.setStyle(Paint.Style.STROKE);
        mark.setStrokeWidth(dp(1.8f));
        mark.setStrokeCap(Paint.Cap.ROUND);
        mark.setStrokeJoin(Paint.Join.ROUND);
        mark.setColor(COLOR_DONE);
        path.reset();
        path.moveTo(cx - dp(3.6f), cy + dp(0.5f));
        path.lineTo(cx - dp(0.8f), cy + dp(4f));
        path.lineTo(cx + dp(4.6f), cy - dp(4.6f));
        canvas.drawPath(path, mark);
    }

    /**
     * 逐空用的小圈：跟勾同量级（约 17×14 dp），只圈住那一个答案字符。
     * 略微倾斜，接近手写圈的手感，不会像机器画的规整椭圆。
     */
    private void drawSmallRing(Canvas canvas, float cx, float cy) {
        mark.setStyle(Paint.Style.STROKE);
        mark.setStrokeWidth(dp(1.7f));
        mark.setStrokeCap(Paint.Cap.ROUND);
        mark.setColor(COLOR_BAD);

        float hw = dp(5.6f), hh = dp(4.8f);

        canvas.save();
        canvas.rotate(-14f, cx, cy);
        oval.set(cx - hw, cy - hh, cx + hw, cy + hh);
        canvas.drawOval(oval, mark);
        canvas.restore();
    }

    /** 逐空用的小三角：半对的提示。 */
    private void drawSmallHalf(Canvas canvas, float cx, float cy) {
        mark.setStyle(Paint.Style.STROKE);
        mark.setStrokeWidth(dp(1.7f));
        mark.setStrokeCap(Paint.Cap.ROUND);
        mark.setStrokeJoin(Paint.Join.ROUND);
        mark.setColor(COLOR_HL);
        path.reset();
        path.moveTo(cx, cy - dp(5f));
        path.lineTo(cx + dp(5f), cy + dp(4.2f));
        path.lineTo(cx - dp(5f), cy + dp(4.2f));
        path.close();
        canvas.drawPath(path, mark);
    }

    /** 先找离得近的标记点（批改模式没框，靠点更准），再退回框内判定。 */
    private int hitTest(float x, float y) {
        float best = dp(26);
        int bestIdx = -1;
        for (int i = 0; i < marksList.size(); i++) {
            List<float[]> pts = i < pointList.size() ? pointList.get(i) : null;
            if (pts == null) continue;
            for (float[] p : pts) {
                float px = lastOx + p[0] * lastDx;
                float py = lastOy + p[1] * lastDy;
                float d = (float) Math.hypot(px - x, py - y);
                if (d < best) {
                    best = d;
                    bestIdx = i;
                }
            }
        }
        if (bestIdx >= 0) return bestIdx;

        for (int i = 0; i < screenRects.size(); i++) {
            RectF r = screenRects.get(i);
            if (r.width() <= 0) continue;
            float top = r.top - dp(6), bottom = r.bottom + dp(6);
            if (x >= r.left && x <= r.right && y >= top && y <= bottom) return i;
        }
        return -1;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_UP && tapListener != null) {
            int idx = hitTest(e.getX(), e.getY());
            if (idx >= 0) {
                tapListener.onBoxTap(idx);
                return true;
            }
        }
        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (boxes.isEmpty() || imgW <= 0 || imgH <= 0) return;

        float vw = getWidth(), vh = getHeight();
        float scale = Math.min(vw / imgW, vh / imgH);
        float dw = imgW * scale, dh = imgH * scale;
        float ox = (vw - dw) * 0.5f, oy = (vh - dh) * 0.5f;
        lastDx = dw;
        lastDy = dh;
        lastOx = ox;
        lastOy = oy;

        while (screenRects.size() < boxes.size()) screenRects.add(new RectF());
        while (screenRects.size() > boxes.size()) screenRects.remove(screenRects.size() - 1);

        for (int i = 0; i < boxes.size(); i++) {
            Quad q = boxes.get(i);
            float[] bb = q.bbox();

            rect.set(ox + bb[0] * dw, oy + bb[1] * dh,
                    ox + bb[2] * dw, oy + bb[3] * dh);
            screenRects.get(i).set(rect);
            if (rect.width() < dp(12) || rect.height() < dp(6)) continue;

            String v = i < verdicts.size() ? verdicts.get(i) : "";
            int color = COLOR_IDLE;
            if ("right".equalsIgnoreCase(v)) color = COLOR_DONE;
            else if ("wrong".equalsIgnoreCase(v)) color = COLOR_BAD;
            else if ("partial".equalsIgnoreCase(v)) color = COLOR_HL;
            if (i == highlight) color = COLOR_HL;

            // 批改模式平时不画框：框只能覆盖题干行，会把选项行"切"成两截，
            // 看着像划掉了半道题。只有选中时才画一个高亮框，明确"你正在看这题"。
            if (!gradingStyle) {
                frame.setColor(color);
                canvas.drawRoundRect(rect, dp(3), dp(3), frame);
            } else if (i == highlight) {
                frame.setColor(COLOR_HL);
                frame.setStrokeWidth(dp(2.4f));
                canvas.drawRoundRect(rect, dp(4), dp(4), frame);
                frame.setStrokeWidth(dp(1.6f));
            }

            // 编号徽章：框左上角
            String label = String.valueOf(i + 1);
            float r = dp(8f);
            float cx = rect.left + r;
            float cy = rect.top + r;
            badge.setColor(color);
            canvas.drawCircle(cx, cy, r, badge);
            canvas.drawText(label, cx, cy + dp(3.2f), text);

            // 有逐空数据：在每个作答点的位置画勾/圈
            List<Boolean> marks = i < marksList.size() ? marksList.get(i) : null;
            if (marks != null && !marks.isEmpty()) {
                int n = marks.size();
                List<float[]> pts = i < pointList.size() ? pointList.get(i) : null;
                boolean hasPts = pts != null && pts.size() == n;
                for (int j = 0; j < n; j++) {
                    float mx, my;
                    if (hasPts) {
                        mx = ox + pts.get(j)[0] * dw;
                        my = oy + pts.get(j)[1] * dh;
                    } else {
                        mx = rect.left + rect.width() * (j + 0.5f) / n;
                        my = rect.centerY();
                    }
                    if (Boolean.TRUE.equals(marks.get(j))) {
                        drawSmallCheck(canvas, mx, my);
                    } else {
                        drawSmallRing(canvas, mx, my);
                    }
                }
                continue;
            }

            // 没有逐空数据时，退回整题一个标记。也用右下角小图标，
            // 不再画大椭圆——大圈会盖住整道题的字，看着像划掉了整题。
            float fx = rect.right - dp(16f), fy = rect.bottom - dp(14f);
            if ("right".equalsIgnoreCase(v)) {
                drawSmallCheck(canvas, fx, fy);
            } else if ("wrong".equalsIgnoreCase(v)) {
                drawSmallRing(canvas, fx, fy);
            } else if ("partial".equalsIgnoreCase(v)) {
                drawSmallHalf(canvas, fx, fy);
            }
        }
    }
}
