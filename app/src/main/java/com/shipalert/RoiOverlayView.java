package com.shipalert;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import com.shipalert.core.NormRect;

import java.util.ArrayList;
import java.util.List;

/**
 * 疊加在預覽畫面上：顯示 地圖區(藍)/關鍵字區(橙)/OCR 文字框(綠)，並支援手指拖曳框選。
 * 預覽使用 FIT_CENTER，因此依畫面比例計算實際影像顯示範圍。
 */
public class RoiOverlayView extends View {
    public static final int MODE_NONE = 0, MODE_MAP = 1, MODE_KW = 2;

    public interface Listener { void onRoiSelected(int mode, NormRect r); }

    private final Paint mapPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint kwPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ocrPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dragPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintBg = new Paint();

    private NormRect mapRoi = NormRect.DEFAULT_MAP, kwRoi = NormRect.FULL;
    private List<NormRect> ocrBoxes = new ArrayList<>();
    private float imageAspect = 16f / 9f;
    private int mode = MODE_NONE;
    private float sx, sy, ex, ey;
    private boolean dragging;
    private Listener listener;

    public RoiOverlayView(Context c) { this(c, null); }

    public RoiOverlayView(Context c, AttributeSet a) {
        super(c, a);
        float d = getResources().getDisplayMetrics().density;
        mapPaint.setStyle(Paint.Style.STROKE); mapPaint.setStrokeWidth(2.5f * d); mapPaint.setColor(Color.rgb(33, 150, 243));
        kwPaint.setStyle(Paint.Style.STROKE); kwPaint.setStrokeWidth(2.5f * d); kwPaint.setColor(Color.rgb(255, 152, 0));
        ocrPaint.setStyle(Paint.Style.STROKE); ocrPaint.setStrokeWidth(1f * d); ocrPaint.setColor(Color.argb(200, 76, 175, 80));
        dragPaint.setStyle(Paint.Style.FILL); dragPaint.setColor(Color.argb(70, 255, 255, 255));
        textPaint.setColor(Color.WHITE); textPaint.setTextSize(13 * d);
        textPaint.setShadowLayer(3, 1, 1, Color.BLACK);
        hintBg.setColor(Color.argb(170, 0, 0, 0));
    }

    public void setListener(Listener l) { listener = l; }

    public void setRois(NormRect map, NormRect kw) { mapRoi = map; kwRoi = kw; invalidate(); }

    public void setOcrBoxes(List<NormRect> boxes) { ocrBoxes = boxes; invalidate(); }

    public void setImageAspect(float aspect) {
        if (aspect > 0.1f && Math.abs(aspect - imageAspect) > 0.001f) { imageAspect = aspect; invalidate(); }
    }

    public void startSelect(int m) { mode = m; dragging = false; invalidate(); }

    public int getMode() { return mode; }

    /** 影像在本 View 中實際顯示的區域 */
    private RectF content() {
        float w = getWidth(), h = getHeight();
        float cw, ch;
        if (w / h > imageAspect) { ch = h; cw = h * imageAspect; } else { cw = w; ch = w / imageAspect; }
        float l = (w - cw) / 2f, t = (h - ch) / 2f;
        return new RectF(l, t, l + cw, t + ch);
    }

    private RectF toView(NormRect r, RectF c) {
        return new RectF(c.left + r.left * c.width(), c.top + r.top * c.height(),
                c.left + r.right * c.width(), c.top + r.bottom * c.height());
    }

    @Override
    protected void onDraw(Canvas cv) {
        super.onDraw(cv);
        RectF c = content();
        for (NormRect b : ocrBoxes) cv.drawRect(toView(b, c), ocrPaint);
        RectF kr = toView(kwRoi, c);
        cv.drawRect(kr, kwPaint);
        cv.drawText("關鍵字區", kr.left + 6, kr.bottom - 8, textPaint);
        RectF mr = toView(mapRoi, c);
        cv.drawRect(mr, mapPaint);
        cv.drawText("地圖區", mr.left + 6, mr.top + textPaint.getTextSize() + 4, textPaint);

        if (mode != MODE_NONE) {
            String hint = mode == MODE_MAP ? "用手指拖曳框出【右上角地圖＋地名】區域" : "用手指拖曳框出【要找關鍵字】的區域（例如畫面中間）";
            float tw = textPaint.measureText(hint);
            cv.drawRect(0, 0, tw + 24, textPaint.getTextSize() + 20, hintBg);
            cv.drawText(hint, 12, textPaint.getTextSize() + 8, textPaint);
            if (dragging) cv.drawRect(Math.min(sx, ex), Math.min(sy, ey), Math.max(sx, ex), Math.max(sy, ey), dragPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (mode == MODE_NONE) return false;   // 讓點擊穿透給預覽（點擊對焦）
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                sx = ex = e.getX(); sy = ey = e.getY(); dragging = true;
                break;
            case MotionEvent.ACTION_MOVE:
                ex = e.getX(); ey = e.getY();
                break;
            case MotionEvent.ACTION_UP:
                ex = e.getX(); ey = e.getY(); dragging = false;
                RectF c = content();
                NormRect r = new NormRect((sx - c.left) / c.width(), (sy - c.top) / c.height(),
                        (ex - c.left) / c.width(), (ey - c.top) / c.height());
                int m = mode;
                if (r.width() > 0.02f && r.height() > 0.02f) {
                    if (m == MODE_MAP) mapRoi = r; else kwRoi = r;
                    mode = MODE_NONE;
                    if (listener != null) listener.onRoiSelected(m, r);
                }
                performClick();
                break;
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                break;
        }
        invalidate();
        return true;
    }

    @Override
    public boolean performClick() { return super.performClick(); }
}
