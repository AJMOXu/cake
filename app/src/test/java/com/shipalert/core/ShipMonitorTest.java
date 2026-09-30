package com.shipalert.core;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ShipMonitorTest {

    /** 依附件截圖（1583x1143）模擬的 OCR 結果 */
    static List<OcrLine> sampleScreen(boolean withDock) {
        float W = 1583f, H = 1143f;
        List<OcrLine> l = new ArrayList<>();
        l.add(new OcrLine("季节伺服器-2 PM 2:34", 1425 / W, 18 / H, 1575 / W, 36 / H));
        l.add(new OcrLine("2D", 1333 / W, 46 / H, 1350 / W, 60 / H));
        l.add(new OcrLine("罗兹班岛", 1412 / W, 47 / H, 1490 / W, 69 / H));
        l.add(new OcrLine("罗兹班岛据点所属", 1452 / W, 225 / H, 1568 / W, 241 / H));
        l.add(new OcrLine("已进入战斗地区。", 60 / W, 760 / H, 190 / W, 776 / H));
        l.add(new OcrLine("船舶粮食快耗尽了。", 60 / W, 870 / H, 190 / W, 886 / H));
        l.add(new OcrLine("未接受：[赛林迪亚]开...", 1350 / W, 506 / H, 1500 / W, 522 / H));
        if (withDock) l.add(new OcrLine("停 泊", 1148 / W, 495 / H, 1185 / W, 515 / H));
        return l;
    }

    static class RecSink implements ShipMonitor.Sink {
        List<String> pushes = new ArrayList<>();
        public void push(String t, String c) { pushes.add(c); System.out.println("PUSH> " + c.replace("\n", " / ")); }
        public void log(String m) { System.out.println("LOG > " + m); }
    }

    static ShipMonitor.Settings settings() {
        ShipMonitor.Settings s = new ShipMonitor.Settings();
        s.rules = KeywordRule.parseAll("# 註解\n停泊|停治 => ⚓ 船已到達【{loc}】港口\n卡莫斯\n");
        s.mapRoi = new NormRect(0.80f, 0.0f, 1f, 0.25f);
        return s;
    }

    static float[] sig(int seed) {
        int[] px = new int[Signature.SIZE * Signature.SIZE];
        for (int i = 0; i < px.length; i++) {
            int v = ((i * 37 + seed * 101) % 256);
            px[i] = 0xff000000 | (v << 16) | (v << 8) | v;
        }
        return Signature.fromArgb(px);
    }

    @Test
    public void location() {
        ShipMonitor.Settings s = settings();
        assertEquals("罗兹班岛", ScreenParser.extractLocation(sampleScreen(false), s.mapRoi));
        // 標題沒識別到時用「据点所属」備援
        List<OcrLine> only = new ArrayList<>();
        only.add(new OcrLine("罗兹班岛据点所属", 0.92f, 0.2f, 0.99f, 0.21f));
        assertEquals("罗兹班岛", ScreenParser.extractLocation(only, s.mapRoi));
    }

    @Test
    public void autoLocate() {
        NormRect r = ScreenParser.autoLocateMap(sampleScreen(false), 1583f / 1143f);
        System.out.println("autoLocate px = " + r.left * 1583 + "," + r.top * 1143 + " - " + r.right * 1583 + "," + r.bottom * 1143);
        assertTrue(r != null);
        // 地名、据点所属在框內；時鐘、「2D」、任務欄不在框內
        assertEquals("罗兹班岛", ScreenParser.extractLocation(sampleScreen(false), r));
        assertTrue(!r.contains(1500 / 1583f, 27 / 1143f));   // 時鐘
        assertTrue(!r.contains(1425 / 1583f, 514 / 1143f));  // 任務欄
        // 框完全不對時，仍能從全畫面備援拿到位置
        assertEquals("罗兹班岛", ScreenParser.extractLocation(sampleScreen(false), new NormRect(0.3f, 0.05f, 0.5f, 0.2f)));
    }

    @Test
    public void rules() {
        List<KeywordRule> r = KeywordRule.parseAll("# x\n停泊|停治 => 到了{loc}\n\n卡莫斯");
        assertEquals(2, r.size());
        assertEquals(2, r.get(0).keywords.length);
        assertEquals(KeywordRule.DEFAULT_TEMPLATE, r.get(1).template);
    }

    @Test
    public void dockAndStay() {
        RecSink sink = new RecSink();
        ShipMonitor m = new ShipMonitor(sink, settings());
        long t = 0, step = 10_000;
        // 航行中：無停泊、畫面一直變
        for (int i = 0; i < 6; i++, t += step) m.onFrame(t, sampleScreen(false), sig(i * 7));
        assertEquals(0, sink.pushes.size());
        // 出現停泊：第 2 幀確認後推送一次
        m.onFrame(t, sampleScreen(true), sig(100)); t += step;
        assertEquals(0, sink.pushes.size());
        m.onFrame(t, sampleScreen(true), sig(100)); t += step;
        assertEquals(1, sink.pushes.size());
        assertTrue(sink.pushes.get(0).contains("罗兹班岛"));
        // 停在原地 12 分鐘 → 停留提醒
        for (int i = 0; i < 72; i++, t += step) m.onFrame(t, sampleScreen(true), sig(100));
        assertEquals(2, sink.pushes.size());
        assertTrue(sink.pushes.get(1).contains("停留"));
        // 開走 → 離開提醒
        m.onFrame(t, sampleScreen(false), sig(3)); t += step;
        m.onFrame(t, sampleScreen(false), sig(9)); t += step;
        assertEquals(3, sink.pushes.size());
        assertTrue(sink.pushes.get(2).contains("離開"));
        // 畫面全黑 5 分鐘 → 異常提醒
        for (int i = 0; i < 32; i++, t += step) m.onFrame(t, new ArrayList<OcrLine>(), sig(0));
        assertEquals(4, sink.pushes.size());
        assertTrue(sink.pushes.get(3).contains("異常") || sink.pushes.get(3).contains("識別不到"));
    }
}
