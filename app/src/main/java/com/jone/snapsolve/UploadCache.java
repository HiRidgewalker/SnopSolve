package com.jone.snapsolve;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

/**
 * 本地解题结果缓存。
 *
 * 对应原 App 的 `ApolloQuestionCacheV2` / `QueryDetailCacheModel`：
 * 同一张图（按内容 SHA-256 判定）再拍一次，直接出答案，不再走网络。
 */
public final class UploadCache {

    private static final String SP = "snapsolve_cache";
    private static final int MAX_ENTRIES = 30;

    private UploadCache() {}

    public static String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(data);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(data.length);
        }
    }

    public static String get(Context c, String key) {
        if (key == null) return null;
        try {
            JSONObject root = new JSONObject(prefs(c).getString("data", "{}"));
            JSONObject item = root.optJSONObject(key);
            return item == null ? null : item.optString("a", null);
        } catch (Exception e) {
            return null;
        }
    }

    public static void put(Context c, String key, String answer) {
        if (key == null || answer == null || answer.trim().isEmpty()) return;
        try {
            SharedPreferences sp = prefs(c);
            JSONObject root = new JSONObject(sp.getString("data", "{}"));
            JSONObject item = new JSONObject();
            item.put("a", answer);
            item.put("t", System.currentTimeMillis());
            root.put(key, item);

            // 超出上限时删最旧的
            if (root.length() > MAX_ENTRIES) {
                List<String> keys = new ArrayList<>();
                Iterator<String> it = root.keys();
                while (it.hasNext()) keys.add(it.next());

                final JSONObject snapshot = root;
                keys.sort(Comparator.comparingLong(k -> {
                    JSONObject o = snapshot.optJSONObject(k);
                    return o == null ? 0L : o.optLong("t", 0L);
                }));

                int remove = root.length() - MAX_ENTRIES;
                for (int i = 0; i < remove && i < keys.size(); i++) root.remove(keys.get(i));
            }
            sp.edit().putString("data", root.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(SP, Context.MODE_PRIVATE);
    }
}
