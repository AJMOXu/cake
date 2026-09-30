package com.shipalert.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 核心狀態機：
 *  - 關鍵字（如「停泊」）出現 → 推送到港提醒（連續 N 幀確認 + 冷卻時間防重複）
 *  - 地圖區畫面與上次比較，長時間不變 → 推送「船已在 X 停留 Y」，並可定期重複
 *  - 停留後畫面恢復變化 → 推送「船已離開」
 *  - 長時間識別不到文字 → 推送「畫面異常」
 */
public class ShipMonitor {

    public interface Sink {
        void push(String title, String content);
        void log(String msg);
    }

    public static class Settings {
        public List<KeywordRule> rules = new ArrayList<>();
        public NormRect mapRoi = NormRect.DEFAULT_MAP;
        public NormRect kwRoi = NormRect.FULL;
        public int confirmFrames = 2;
        public int releaseFrames = 3;
        public long cooldownMs = 10 * 60_000L;
        public boolean stayEnabled = true;
        public long stayThresholdMs = 10 * 60_000L;
        public long stayRepeatMs = 30 * 60_000L;
        public float diffThreshold = 8f;
        public int moveConfirmFrames = 2;
        public boolean leaveNotify = true;
        public long noTextMs = 5 * 60_000L;
        public int minTextChars = 4;
    }

    public static class FrameResult {
        public String location;        // 本幀識別到的位置
        public String knownLocation;   // 最近一次有效位置
        public List<String> hits = new ArrayList<>();
        public int totalChars;
        public float diff = -1;
        public long stillMs = -1;
        public boolean textOk;
    }

    private static class KwState {
        int hits, misses;
        boolean active;
        long lastPushAt = -1;
    }

    private final Sink sink;
    private Settings s;
    private final Map<String, KwState> kw = new HashMap<>();

    private float[] anchor;
    private long stillSince;
    private int stayAlerts;
    private long lastStayAlertAt;
    private int movedFrames;
    private String knownLocation;
    private String stayLocation;
    private long noTextSince;
    private boolean noTextAlerted;

    public ShipMonitor(Sink sink, Settings s) {
        this.sink = sink;
        this.s = s;
    }

    public synchronized void setSettings(Settings s) { this.s = s; }

    public synchronized void reset() {
        kw.clear();
        anchor = null;
        stillSince = 0;
        stayAlerts = 0;
        lastStayAlertAt = 0;
        movedFrames = 0;
        knownLocation = null;
        stayLocation = null;
        noTextSince = 0;
        noTextAlerted = false;
    }

    /** 只解析不改變狀態（「識別一次」測試用）。 */
    public synchronized FrameResult inspect(List<OcrLine> lines, float[] mapSig) {
        FrameResult r = new FrameResult();
        r.totalChars = ScreenParser.totalChars(lines);
        r.textOk = r.totalChars >= s.minTextChars;
        r.location = ScreenParser.extractLocation(lines, s.mapRoi);
        r.knownLocation = r.location != null ? r.location : knownLocation;
        for (Map.Entry<KeywordRule, String> e : ScreenParser.matchKeywords(lines, s.kwRoi, s.rules).entrySet())
            r.hits.add(e.getValue());
        if (anchor != null && mapSig != null) r.diff = Signature.diff(anchor, mapSig);
        return r;
    }

    public synchronized FrameResult onFrame(long now, List<OcrLine> lines, float[] mapSig) {
        FrameResult r = new FrameResult();
        r.totalChars = ScreenParser.totalChars(lines);
        r.textOk = r.totalChars >= s.minTextChars;

        // ---------- 位置 ----------
        r.location = ScreenParser.extractLocation(lines, s.mapRoi);
        if (r.location != null) knownLocation = r.location;
        r.knownLocation = knownLocation;
        String loc = knownLocation != null ? knownLocation : "未知位置";

        // ---------- 畫面異常 ----------
        if (!r.textOk) {
            if (noTextSince == 0) noTextSince = now;
            if (s.noTextMs > 0 && !noTextAlerted && now - noTextSince >= s.noTextMs) {
                noTextAlerted = true;
                sink.push("⚠️ 畫面異常", "已 " + TimeFmt.duration(now - noTextSince)
                        + " 識別不到螢幕文字。\n可能原因：手機被移動、電腦螢幕關閉/休眠、遊戲斷線。\n最後位置：" + loc);
            }
        } else {
            if (noTextAlerted) {
                sink.push("✅ 畫面已恢復", "已重新識別到螢幕內容。位置：" + loc);
            }
            noTextSince = 0;
            noTextAlerted = false;
        }

        // ---------- 關鍵字 ----------
        Map<KeywordRule, String> hits = ScreenParser.matchKeywords(lines, s.kwRoi, s.rules);
        for (KeywordRule rule : s.rules) {
            KwState st = kw.get(rule.id);
            if (st == null) { st = new KwState(); kw.put(rule.id, st); }
            String hit = hits.get(rule);
            if (hit != null) {
                r.hits.add(hit);
                st.hits++;
                st.misses = 0;
                if (!st.active && st.hits >= Math.max(1, s.confirmFrames)) {
                    st.active = true;
                    if (st.lastPushAt < 0 || now - st.lastPushAt >= s.cooldownMs) {
                        st.lastPushAt = now;
                        String msg = rule.render(loc, hit, now);
                        sink.push(shortTitle(msg), msg);
                    } else {
                        sink.log("「" + hit + "」再次出現，但仍在冷卻時間內，略過推送");
                    }
                }
            } else if (r.textOk) {   // 畫面異常時不算「消失」
                st.misses++;
                st.hits = 0;
                if (st.active && st.misses >= Math.max(1, s.releaseFrames)) {
                    st.active = false;
                    sink.log("「" + rule.displayName() + "」已從畫面消失");
                }
            }
        }

        // ---------- 停留檢測（對比上一次畫面） ----------
        if (mapSig != null && r.textOk) {
            if (anchor == null) {
                anchor = mapSig;
                stillSince = now;
                stayAlerts = 0;
                movedFrames = 0;
            } else {
                float d = Signature.diff(anchor, mapSig);
                r.diff = d;
                if (d > s.diffThreshold) {
                    movedFrames++;
                    if (movedFrames >= Math.max(1, s.moveConfirmFrames)) {
                        if (stayAlerts > 0 && s.leaveNotify) {
                            String where = stayLocation != null ? stayLocation : loc;
                            sink.push("⛵ 船已離開" + where, "船已離開【" + where + "】，恢復航行。\n共停留約 "
                                    + TimeFmt.duration(now - stillSince) + "。");
                        }
                        anchor = mapSig;
                        stillSince = now;
                        stayAlerts = 0;
                        movedFrames = 0;
                        stayLocation = null;
                    }
                } else {
                    movedFrames = 0;
                    anchor = Signature.blend(anchor, mapSig, 0.2f);
                    long still = now - stillSince;
                    if (s.stayEnabled && still >= s.stayThresholdMs
                            && (stayAlerts == 0 || (s.stayRepeatMs > 0 && now - lastStayAlertAt >= s.stayRepeatMs))) {
                        stayAlerts++;
                        lastStayAlertAt = now;
                        if (stayLocation == null) stayLocation = loc;
                        String msg = "⏳ 船已在【" + stayLocation + "】停留了 " + TimeFmt.duration(still)
                                + (stayAlerts > 1 ? "（第 " + stayAlerts + " 次提醒）" : "");
                        sink.push("⏳ 已停留 " + TimeFmt.duration(still), msg
                                + String.format(Locale.US, "\n畫面差異 %.1f（閾值 %.1f）", d, s.diffThreshold));
                    }
                }
            }
            r.stillMs = now - stillSince;
        }
        return r;
    }

    private static String shortTitle(String msg) {
        String t = msg.replace('\n', ' ');
        return t.length() > 30 ? t.substring(0, 30) + "…" : t;
    }
}
