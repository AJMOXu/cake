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
 * 疊加在預覽畫面上：顯示 地圖區(藍) / 關鍵字區(橙) / OCR 文字框(綠)。
 *
 * 編輯模式下：
 *  - 點框內 = 選中該框；按住框內拖動 = 移動
 *  - 拖動選中框的四個角 = 調整大小
 *  - 在框外空白處拖動 = 重新畫選中的框
 * 非編輯模式時觸控穿透給預覽（點擊對焦）。
 */
public class RoiOverlayView extends View {
    public static final int BOX_MAP = 1, BOX_KW = 2;

    public interface Listener { void onRoiChanged(int box, NormRect r); }

    private static final int ACT_NONE = 0, ACT_MOVE = 1, ACT_RESIZE = 2, ACT_DRAW = 3;

    private final Paint mapPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint kwPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ocrPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintBg = new Paint();
    private final float density;

    private NormRect mapRoi = NormRect.DEFAULT_MAP, kwRoi = NormRect.FULL;
    private List<NormRect> ocrBoxes = new ArrayList<>();
    private float imageAspect = 16f / 9f;

    private boolean editing;
    private int selected = BOX_MAP;
    private int action = ACT_NONE;
    private int corner;               // 0=左上 1=右上 2=右下 3=左下
    private float downX, downY;       // 正規化座標
    private NormRect startRect, workRect;
    private Listener listener;

    public RoiOverlayView(Context c) { this(c, null); }

    public RoiOverlayView(Context c, AttributeSet a) {
        super(c, a);
        density = getResources().getDisplayMetrics().density;
        mapPaint.setStyle(Paint.Style.STROKE); mapPaint.setColor(Color.rgb(33, 150, 243));
        kwPaint.setStyle(Paint.Style.STROKE); kwPaint.setColor(Color.rgb(255, 152, 0));
        ocrPaint.setStyle(Paint.Style.STROKE); ocrPaint.setStrokeWidth(1f * density); ocrPaint.setColor(Color.argb(200, 76, 175, 80));
        fillPaint.setStyle(Paint.Style.FILL);
        handlePaint.setStyle(Paint.Style.FILL); handlePaint.setColor(Color.WHITE);
        textPaint.setColor(Color.WHITE); textPaint.setTextSize(13 * density);
        textPaint.setShadowLayer(3, 1, 1, Color.BLACK);
        hintBg.setColor(Color.argb(180, 0, 0, 0));
    }

    public void setListener(Listener l) { listener = l; }

    public void setRois(NormRect map, NormRect kw) { mapRoi = map; kwRoi = kw; invalidate(); }

    public void setOcrBoxes(List<NormRect> boxes) { ocrBoxes = boxes; invalidate(); }

    public void setImageAspect(float aspect) {
        if (aspect > 0.1f && Math.abs(aspect - imageAspect) > 0.001f) { imageAspect = aspect; invalidate(); }
    }

    public void setEditing(boolean e) { editing = e; action = ACT_NONE; invalidate(); }

    public boolean isEditing() { return editing; }

    public void select(int box) { selected = box; invalidate(); }

    // ------------------------------------------------------------------ 座標

    /** 影像在本 View 中實際顯示的區域（預覽為 FIT_CENTER） */
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

    private NormRect rect(int box) { return box == BOX_MAP ? mapRoi : kwRoi; }

    private void setRect(int box, NormRect r) { if (box == BOX_MAP) mapRoi = r; else kwRoi = r; }

    // ------------------------------------------------------------------ 繪製

    @Override
    protected void onDraw(Canvas cv) {
        super.onDraw(cv);
        RectF c = content();
        for (NormRect b : ocrBoxes) cv.drawRect(toView(b, c), ocrPaint);

        drawBox(cv, c, BOX_KW, "關鍵字區", kwPaint);
        drawBox(cv, c, BOX_MAP, "地圖區", mapPaint);

        if (editing) {
            String hint = "調整框位：點框內選中｜拖框內移動｜拖白角縮放｜框外拖動重畫   目前：" + (selected == BOX_MAP ? "地圖區(藍)" : "關鍵字區(橙)");
            float max = getWidth() - 24;
            List<String> rows = wrap(hint, max);
            float lh = textPaint.getTextSize() + 6;
            cv.drawRect(0, 0, getWidth(), rows.size() * lh + 12, hintBg);
            for (int i = 0; i < rows.size(); i++) cv.drawText(rows.get(i), 12, (i + 1) * lh, textPaint);
        }
    }

    private void drawBox(Canvas cv, RectF c, int box, String label, Paint stroke) {
        boolean sel = editing && selected == box;
        RectF r = toView(rect(box), c);
        stroke.setStrokeWidth((sel ? 3.5f : 2f) * density);
        if (sel) {
            fillPaint.setColor((stroke.getColor() & 0x00FFFFFF) | 0x33000000);
            cv.drawRect(r, fillPaint);
        }
        cv.drawRect(r, stroke);
        float ty = box == BOX_MAP ? r.top + textPaint.getTextSize() + 4 : r.bottom - 8;
        cv.drawText(label, r.left + 6, ty, textPaint);
        if (sel) {
            float hr = 7 * density;
            cv.drawCircle(r.left, r.top, hr, handlePaint);
            cv.drawCircle(r.right, r.top, hr, handlePaint);
            cv.drawCircle(r.right, r.bottom, hr, handlePaint);
            cv.drawCircle(r.left, r.bottom, hr, handlePaint);
        }
    }

    private List<String> wrap(String s, float max) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            cur.append(s.charAt(i));
            if (textPaint.measureText(cur.toString()) > max) {
                cur.setLength(cur.length() - 1);
                out.add(cur.toString());
                cur.setLength(0);
                cur.append(s.charAt(i));
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    // ------------------------------------------------------------------ 觸控

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!editing) return false;   // 讓點擊穿透給預覽（點擊對焦）
        RectF c = content();
        float nx = (e.getX() - c.left) / c.width();
        float ny = (e.getY() - c.top) / c.height();

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                downX = nx; downY = ny;
                int hc = hitCorner(e.getX(), e.getY(), toView(rect(selected), c));
                if (hc >= 0) {
                    action = ACT_RESIZE; corner = hc;
                } else if (mapRoi.contains(nx, ny)) {          // 地圖區較小、在上層，優先
                    selected = BOX_MAP; action = ACT_MOVE;
                } else if (kwRoi.contains(nx, ny)) {
                    selected = BOX_KW; action = ACT_MOVE;
                } else {
                    action = ACT_DRAW;
                }
                startRect = rect(selected);
                workRect = startRect;
                break;
            }
            case MotionEvent.ACTION_MOVE:
                workRect = compute(nx, ny);
                if (workRect != null) setRect(selected, workRect);
                break;
            case MotionEvent.ACTION_UP:
                workRect = compute(nx, ny);
                if (workRect != null && workRect.width() > 0.02f && workRect.height() > 0.02f) {
                    setRect(selected, workRect);
                    if (listener != null && !workRect.serialize().equals(startRect.serialize()))
                        listener.onRoiChanged(selected, workRect);
                } else {
                    setRect(selected, startRect);
                }
                action = ACT_NONE;
                performClick();
                break;
            case MotionEvent.ACTION_CANCEL:
                setRect(selected, startRect);
                action = ACT_NONE;
                break;
        }
        invalidate();
        return true;
    }

    private int hitCorner(float x, float y, RectF r) {
        float tol = 26 * density;
        float[][] pts = {{r.left, r.top}, {r.right, r.top}, {r.right, r.bottom}, {r.left, r.bottom}};
        int best = -1;
        float bestD = tol * tol;
        for (int i = 0; i < 4; i++) {
            float dx = x - pts[i][0], dy = y - pts[i][1], d = dx * dx + dy * dy;
            if (d <= bestD) { bestD = d; best = i; }
        }
        return best;
    }

    private NormRect compute(float nx, float ny) {
        NormRect s = startRect;
        switch (action) {
            case ACT_MOVE: {
                float dx = nx - downX, dy = ny - downY;
                float l = clampShift(s.left + dx, s.width()), t = clampShift(s.top + dy, s.height());
                return new NormRect(l, t, l + s.width(), t + s.height());
            }
            case ACT_RESIZE:
                switch (corner) {
                    case 0: return new NormRect(nx, ny, s.right, s.bottom);
                    case 1: return new NormRect(s.left, ny, nx, s.bottom);
                    case 2: return new NormRect(s.left, s.top, nx, ny);
                    default: return new NormRect(nx, s.top, s.right, ny);
                }
            case ACT_DRAW:
                return new NormRect(downX, downY, nx, ny);
            default:
                return null;
        }
    }

    private static float clampShift(float pos, float size) {
        return Math.max(0f, Math.min(1f - size, pos));
    }

    @Override
    public boolean performClick() { return super.performClick(); }
}
