package com.shipalert.core;

/** 地圖區域的縮圖指紋，用來判斷「畫面是否和上一次一致」（船是否在移動）。 */
public final class Signature {
    public static final int SIZE = 32;

    private Signature() {}

    /** 由 ARGB 像素生成去均值灰度指紋（減少曝光變化影響）。 */
    public static float[] fromArgb(int[] px) {
        float[] g = new float[px.length];
        double sum = 0;
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            float v = 0.299f * ((c >> 16) & 0xff) + 0.587f * ((c >> 8) & 0xff) + 0.114f * (c & 0xff);
            g[i] = v;
            sum += v;
        }
        float mean = (float) (sum / Math.max(1, px.length));
        for (int i = 0; i < g.length; i++) g[i] -= mean;
        return g;
    }

    /** 平均絕對差（0~255）。越大代表畫面變化越大。 */
    public static float diff(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) return Float.MAX_VALUE;
        double s = 0;
        for (int i = 0; i < a.length; i++) s += Math.abs(a[i] - b[i]);
        return (float) (s / a.length);
    }

    /** anchor = anchor*(1-alpha) + cur*alpha，用於吸收緩慢的光線變化。 */
    public static float[] blend(float[] anchor, float[] cur, float alpha) {
        float[] o = new float[anchor.length];
        for (int i = 0; i < o.length; i++) o[i] = anchor[i] * (1 - alpha) + cur[i] * alpha;
        return o;
    }
}
