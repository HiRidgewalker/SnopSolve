package com.jone.snapsolve;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 拍照后的页面。
 *
 * 拍完立刻用本地投影切题画框（约 80ms），同时发起整页流式请求：
 * 模型一行一题地吐，解析出一题就追加一张卡片，第一题约 1.5 秒就能看到。
 * 这等价于原版"讲解中 → 讲解"的渐进效果，不必等全部生成完。
 *
 * 本地切题只负责视觉框，不参与答案（它在密集排版上会切不准，逐题裁切会切丢题干）。
 */
public class ResultActivity extends AppCompatActivity {

    public static final String EXTRA_PATH = "path";
    public static final String EXTRA_SIZE = "size";
    public static final String EXTRA_KEY = "key";
    public static final String EXTRA_SPLIT = "split";
    public static final String EXTRA_GRADING = "grading";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<TextView> cardBodies = new ArrayList<>();
    private final List<View> cardViews = new ArrayList<>();
    private final List<TextView> cardExplain = new ArrayList<>();
    private final List<String> cardNos = new ArrayList<>();
    private final List<String> cardStudents = new ArrayList<>();
    private final List<String> cardAnswers = new ArrayList<>();
    private final java.util.Map<Integer, String> explainCache = new java.util.HashMap<>();
    private final java.util.Set<Integer> explainLoading = new java.util.HashSet<>();
    private java.util.concurrent.ExecutorService explainPool;
    private String imagePath;
    private final List<TextView> cardTitles = new ArrayList<>();
    private final List<String> answers = new ArrayList<>();
    private final List<int[]> spans = new ArrayList<>();   // {y1, y2}
    private final List<String> verdicts = new ArrayList<>(); // right / wrong / partial
    private final List<List<Boolean>> marksList = new ArrayList<>(); // 每题的逐空对错
    private final List<List<float[]>> pointList = new ArrayList<>(); // 每题每个标记的归一化落点
    private boolean grading;
    private int rightCount, wrongCount, partialCount;

    private Bitmap page;
    private List<Quad> boxes = new ArrayList<>();
    private ImageView image;
    private BoxOverlayView overlay;
    private LinearLayout cards;
    private ScrollView scrollView;
    private View imageWrap;
    private TextView badge;
    private String path;

    private int uploadBytes;
    private ExecutorService pool;
    private int solvingRound;
    private long t0;              // 进入结果页开始解题的时刻
    private long firstBoxMs = -1; // 第一个框出现的耗时
    private long totalMs = -1;    // 全部完成的耗时

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_result);

        path = getIntent().getStringExtra(EXTRA_PATH);
        uploadBytes = getIntent().getIntExtra(EXTRA_SIZE, -1);
        boolean split = getIntent().getBooleanExtra(EXTRA_SPLIT, true);
        grading = getIntent().getBooleanExtra(EXTRA_GRADING, Prefs.grading(this))
                || "grading".equals(getIntent().getStringExtra("mode"));

        image = findViewById(R.id.image);
        imageWrap = findViewById(R.id.image_wrap);

        overlay = findViewById(R.id.boxes);
        explainPool = java.util.concurrent.Executors.newFixedThreadPool(2);
        overlay.setOnBoxTapListener(idx -> {
            if (idx >= 0 && idx < cardViews.size()) {
                scrollToCard(idx);
                highlightCard(idx);
            }
        });
        cards = findViewById(R.id.cards);
        scrollView = findViewById(R.id.scroll);
        badge = findViewById(R.id.badge);
        Button retry = findViewById(R.id.retry);
        Button copyAll = findViewById(R.id.copy_all);
        Button useOriginal = findViewById(R.id.use_original);
        TextView back = findViewById(R.id.back);

        back.setOnClickListener(v -> finish());
        retry.setOnClickListener(v -> {
            startActivity(new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
            finish();
        });
        copyAll.setOnClickListener(v -> copyAll());
        useOriginal.setOnClickListener(v -> startSolve());

        if (path == null) { finish(); return; }
        imagePath = path;
        page = BitmapFactory.decodeFile(path);
        fitImageArea();
        if (page == null) { finish(); return; }

        image.setImageBitmap(page);
        overlay.setImageSize(page.getWidth(), page.getHeight());
        badge.setText(R.string.solving);

        pool = Executors.newSingleThreadExecutor();
        // 不再画本地切题的框：实测它把"行"当成"题"（一道三行的题会出三个框，
        // 页眉和姓名栏也会各出一个），编号与题目完全对不上，画出来只会误导。
        // 题目编号以模型返回的题号为准，参考图只显示整页原图。
        startSolve();
    }

    // ---------------------------------------------------------------- 本地切题（只为画框）

    // ---------------------------------------------------------------- 流式求解

    private void startSolve() {
        final int round = ++solvingRound;
        t0 = android.os.SystemClock.elapsedRealtime();
        firstBoxMs = -1;
        totalMs = -1;
        cards.removeAllViews();
        cardBodies.clear();
        cardViews.clear();
        cardExplain.clear();
        cardNos.clear();
        cardStudents.clear();
        cardAnswers.clear();
        explainCache.clear();
        explainLoading.clear();
        cardTitles.clear();
        answers.clear();
        spans.clear();
        verdicts.clear();
        marksList.clear();
        pointList.clear();
        rightCount = wrongCount = partialCount = 0;
        overlay.setBoxes(new ArrayList<>());
        ((LinearLayout) findViewById(R.id.nav)).removeAllViews();
        badge.setText(R.string.solving);

        if (pool == null) pool = Executors.newSingleThreadExecutor();
        pool.execute(() -> {
            try {
                VisionApi.solvePageStream(this, path, grading, new VisionApi.StreamListener() {
                    @Override
                    public void onItem(VisionApi.Item item) {
                        main.post(() -> {
                            if (round != solvingRound) return;
                            appendCard(item);
                        });
                    }

                    @Override
                    public void onDone() {
                        main.post(() -> {
                            if (round != solvingRound) return;
                            finishSolve();
                        });
                    }
                });
            } catch (Throwable t) {
                main.post(() -> {
                    if (round != solvingRound) return;
                    addCard(getString(R.string.whole_page),
                            getString(R.string.failed_prefix) + t.getMessage(), false);
                    badge.setText(R.string.failed_prefix);
                });
            }
        });
    }

    /** 流式过程中来一题就追加一张卡片。 */
    private void appendCard(VisionApi.Item item) {
        // 用顺序号，不用卷面题号：卷面多个大题都有 1/2/3，直接取数字会重号
        int n = cardTitles.size() + 1;
        String title = getString(R.string.question_n, n);
        String label = item.no == null ? "" : item.no.trim();
        if (!label.isEmpty() && !label.equals(String.valueOf(n))) {
            title = title + " · " + label;
        }
        String body = item.answer;
        boolean ok = true;

        if (grading) {
            if (item.isRight()) {
                title = title + "  \u2713";
                rightCount++;
            } else if (item.isWrong()) {
                title = title + "  \u2717";
                wrongCount++;
                ok = false;
            } else if (item.isPartial()) {
                title = title + "  \u25B3";
                partialCount++;
            }
            StringBuilder sb = new StringBuilder();
            // 多空题：逐个标出每个空的对错，和图上画的勾圈对应
            if (item.marks != null && item.marks.size() > 1) {
                StringBuilder mk = new StringBuilder();
                for (Boolean b : item.marks) {
                    mk.append(Boolean.TRUE.equals(b) ? "\u2713" : "\u2717").append(' ');
                }
                sb.append(getString(R.string.blank_marks, mk.toString().trim())).append('\n');
            }
            if (!item.student.isEmpty()) {
                sb.append(getString(R.string.student_answer, item.student)).append('\n');
            }
            // 答对了就不重复显示正确答案，只留学生写的；答错/半对才给标准答案和错因
            if (!item.isRight() && !item.answer.isEmpty()) {
                sb.append(getString(R.string.correct_answer, item.answer));
            }
            body = sb.toString().trim();
            if (body.isEmpty()) body = item.answer;
        }

        cardNos.add(item.no == null ? String.valueOf(n) : item.no);
        cardStudents.add(item.student);
        cardAnswers.add(item.answer);

        addCard(title, body, ok);
        answers.add(body);
        spans.add(new int[]{item.y1, item.y2});
        verdicts.add(item.verdict);
        marksList.add(item.marks);
        pointList.add(computePoints(item));

        // 一题答完就立刻标一题：框数随答案递增，用户能看见它一题题长出来
        if (firstBoxMs < 0) firstBoxMs = android.os.SystemClock.elapsedRealtime() - t0;
        drawBoxesIncremental();

        buildNav(cardTitles.size());
        badge.setText(getString(R.string.streaming_badge,
                cardTitles.size(), firstBoxMs / 1000f));
    }

    private void finishSolve() {
        totalMs = android.os.SystemClock.elapsedRealtime() - t0;
        drawBoxesIncremental();
        if (cardTitles.isEmpty()) {
            addCard(getString(R.string.whole_page), getString(R.string.empty_answer), false);
            badge.setText(R.string.empty_answer);
            return;
        }
        if (grading) {
            badge.setText(getString(R.string.grading_done,
                    rightCount, wrongCount + partialCount,
                    Math.max(0, firstBoxMs) / 1000f, totalMs / 1000f));
        } else {
            badge.setText(getString(R.string.done_badge,
                    cardTitles.size(),
                    Math.max(0, firstBoxMs) / 1000f,
                    totalMs / 1000f));
        }
    }

    /**
     * 解题完成后，用模型给出的纵向位置画框。
     *
     * 为什么这个比本地切题可靠：框的数量天然等于题数、顺序天然对齐题号，
     * 不存在"18 段对 15 题"的错位问题。缺点是精度到"行"级，不是像素级贴合。
     */
    private void drawBoxesIncremental() {
        int n = Math.min(spans.size(), cardTitles.size());
        if (n == 0) { overlay.setBoxes(new ArrayList<>()); return; }

        // 模型可能按 0-100 或 0-1000 给刻度，用最大值判断
        int maxY = 0;
        for (int i = 0; i < n; i++) {
            int[] sp = spans.get(i);
            if (sp[1] > maxY) maxY = sp[1];
        }
        if (maxY <= 0) { overlay.setBoxes(new ArrayList<>()); return; }
        float scale = maxY > 150 ? 1000f : 100f;

        // 只要有一题缺位置就整体不画：漏一个框会让后面所有编号错位
        for (int i = 0; i < n; i++) {
            int[] sp = spans.get(i);
            if (sp[0] < 0 || sp[1] <= sp[0]) {
                overlay.setBoxes(new ArrayList<>());
                return;
            }
        }

        List<Quad> qs = new ArrayList<>();
        List<String> vs = new ArrayList<>();
        List<List<Boolean>> mks = new ArrayList<>();
        List<List<float[]>> pl = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int[] sp = spans.get(i);
            if (sp[0] < 0 || sp[1] <= sp[0]) continue;
            float top = sp[0] / scale;
            float bottom = sp[1] / scale;
            if (bottom <= top) continue;
            top = Math.max(0f, Math.min(1f, top));
            bottom = Math.max(0f, Math.min(1f, bottom));
            qs.add(Quad.fromRect(0.015f, top, 0.985f, bottom));
            vs.add(i < verdicts.size() ? verdicts.get(i) : "");
            mks.add(i < marksList.size() ? marksList.get(i) : new ArrayList<>());
            pl.add(i < pointList.size() ? pointList.get(i) : null);
        }
        overlay.setBoxes(qs, vs, mks, pl);
    }

    /**
     * 把模型给的 row/col 变成实际坐标。
     *
     * 纵向：先在题目区域里跑一次行段投影（InkRows），拿到真实的文字行位置，
     * 再按 row 取对应那一段——这样多行题的勾就不会挤在题目中线了。
     * 模型偶尔把行数说多一行（实测第 1 题说 4 行、实际 3 行），
     * 所以当最大行号超过实际行段数时整体平移回来。
     *
     * 横向：同一行内按 col 等分，col 的跨度用该行实际有几个作答点。
     */
    private List<float[]> computePoints(VisionApi.Item item) {
        List<float[]> out = new ArrayList<>();
        int n = item.marks.size();
        if (n == 0) return out;

        for (int j = 0; j < n; j++) {
            int[] pt = j < item.pts.size() ? item.pts.get(j) : null;
            if (pt != null && pt[0] >= 0 && pt[1] >= 0) {
                out.add(new float[]{clamp01(pt[1] / 1000f), clamp01(pt[0] / 1000f)});
            } else {
                // 没有坐标时退回：题目框等分
                float scale = item.y2 > 150 ? 1000f : 100f;
                float top = clamp01(item.y1 / scale), bottom = clamp01(item.y2 / scale);
                if (bottom <= top) bottom = Math.min(1f, top + 0.02f);
                out.add(new float[]{
                        0.015f + 0.97f * (j + 0.5f) / n,
                        (top + bottom) * 0.5f});
            }
        }
        return out;
    }

    /** 采样题目区域的灰度图；长边压到 1200，够分辨行又能跑得快。 */
    private byte[] sampleGray(float top, float bottom, int[] sizeOut) {
        int W = page.getWidth(), H = page.getHeight();
        int x1 = (int) (W * 0.015f), x2 = (int) (W * 0.985f);
        int y1 = (int) (H * top), y2 = (int) (H * bottom);
        int pw = x2 - x1, ph = y2 - y1;
        if (pw < 8 || ph < 6) return null;

        float sc = Math.min(1f, 1200f / Math.max(pw, ph));
        int sw = Math.max(8, (int) (pw * sc)), sh = Math.max(6, (int) (ph * sc));

        byte[] g = new byte[sw * sh];
        int[] line = new int[pw];
        for (int y = 0; y < sh; y++) {
            int py = Math.min(H - 1, y1 + y * ph / sh);
            try {
                page.getPixels(line, 0, pw, x1, py, pw, 1);
            } catch (Exception e) {
                return null;
            }
            for (int x = 0; x < sw; x++) {
                int rgb = line[Math.min(pw - 1, x * pw / sw)];
                int r = (rgb >> 16) & 0xFF, gg = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                g[y * sw + x] = (byte) ((299 * r + 587 * gg + 114 * b) / 1000);
            }
        }
        sizeOut[0] = sw;
        sizeOut[1] = sh;
        return g;
    }

    private static float clamp01(float v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    /**
     * 图片区高度按图片比例算，保证整页完整显示。
     *
     * 之前固定 340dp + adjustViewBounds，竖版卷子会被压扁裁掉底部——实测最后两题
     * 在容器外，模型给了坐标却画不出来。现在：按宽度撑满所需高度，超过屏高 42% 才封顶，
     * fitCenter 会自己留黑边，不会再裁。
     */
    private void fitImageArea() {
        if (page == null || imageWrap == null) return;
        int vw = getResources().getDisplayMetrics().widthPixels;
        int hByWidth = (int) ((float) vw * page.getHeight() / page.getWidth());
        int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.42f);
        int h = Math.min(hByWidth, Math.max(dp(220), maxH));
        imageWrap.getLayoutParams().height = h;
        imageWrap.requestLayout();
    }

    // ---------------------------------------------------------------- UI

    private void addCard(String title, String body, boolean ok) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        int p = dp(12);
        card.setPadding(p, dp(10), p, dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        card.setLayoutParams(lp);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(ok ? Color.parseColor("#4DD0E1") : Color.parseColor("#FF6B6B"));
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setTextSize(13);

        TextView b = new TextView(this);
        b.setText(body);
        b.setTextColor(Color.parseColor("#E6EAF0"));
        b.setTextSize(14);
        b.setLineSpacing(dp(4), 1f);
        b.setPadding(0, dp(6), 0, 0);
        b.setTextIsSelectable(true);

        TextView ex = new TextView(this);
        ex.setText(getString(R.string.explain_hint));
        ex.setTextColor(Color.parseColor("#8A94A6"));
        ex.setTextSize(13);
        ex.setLineSpacing(dp(4), 1f);
        ex.setPadding(0, dp(8), 0, 0);
        ex.setTextIsSelectable(true);

        card.addView(t);
        card.addView(b);
        card.addView(ex);

        final int index = cardTitles.size();
        card.setOnClickListener(v -> {
            overlay.setHighlight(index);
            highlightCard(index);
            loadExplain(index);
        });

        cards.addView(card);
        cardTitles.add(t);
        cardBodies.add(b);
        cardViews.add(card);
        cardExplain.add(ex);
    }

    /** 高亮当前卡片，其他恢复默认底色。 */
    private void highlightCard(int index) {
        for (int i = 0; i < cardViews.size(); i++) {
            cardViews.get(i).setBackgroundResource(
                    i == index ? R.drawable.bg_card_active : R.drawable.bg_card);
        }
    }

    /**
     * 懒加载单题解析：点了才请求，首屏时间一分不变。
     * 主流程（答案）跑完才会被点到，所以不会互相抢带宽。
     */
    private void loadExplain(int index) {
        if (index < 0 || index >= cardExplain.size()) return;
        if (explainCache.containsKey(index)) return;      // 已经加载过
        if (explainLoading.contains(index)) return;
        if (imagePath == null) return;

        explainLoading.add(index);
        TextView tv = cardExplain.get(index);
        tv.setTextColor(Color.parseColor("#8A94A6"));
        tv.setText(getString(R.string.explain_loading));

        final String no = index < cardNos.size() ? cardNos.get(index) : String.valueOf(index + 1);
        final String student = index < cardStudents.size() ? cardStudents.get(index) : "";
        final String answer = index < cardAnswers.size() ? cardAnswers.get(index) : "";

        explainPool.execute(() -> {
            String text;
            try {
                text = VisionApi.explain(this, imagePath, no, student, answer);
                if (text == null || text.trim().isEmpty()) text = getString(R.string.explain_empty);
            } catch (Exception e) {
                text = getString(R.string.explain_failed) + " " + e.getMessage();
            }
            final String result = text.trim();
            runOnUiThread(() -> {
                if (isFinishing()) return;
                explainLoading.remove(index);
                explainCache.put(index, result);
                if (index < cardExplain.size()) {
                    TextView v = cardExplain.get(index);
                    v.setTextColor(Color.parseColor("#B8C2D4"));
                    v.setText(result);
                }
            });
        });
    }

    private void buildNav(int count) {
        LinearLayout nav = findViewById(R.id.nav);
        nav.removeAllViews();
        for (int i = 0; i < count; i++) {
            final int idx = i;
            TextView dot = new TextView(this);
            dot.setText(String.valueOf(i + 1));
            dot.setTextColor(Color.parseColor("#C9D1DB"));
            dot.setTextSize(12);
            dot.setGravity(Gravity.CENTER);
            dot.setBackgroundResource(R.drawable.bg_pill);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(30), dp(30));
            lp.setMarginEnd(dp(6));
            dot.setLayoutParams(lp);
            dot.setOnClickListener(v -> scrollToCard(idx));
            nav.addView(dot);
        }
    }

    private void scrollToCard(int index) {
        if (index >= cardTitles.size()) return;
        View card = (View) cardTitles.get(index).getParent();
        overlay.setHighlight(index);
        // 图片区固定在顶部，这里只滚下面的列表
        scrollView.smoothScrollTo(0, Math.max(0, card.getTop() - dp(8)));
    }

    private void copyAll() {
        if (answers.isEmpty()) {
            Toast.makeText(this, R.string.nothing_to_copy, Toast.LENGTH_SHORT).show();
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < answers.size(); i++) {
            sb.append(getString(R.string.question_n, i + 1)).append('\n')
                    .append(answers.get(i)).append("\n\n");
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("answers", sb.toString()));
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    @Override
    protected void onDestroy() {
        if (explainPool != null) explainPool.shutdownNow();
        super.onDestroy();
        if (pool != null) pool.shutdownNow();
    }
}
