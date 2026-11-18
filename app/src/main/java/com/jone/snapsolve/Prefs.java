package com.jone.snapsolve;

import android.content.Context;
import android.content.SharedPreferences;

/** 简易配置存储：接口地址 / 密钥 / 模型名 / 提示词。 */
public final class Prefs {

    private static final String NAME = "snapsolve";

    public static final String DEFAULT_BASE_URL = "https://ark.cn-beijing.volces.com/api/v3";
    public static final String DEFAULT_MODEL = "doubao-seed-2-1-lite-260915";
    public static final String DEFAULT_PROMPT =
            "你是一个解题助手。图片里是一道题目，请：\n"
          + "1. 先用一行给出最终答案；\n"
          + "2. 再给出简明的分步解析（不超过 8 步）；\n"
          + "3. 如果图片里有多道题，逐题作答并标号；\n"
          + "4. 数学公式用纯文本或 LaTeX 表示；\n"
          + "5. 如果看不清题目，直接说明哪里不清晰，不要编造。";

    private Prefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static String baseUrl(Context c) {
        return sp(c).getString("baseUrl", DEFAULT_BASE_URL);
    }

    public static String apiKey(Context c) {
        return sp(c).getString("apiKey", "");
    }

    public static String model(Context c) {
        return sp(c).getString("model", DEFAULT_MODEL);
    }

    public static String prompt(Context c) {
        return sp(c).getString("prompt", DEFAULT_PROMPT);
    }

    public static boolean isConfigured(Context c) {
        String k = apiKey(c);
        return k != null && !k.trim().isEmpty();
    }

    public static void save(Context c, String baseUrl, String apiKey, String model, String prompt) {
        sp(c).edit()
                .putString("baseUrl", baseUrl.trim())
                .putString("apiKey", apiKey.trim())
                .putString("model", model.trim())
                .putString("prompt", prompt)
                .apply();
    }

    public static boolean autoCapture(Context c) {
        return sp(c).getBoolean("autoCapture", true);
    }

    public static void setAutoCapture(Context c, boolean value) {
        sp(c).edit().putBoolean("autoCapture", value).apply();
    }

    /** 学科选择：auto / math / chinese / english，只影响提示词。 */
    public static String subject(Context c) {
        return sp(c).getString("subject", "auto");
    }

    public static void setSubject(Context c, String value) {
        sp(c).edit().putString("subject", value).apply();
    }

    /** 批改模式：判对错，而不是直接给答案。 */
    public static boolean grading(Context c) {
        return sp(c).getBoolean("grading", false);
    }

    public static void setGrading(Context c, boolean value) {
        sp(c).edit().putBoolean("grading", value).apply();
    }

    /** 一页多题时是否把框收紧到单题。 */
    public static boolean splitQuestions(Context c) {
        return sp(c).getBoolean("splitQuestions", true);
    }

    public static void setSplitQuestions(Context c, boolean value) {
        sp(c).edit().putBoolean("splitQuestions", value).apply();
    }

    /** 上传前模糊拦截阈值，0 表示关闭。 */
    public static int blurThreshold(Context c) {
        return sp(c).getInt("blurThreshold", Sharpness.DEFAULT_THRESHOLD);
    }

    public static void setBlurThreshold(Context c, int value) {
        sp(c).edit().putInt("blurThreshold", Math.max(0, value)).apply();
    }
}
