package com.jone.snapsolve;

import android.content.Context;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import okio.BufferedSource;

/**
 * 解题请求：把裁剪后的图片以 data URL 形式发给 OpenAI 兼容的视觉模型接口。
 * 不写死任何厂商、任何密钥，全部来自设置页。
 */
public final class VisionApi {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private VisionApi() {}

    public static String solve(Context ctx, String imagePath) throws Exception {
        return solve(ctx, imagePath, null);
    }

    /**
     * @param hint 题目级提示（例如"这是第 3 题，只答这一题"）；整页请求时传 null
     */
    public static String solve(Context ctx, String imagePath, String hint) throws Exception {
        String base = Prefs.baseUrl(ctx);
        String key = Prefs.apiKey(ctx);
        String model = Prefs.model(ctx);
        String prompt = subjectPrefix(Prefs.subject(ctx)) + Prefs.prompt(ctx);
        if (hint != null && !hint.isEmpty()) prompt = hint + "\n\n" + prompt;

        String b64 = Base64.encodeToString(readFile(imagePath), Base64.NO_WRAP);
        String dataUrl = "data:image/jpeg;base64," + b64;

        JSONArray content = new JSONArray();
        content.put(new JSONObject().put("type", "text").put("text", prompt));
        content.put(new JSONObject()
                .put("type", "image_url")
                .put("image_url", new JSONObject().put("url", dataUrl)));

        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "user").put("content", content));

        JSONObject body = new JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("temperature", 0.2)
                .put("max_tokens", 2048);

        // 火山方舟的 Seed 系列默认开启深度思考，实测会把一次请求从 3 秒拖到 80 秒以上。
        // 只对这个域名下发该字段，避免其他 OpenAI 兼容服务因未知参数报 400。
        if (base != null && base.contains("volces.com")) {
            body.put("thinking", new JSONObject().put("type", "disabled"));
        }

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build();

        Request request = new Request.Builder()
                .url(endpoint(base))
                .addHeader("Authorization", "Bearer " + key)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(body.toString(), JSON))
                .build();

        try (Response resp = client.newCall(request).execute()) {
            String text = resp.body() == null ? "" : resp.body().string();
            if (!resp.isSuccessful()) {
                throw new IOException("HTTP " + resp.code() + "："
                        + (text.length() > 300 ? text.substring(0, 300) : text));
            }
            return extractContent(text);
        }
    }

    /**
     * 整页一次性求解：让模型自己在图上分题，返回结构化结果。
     *
     * 为什么不在本地切题后逐题请求：本地用投影切题，在密集排版上会把多道题并成一段，
     * 逐题裁出来的图本身就缺内容，模型只能瞎猜（实测第一题只框到标题，模型答"看不到题目"）。
     * 模型看整页时版面理解是可靠的，所以把分题这件交给它做。
     *
     * @return 无法解析成 JSON 时返回 null，调用方退回纯文本展示
     */
    public static String solvePage(Context ctx, String imagePath) throws Exception {
        String base = Prefs.baseUrl(ctx);
        String key = Prefs.apiKey(ctx);
        String model = Prefs.model(ctx);
        String subject = subjectPrefix(Prefs.subject(ctx));

        String prompt = subject
                + "请解答这页试卷上的每一道题，按题号顺序输出。\n"
                + "只输出 JSON，不要任何解释文字，格式：\n"
                + "{\"items\":[{\"no\":\"1\",\"answer\":\"解答内容\"}]}\n"
                + "要求：\n"
                + "1. 每题一项，answer 只写最终答案和一行关键步骤，整条控制在 60 字内，不要写标题、不要展开讲解\n"
                + "2. 看不清的题 answer 写\"题目不清晰\"\n"
                + "3. 严格按图上的题号顺序，不要跳号、不要合并题目";

        String b64 = Base64.encodeToString(readFile(imagePath), Base64.NO_WRAP);
        JSONArray content = new JSONArray();
        content.put(new JSONObject().put("type", "text").put("text", prompt));
        content.put(new JSONObject()
                .put("type", "image_url")
                .put("image_url", new JSONObject().put("url", "data:image/jpeg;base64," + b64)));

        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "user").put("content", content));

        JSONObject body = new JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("temperature", 0.2)
                .put("max_tokens", 2600);

        if (base != null && base.contains("volces.com")) {
            body.put("thinking", new JSONObject().put("type", "disabled"));
        }

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS)
                .build();

        Request request = new Request.Builder()
                .url(endpoint(base))
                .addHeader("Authorization", "Bearer " + key)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(body.toString(), JSON))
                .build();

        try (Response resp = client.newCall(request).execute()) {
            String text = resp.body() == null ? "" : resp.body().string();
            if (!resp.isSuccessful()) {
                throw new IOException("HTTP " + resp.code() + "："
                        + (text.length() > 300 ? text.substring(0, 300) : text));
            }
            return extractContent(text);
        }
    }

    /** 一题的结果：解题模式只有 answer，批改模式还有 verdict/student/correct。 */
    public static final class Item {
        public final String no;
        public final String answer;    // 解析/正确答案
        public final String verdict;   // right / wrong / partial（解题模式下为空）
        public final String student;   // 学生答卷上写的答案
        public final int y1, y2;
        /** 每个作答点（每个空/每个括号）的对错，按从左到右、从上到下；解题模式为空。 */
        public final java.util.List<Boolean> marks;
        /** 每个作答点在整图上的坐标 {y, x}，取值 0~1000；模型没给时为空。 */
        public final java.util.List<int[]> pts;

        public Item(String no, String answer, String verdict, String student, int y1, int y2) {
            this(no, answer, verdict, student, y1, y2, new java.util.ArrayList<>());
        }

        public Item(String no, String answer, String verdict, String student, int y1, int y2,
                    java.util.List<Boolean> marks) {
            this(no, answer, verdict, student, y1, y2, marks, new java.util.ArrayList<>());
        }

        public Item(String no, String answer, String verdict, String student, int y1, int y2,
                    java.util.List<Boolean> marks, java.util.List<int[]> pts) {
            this.marks = marks == null ? new java.util.ArrayList<>() : marks;
            this.pts = pts == null ? new java.util.ArrayList<>() : pts;
            this.no = no;
            this.answer = answer;
            this.verdict = verdict == null ? "" : verdict;
            this.student = student == null ? "" : student;
            this.y1 = y1;
            this.y2 = y2;
        }

        public boolean isGraded() { return !verdict.isEmpty(); }
        public boolean isRight() { return "right".equalsIgnoreCase(verdict); }
        public boolean isWrong() { return "wrong".equalsIgnoreCase(verdict); }
        public boolean isPartial() { return "partial".equalsIgnoreCase(verdict); }
    }

    /** 流式逐题回调。 */
    public interface StreamListener {
        void onItem(Item item);
        void onDone();
    }

    /**
     * 整页流式求解：让模型一行一题地吐，解析出一题就回调一次。
     *
     * 实测首个 token 约 1.5 秒，之后每 0.2~0.3 秒出一题，全部 15 题约 5 秒完成。
     * 比"等全部生成完再一次返回"（约 20 秒）体验好得多，等价于原版"讲解中→讲解"的渐进效果。
     */
    public static void solvePageStream(Context ctx, String imagePath, StreamListener listener)
            throws Exception {
        solvePageStream(ctx, imagePath, false, listener);
    }

    /**
     * @param grading true = 作业批改模式（判对错、指出错因）；false = 解题模式（给出答案）
     */
    public static void solvePageStream(Context ctx, String imagePath, boolean grading,
                                       StreamListener listener) throws Exception {
        String base = Prefs.baseUrl(ctx);
        String key = Prefs.apiKey(ctx);
        String model = Prefs.model(ctx);
        String subject = subjectPrefix(Prefs.subject(ctx));

        String prompt = grading ? gradingPrompt(subject) : solvePrompt(subject);

        String b64 = Base64.encodeToString(readFile(imagePath), Base64.NO_WRAP);
        JSONArray content = new JSONArray();
        content.put(new JSONObject().put("type", "text").put("text", prompt));
        content.put(new JSONObject()
                .put("type", "image_url")
                .put("image_url", new JSONObject().put("url", "data:image/jpeg;base64," + b64)));

        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "user").put("content", content));

        JSONObject body = new JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("temperature", 0)
                .put("seed", 20261105)
                .put("max_tokens", 3000)
                .put("stream", true);

        if (base != null && base.contains("volces.com")) {
            body.put("thinking", new JSONObject().put("type", "disabled"));
        }

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS)
                .build();

        Request request = new Request.Builder()
                .url(endpoint(base))
                .addHeader("Authorization", "Bearer " + key)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(body.toString(), JSON))
                .build();

        try (Response resp = client.newCall(request).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                String err = resp.body() == null ? "" : resp.body().string();
                throw new IOException("HTTP " + resp.code() + "："
                        + (err.length() > 300 ? err.substring(0, 300) : err));
            }
            BufferedSource src = resp.body().source();
            StringBuilder acc = new StringBuilder();
            StringBuilder dump = new StringBuilder();
            String line;
            while ((line = src.readUtf8Line()) != null) {
                dump.append(line).append('\n');
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if (data.isEmpty()) continue;
                if ("[DONE]".equals(data)) break;

                String delta;
                try {
                    JSONObject obj = new JSONObject(data);
                    JSONArray ch = obj.optJSONArray("choices");
                    if (ch == null || ch.length() == 0) continue;
                    JSONObject d = ch.optJSONObject(0) == null
                            ? null : ch.optJSONObject(0).optJSONObject("delta");
                    if (d == null) continue;
                    delta = d.optString("content", "");
                } catch (Exception e) {
                    continue;
                }
                if (delta.isEmpty()) continue;

                acc.append(delta);
                int nl;
                while ((nl = acc.indexOf("\n")) >= 0) {
                    String row = acc.substring(0, nl);
                    acc.delete(0, nl + 1);
                    emit(row, listener);
                }
            }
            if (acc.length() > 0) emit(acc.toString(), listener);

            try {
                java.io.File f = new java.io.File(ctx.getCacheDir(), "last_stream.txt");
                try (java.io.FileOutputStream fo = new java.io.FileOutputStream(f)) {
                    fo.write(dump.toString().getBytes("UTF-8"));
                }
            } catch (Exception ignored) {
            }
        }
        listener.onDone();
    }

    /** 解题模式的提示词。 */
    private static String solvePrompt(String subject) {
        return subject
                + "这是一页试卷。请按题号顺序逐题解答。\n\n"
                + "输出格式：每行一个 JSON 对象，不要数组、不要 markdown、不要任何其他文字：\n"
                + "{\"no\":\"1\",\"answer\":\"...\",\"y1\":282,\"y2\":353}\n"
                + "{\"no\":\"2\",\"answer\":\"...\",\"y1\":363,\"y2\":393}\n\n"
                + "字段说明：\n"
                + "- y1、y2：这道题在整张图片纵向的位置，图片最上边是 0、最下边是 1000 的整数刻度；"
                + "y1 是题目开始行，y2 是题目结束行\n\n"
                + "要求：\n"
                + "0. y1 必须小于 y2，且后一题的 y1 不小于前一题的 y2\n"
                + "1. answer 用完整通顺的句子写，60 字以内，写清「哪个空填什么」或「结论是什么」\n"
                + "2. no 必须是从 1 开始的连续整数，按从上到下、从左到右的阅读顺序编号，"
                + "不要用卷面上「一、填一填/二、辨一辨」的分组编号\n"
                + "3. 页面上的每一道题都要输出。注意页面最底部、最后一行也是题目，务必逐行扫到底\n"
                + "4. 每写完一题就换行，立刻输出下一题，不要等全部想完";
    }

    /**
     * 作业批改模式的提示词。
     * 和解题的区别：要判断学生写的对不对，而不是直接给答案。
     */
    private static String gradingPrompt(String subject) {
        return subject
                + "这是一页学生已经做完的试卷。请逐题批改。\n\n"
                + "【什么算一道题】\n"
                + "- 卷面上的「一、选一选」「二、辨一辨」「四、重庆真题」这类分组标题不是题，不要输出\n"
                + "- 选择题是一道题，A/B/C/D 四个选项不是题目，不要拆开\n"
                + "- 填空/应用题按卷面题号算，一道题可以跨多行\n\n"
                + "【输出格式】每行一个 JSON 对象，不要数组、不要 markdown、不要其他文字：\n"
                + "{\"no\":\"3\",\"y1\":275,\"y2\":330,\"marks\":["
                + "{\"ok\":true,\"y\":295,\"x\":530}],\"student\":\"C\",\"answer\":\"C\"}\n\n"
                + "【字段说明】\n"
                + "- no：卷面上的题号，原样抄\n"
                + "- y1、y2：这道题的纵向范围，整张图高度为 1000（顶部 0、底部 1000）。"
                + "y1 取题干第一行，y2 取这道题最后一行（含选项行、答题横线）\n"
                + "- marks：每个作答点一项（括号里、横线上真正写字的地方）\n"
                + "  · ok：true=对 false=错\n"
                + "  · y：这个作答点在整张图上的纵向位置，整图高度 1000\n"
                + "  · x：这个作答点在整张图上的横向位置，整图宽度 1000（最左边 0、最右边 1000）\n"
                + "- student：学生在卷面上写的答案，各空用；分隔\n"
                + "- answer：正确答案；选择题写成「字母. 数值」，例如「D. 100」；"
                + "有错时在后面补一句错因（30 字以内）\n"
                + "- 选择题：answer 里的字母必须和数值对得上，不能出现「C. 100」这种矛盾\n\n"
                + "【判断标准（务必严格）】\n"
                + "1. answer 必须是**你自己算出来的正确答案**，绝不能照抄 student。"
                + "如果两者是同一串字，说明你没算，要重新独立计算\n"
                + "2. 逐个作答点对比 student 与 answer：完全一致（或数值等价，如 0.5 与 1/2）才 true\n"
                + "3. 数值不同、单位不同、化简不彻底、符号写反，任一不符就是 false\n"
                + "4. 空着没写的作答点算 false，student 对应位置写「未作答」\n"
                + "5. 学生涂改、划掉、写了又改的地方，以最终留下的答案为准\n"
                + "6. 卷面上的红笔勾叉是别人批改的痕迹，仅供参考，必须独立判断\n"
                + "7. 遇到「在括号里画√或×」这类题：先自己判断这个对象**应该**画√还是×，"
                + "再和学生实际画的符号比对，不一致就是 false\n"
                + "8. 每题都要输出，注意页面最底部最后一行\n"
                + "9. y1 必须小于 y2，且后一题的 y1 不小于前一题的 y2\n"
                + "10. 每批完一题就换行，立刻输出下一题";
    }

    /**
     * marks 字段的容错解析。
     *
     * 模型在流式输出下格式不稳定，实测出现过四种：
     * [true,false]（标准）、"[true,false]"（字符串包着数组）、
     * "true,false"（裸串）、"√×√"（符号串）。
     * 解析不出来就会退回"整题一个标记"，所以这里必须吃全。
     */
    /** 一次解析的产物：每个空的对错 + 题内行列。 */
    static final class MarkData {
        final java.util.List<Boolean> ok = new java.util.ArrayList<>();
        final java.util.List<int[]> pts = new java.util.ArrayList<>();
    }

    /**
     * marks 字段的容错解析。
     *
     * 标准格式是对象数组 [{"ok":true,"row":3,"col":1}]。
     * 但模型在流式输出下会漂：可能退化成布尔数组 [true,false]，
     * 也可能是字符串 "[true,false]" / "√×√"。全部都要吃得下——
     * 任何一种解析失败都会让标记退回"整题一个"，位置全丢。
     */
    private static MarkData parseMarks(JSONObject o) {
        MarkData d = new MarkData();
        Object raw = o.opt("marks");
        if (raw == null) raw = o.opt("blanks");
        if (raw == null) return d;

        if (raw instanceof JSONArray) {
            JSONArray a = (JSONArray) raw;
            for (int i = 0; i < a.length(); i++) {
                Object v = a.opt(i);
                if (v instanceof JSONObject) {
                    JSONObject m = (JSONObject) v;
                    d.ok.add(truthy(m.opt("ok")));
                    d.pts.add(new int[]{coord(m, "y"), coord(m, "x")});
                } else {
                    d.ok.add(truthy(v));
                    d.pts.add(new int[]{-1, -1});
                }
            }
            return d;
        }

        String str = String.valueOf(raw).trim();
        if (str.isEmpty() || "null".equalsIgnoreCase(str)) return d;

        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (c == '\u2713' || c == '\u221A' || c == '\u5BF9') d.ok.add(true);
            else if (c == '\u2717' || c == '\u00D7' || c == '\u9519') d.ok.add(false);
            else if (c == 't' || c == 'T') d.ok.add(true);
            else if (c == 'f' || c == 'F') d.ok.add(false);
        }
        for (int i = 0; i < d.ok.size(); i++) {
            d.pts.add(new int[]{-1, -1});
        }
        return d;
    }

    private static boolean truthy(Object v) {
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).doubleValue() != 0;
        String s = String.valueOf(v).trim().toLowerCase();
        return s.equals("true") || s.equals("1") || s.equals("\u2713")
                || s.equals("\u221a") || s.equals("\u5bf9");
    }

    /**
     * 位置字段的容错解析。
     * 模型有时给 282（0-1000 刻度）、有时给 "28%"、有时给 0.28（0-1 小数），都要吃得下。
     */
    private static int coord(JSONObject o, String key) {
        Object v = o.opt(key);
        if (v == null) return -1;
        if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            if (d > 0 && d < 1) d *= 1000;
            return (int) Math.round(d);
        }
        String s = String.valueOf(v).replaceAll("[^0-9.\\-]", "");
        if (s.isEmpty()) return -1;
        try {
            double d = Double.parseDouble(s);
            if (d > 0 && d < 1) d *= 1000;
            return (int) Math.round(d);
        } catch (Exception e) {
            return -1;
        }
    }

    /** 从一行里抠出 {"no":..,"answer":..}，容忍 markdown 包裹和尾逗号。 */
    private static void emit(String row, StreamListener listener) {
        String t = row.trim();
        if (t.isEmpty()) return;
        t = t.replace("```json", "").replace("```", "").trim();
        int s = t.indexOf('{');
        int e = t.lastIndexOf('}');
        if (s < 0 || e <= s) return;
        try {
            JSONObject o = new JSONObject(t.substring(s, e + 1));
            String no = o.optString("no", "");
            String ans = o.optString("answer", "");
            String verdict = o.optString("verdict", "");
            String student = o.optString("student", "");
            int y1 = coord(o, "y1");
            int y2 = coord(o, "y2");

            MarkData md = parseMarks(o);
            java.util.List<Boolean> marks = md.ok;
            // 有 marks 时按逐空结果推导整题判定
            if (!marks.isEmpty()) {
                boolean allOk = true, anyOk = false;
                for (Boolean b : marks) {
                    if (Boolean.TRUE.equals(b)) anyOk = true;
                    else allOk = false;
                }
                verdict = allOk ? "right" : (anyOk ? "partial" : "wrong");
            }

            if (ans.trim().isEmpty() && verdict.trim().isEmpty()) return;
            listener.onItem(new Item(no, ans.trim(), verdict.trim(), student.trim(), y1, y2,
                    marks, md.pts));
        } catch (Exception ignored) {
        }
    }

    /**
     * 单题解析：只讲解这一题的思路，不重复判定。
     *
     * 为什么单独请求而不是塞进主流程：主流程每多一个字，所有题一起变慢。
     * 这里做成"点了才要"，首屏时间一分不变。
     */
    public static String explain(Context ctx, String imagePath, String no,
                                 String student, String answer) throws Exception {
        String base = Prefs.baseUrl(ctx);
        String key = Prefs.apiKey(ctx);
        String model = Prefs.model(ctx);

        String prompt = "这页试卷的第 " + (no == null ? "" : no) + " 题。\n"
                + "学生写的：" + (student == null || student.isEmpty() ? "未作答" : student) + "\n"
                + "正确答案：" + (answer == null ? "" : answer) + "\n\n"
                + "请用 120 字以内说明这道题的解题思路：先讲怎么想（关键步骤），再给结论。\n"
                + "要求：只讲这一题，不要重复上面的答案，不要客套话，直接开始讲。";

        String b64 = Base64.encodeToString(readFile(imagePath), Base64.NO_WRAP);
        JSONArray content = new JSONArray();
        content.put(new JSONObject().put("type", "text").put("text", prompt));
        content.put(new JSONObject()
                .put("type", "image_url")
                .put("image_url", new JSONObject().put("url", "data:image/jpeg;base64," + b64)));

        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "user").put("content", content));

        JSONObject body = new JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("temperature", 0)
                .put("max_tokens", 500);

        if (base != null && base.contains("volces.com")) {
            body.put("thinking", new JSONObject().put("type", "disabled"));
        }

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build();

        Request request = new Request.Builder()
                .url(endpoint(base))
                .addHeader("Authorization", "Bearer " + key)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(body.toString(), JSON))
                .build();

        try (Response resp = client.newCall(request).execute()) {
            String text = resp.body() == null ? "" : resp.body().string();
            if (!resp.isSuccessful()) {
                throw new IOException("HTTP " + resp.code() + "：" + text.substring(0, Math.min(200, text.length())));
            }
            return extractContent(text);
        }
    }

    /** 学科先验只加在提示词里，不依赖任何本地模型。 */
    private static String subjectPrefix(String subject) {
        if (subject == null) return "";
        switch (subject) {
            case "math":
                return "这是一道数学题。请优先给出计算过程与最终答案，公式用 LaTeX。\n\n";
            case "chinese":
                return "这是一道语文题。注意字音字形、文言实词、阅读理解表述的准确性。\n\n";
            case "english":
                return "这是一道英语题。注意语法点、词形变化与句子结构。\n\n";
            default:
                return "";
        }
    }

    static String endpoint(String base) {
        String b = base == null ? "" : base.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        if (b.isEmpty()) b = Prefs.DEFAULT_BASE_URL;
        if (b.endsWith("/chat/completions")) return b;
        return b + "/chat/completions";
    }

    private static String extractContent(String raw) throws Exception {
        JSONObject root = new JSONObject(raw);
        JSONArray choices = root.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            throw new IOException("返回体里没有 choices：" + raw.substring(0, Math.min(200, raw.length())));
        }
        JSONObject msg = choices.getJSONObject(0).optJSONObject("message");
        if (msg == null) throw new IOException("返回体里没有 message");

        Object content = msg.opt("content");
        if (content instanceof String) return (String) content;

        if (content instanceof JSONArray) {
            StringBuilder sb = new StringBuilder();
            JSONArray arr = (JSONArray) content;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject part = arr.optJSONObject(i);
                if (part != null && part.has("text")) sb.append(part.optString("text"));
            }
            return sb.toString();
        }
        return String.valueOf(content);
    }

    private static byte[] readFile(String path) throws IOException {
        File f = new File(path);
        byte[] data = new byte[(int) f.length()];
        try (FileInputStream in = new FileInputStream(f)) {
            int off = 0;
            while (off < data.length) {
                int n = in.read(data, off, data.length - off);
                if (n <= 0) break;
                off += n;
            }
        }
        return data;
    }
}
