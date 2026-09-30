package com.shipalert.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 從 OCR 結果中提取位置與關鍵字。純 Java，可單元測試。 */
public final class ScreenParser {
    private ScreenParser() {}

    private static final Pattern STRONGHOLD = Pattern.compile("^(.{2,}?)(据点|據點|据點|據点)");
    private static final Pattern SKIP = Pattern.compile("(伺服器|服务器|服務器|PM|AM|[0-9]{1,2}[:：][0-9]{2})");

    public static String normalize(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isWhitespace(c) && c != '　') sb.append(c);
        }
        return sb.toString();
    }

    /** 只保留文字與數字（去掉標點、框線等 OCR 雜訊）。 */
    public static String clean(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c)) sb.append(c);
        }
        return sb.toString();
    }

    public static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF) || (c >= 0xF900 && c <= 0xFAFF);
    }

    public static int cjkCount(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (isCjk(s.charAt(i))) n++;
        return n;
    }

    /**
     * 從地圖區提取位置名稱。
     * 1) 地圖標題（地圖區內字體最大的中文行，如「罗兹班岛」）
     * 2) 「XXX据点所属」中的 XXX 作為備用
     */
    public static String extractLocation(List<OcrLine> lines, NormRect mapRoi) {
        String fromStronghold = null;
        OcrLine best = null;
        String bestText = null;
        for (OcrLine l : lines) {
            if (!l.centerIn(mapRoi)) continue;
            String t = clean(l.text);
            if (t.isEmpty()) continue;
            Matcher m = STRONGHOLD.matcher(t);
            if (m.find()) {
                if (fromStronghold == null) fromStronghold = m.group(1);
                continue;
            }
            if (SKIP.matcher(l.text).find()) continue;
            if (cjkCount(t) < 2) continue;              // 排除 "2D"、單字雜訊
            if (best == null || l.height() > best.height() * 1.05f
                    || (Math.abs(l.height() - best.height()) <= best.height() * 0.05f && l.top < best.top)) {
                best = l;
                bestText = t;
            }
        }
        if (bestText != null) {
            if (fromStronghold != null && !bestText.contains(fromStronghold) && !fromStronghold.contains(bestText)) {
                return bestText + "（" + fromStronghold + "據點）";
            }
            return bestText;
        }
        return fromStronghold;
    }

    /** 回傳 規則 -> 命中字。 */
    public static Map<KeywordRule, String> matchKeywords(List<OcrLine> lines, NormRect kwRoi, List<KeywordRule> rules) {
        Map<KeywordRule, String> hits = new LinkedHashMap<>();
        List<String> texts = new ArrayList<>();
        for (OcrLine l : lines) if (l.centerIn(kwRoi)) texts.add(normalize(l.text));
        for (KeywordRule r : rules) {
            for (String t : texts) {
                String k = r.match(t);
                if (k != null) { hits.put(r, k); break; }
            }
        }
        return hits;
    }

    public static int totalChars(List<OcrLine> lines) {
        int n = 0;
        for (OcrLine l : lines) n += clean(l.text).length();
        return n;
    }
}
