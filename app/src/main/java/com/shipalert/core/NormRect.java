package com.shipalert.core;

import java.util.Locale;

/** 以 0~1 正規化座標表示的矩形（相對於攝像頭畫面）。 */
public final class NormRect {
    public static final NormRect FULL = new NormRect(0f, 0f, 1f, 1f);
    /** 預設地圖區：畫面右上角 */
    public static final NormRect DEFAULT_MAP = new NormRect(0.70f, 0.0f, 1.0f, 0.35f);

    public final float left, top, right, bottom;

    public NormRect(float l, float t, float r, float b) {
        float x1 = clamp(Math.min(l, r)), x2 = clamp(Math.max(l, r));
        float y1 = clamp(Math.min(t, b)), y2 = clamp(Math.max(t, b));
        left = x1; top = y1; right = x2; bottom = y2;
    }

    private static float clamp(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }

    public boolean contains(float x, float y) {
        return x >= left && x <= right && y >= top && y <= bottom;
    }

    public float width() { return right - left; }
    public float height() { return bottom - top; }

    public String serialize() {
        return String.format(Locale.US, "%.4f,%.4f,%.4f,%.4f", left, top, right, bottom);
    }

    public static NormRect parse(String s, NormRect def) {
        if (s == null) return def;
        try {
            String[] p = s.split(",");
            if (p.length != 4) return def;
            NormRect r = new NormRect(Float.parseFloat(p[0].trim()), Float.parseFloat(p[1].trim()),
                    Float.parseFloat(p[2].trim()), Float.parseFloat(p[3].trim()));
            if (r.width() < 0.01f || r.height() < 0.01f) return def;
            return r;
        } catch (Exception e) {
            return def;
        }
    }

    @Override public String toString() { return serialize(); }
}
