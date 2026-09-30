package com.shipalert.core;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 關鍵字規則，一行一條：
 *   停泊|停治 => ⚓ 船已到達【{loc}】
 * 可用變數：{loc} 位置、{kw} 命中字、{time} 時間。以 # 開頭為註解。
 */
public final class KeywordRule {
    public static final String DEFAULT_TEMPLATE = "檢測到「{kw}」｜位置：{loc}（{time}）";

    public final String id;
    public final String[] keywords;
    public final String template;

    public KeywordRule(String id, String[] keywords, String template) {
        this.id = id; this.keywords = keywords; this.template = template;
    }

    public static List<KeywordRule> parseAll(String text) {
        List<KeywordRule> out = new ArrayList<>();
        if (text == null) return out;
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String kwPart = line, tpl = DEFAULT_TEMPLATE;
            int idx = line.indexOf("=>");
            if (idx >= 0) {
                kwPart = line.substring(0, idx);
                String t = line.substring(idx + 2).trim();
                if (!t.isEmpty()) tpl = t;
            }
            List<String> kws = new ArrayList<>();
            for (String k : kwPart.split("[|｜]")) {
                String n = ScreenParser.normalize(k);
                if (!n.isEmpty()) kws.add(n);
            }
            if (kws.isEmpty()) continue;
            out.add(new KeywordRule(line, kws.toArray(new String[0]), tpl));
        }
        return out;
    }

    /** 回傳命中的關鍵字，未命中回傳 null。text 需先 normalize。 */
    public String match(String normalizedText) {
        for (String k : keywords) if (normalizedText.contains(k)) return k;
        return null;
    }

    public String render(String loc, String kw, long timeMs) {
        String time = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(timeMs));
        return template.replace("{loc}", loc == null ? "未知位置" : loc)
                .replace("{kw}", kw == null ? "" : kw)
                .replace("{time}", time);
    }

    public String displayName() { return keywords[0]; }
}
