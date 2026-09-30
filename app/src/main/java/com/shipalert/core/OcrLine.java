package com.shipalert.core;

/** 一行 OCR 結果，座標已正規化到 0~1。 */
public final class OcrLine {
    public final String text;
    public final float left, top, right, bottom;

    public OcrLine(String text, float left, float top, float right, float bottom) {
        this.text = text == null ? "" : text;
        this.left = left; this.top = top; this.right = right; this.bottom = bottom;
    }

    public float cx() { return (left + right) / 2f; }
    public float cy() { return (top + bottom) / 2f; }
    public float height() { return bottom - top; }
    public boolean centerIn(NormRect r) { return r.contains(cx(), cy()); }
}
