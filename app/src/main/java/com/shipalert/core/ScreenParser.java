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
        if (fromStronghold != null) return fromStronghold;
        // 地圖區內找不到 → 在全畫面找「XXX据点所属」作為備援（框歪了也能拿到位置）
        for (OcrLine l : lines) {
            Matcher m = STRONGHOLD.matcher(clean(l.text));
            if (m.find()) return m.group(1);
        }
        return null;
    }

    private static boolean isStrongholdLine(OcrLine l) {
        return STRONGHOLD.matcher(clean(l.text)).find();
    }

    /**
     * 自動定位右上角地圖區：以「XXX据点所属」行（地圖底部）和上方的地圖標題（地圖頂部）推算地圖框。
     * @param aspect 影像寬/高
     * @return 找不到回傳 null
     */
    public static NormRect autoLocateMap(List<OcrLine> lines, float aspect) {
        OcrLine s = null;
        for (OcrLine l : lines) {
            if (isStrongholdLine(l) && (s == null || l.cx() > s.cx())) s = l;   // 多個時取最靠右
        }
        if (s == null) return null;
        // 轉成「以影像高度為單位」的座標，避免寬高比失真
        float sl = s.left * aspect, sr = s.right * aspect, sh = s.height();
        OcrLine title = null;
        for (OcrLine l : lines) {
            if (l == s || l.bottom > s.top || s.top - l.bottom > sh * 16) continue;
            String t = clean(l.text);
            if (cjkCount(t) < 2 || SKIP.matcher(l.text).find()) continue;
            float lcx = l.cx() * aspect;
            if (lcx < sl - sh * 10 || lcx > sr + sh * 4) continue;           // 要在地圖水平範圍內
            if (title == null || l.height() > title.height() * 1.05f) title = l;
        }
        float top, H;
        float right = sr;
        if (title != null) {
            H = s.bottom - title.top;
            right = Math.max(right, title.right * aspect);
        } else {
            H = sh * 13f;
        }
        top = s.bottom - H * 1.05f;
        float bottom = s.bottom + sh * 0.4f;
        right = right + H * 0.06f;
        float left = right - H * 1.3f;
        NormRect r = new NormRect(left / aspect, top, right / aspect, bottom);
        if (r.width() < 0.03f || r.height() < 0.05f) return null;
        return r;
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
