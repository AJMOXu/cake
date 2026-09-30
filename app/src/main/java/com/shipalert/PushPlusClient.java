package com.shipalert;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** PushPlus 推送（http://www.pushplus.plus/send）。需在背景執行緒呼叫。 */
public final class PushPlusClient {
    private static final String[] ENDPOINTS = {
            "https://www.pushplus.plus/send",
            "http://www.pushplus.plus/send"   // 老手機 HTTPS 失敗時的備援
    };

    private PushPlusClient() {}

    /** @return 成功回傳 null，失敗回傳錯誤描述 */
    public static String send(String token, String topic, String title, String content) {
        String lastErr = "未知錯誤";
        for (String ep : ENDPOINTS) {
            try {
                JSONObject o = new JSONObject();
                o.put("token", token);
                o.put("title", title);
                o.put("content", content);
                o.put("template", "txt");
                if (topic != null && !topic.isEmpty()) o.put("topic", topic);
                String resp = post(ep, o.toString());
                JSONObject r = new JSONObject(resp);
                int code = r.optInt("code", -1);
                if (code == 200) return null;
                return "PushPlus 回應 " + code + "：" + r.optString("msg");   // 伺服器已回應，不必重試
            } catch (Exception e) {
                lastErr = e.getClass().getSimpleName() + "：" + e.getMessage();
            }
        }
        return lastErr;
    }

    private static String post(String url, String json) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(15000);
            c.setReadTimeout(15000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] body = json.getBytes("UTF-8");
            c.setFixedLengthStreamingMode(body.length);
            OutputStream os = c.getOutputStream();
            os.write(body);
            os.close();
            int code = c.getResponseCode();
            InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (is == null) throw new IOException("HTTP " + code);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            is.close();
            return bo.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }
}
