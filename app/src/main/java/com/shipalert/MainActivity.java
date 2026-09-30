package com.shipalert;

import android.Manifest;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.util.Size;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.Preview;
import androidx.camera.core.ResolutionInfo;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import com.shipalert.core.NormRect;
import com.shipalert.core.OcrLine;
import com.shipalert.core.ScreenParser;
import com.shipalert.core.ShipMonitor;
import com.shipalert.core.Signature;
import com.shipalert.core.TimeFmt;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements ShipMonitor.Sink {
    private static final String TAG = "ShipAlert";
    private static final int REQ_CAMERA = 1;
    private static final long BG_ALERT_DELAY_MS = 60_000L;
    private static final int MAX_LOG_LINES = 120;

    private PreviewView previewView;
    private RoiOverlayView overlay;
    private Button btnStart, btnDim, btnEdit;
    private TextView tvStatus, tvLog, tvZoom;
    private ScrollView logScroll;
    private SeekBar seekZoom;

    private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService netExecutor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private TextRecognizer recognizer;
    private Camera camera;
    private String boundResolution;
    private volatile AppConfig cfg;
    private ShipMonitor monitor;

    private volatile boolean monitoring;
    private volatile boolean busy;
    private volatile boolean oneShot;
    private volatile boolean autoLocate;
    private volatile long lastProcessAt;
    private long monitorStartAt;

    private final Object debugLock = new Object();
    private Bitmap debugBitmap;

    private boolean dimmed;
    private final LinkedList<String> logLines = new LinkedList<>();

    private final Runnable backgroundAlert = new Runnable() {
        @Override public void run() {
            if (monitoring) {
                push("⚠️ 船務提醒監控已中斷",
                        "App 已離開前台超過 1 分鐘（被切到背景、鎖屏或來電），攝像頭已停止。\n請回到 App 以恢復監控。");
            }
        }
    };

    // =========================== 生命週期 ===========================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        PreferenceManager.setDefaultValues(this, R.xml.prefs, false);
        applyOrientation(AppConfig.load(this).orientation);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.previewView);
        overlay = findViewById(R.id.overlay);
        btnStart = findViewById(R.id.btnStart);
        btnDim = findViewById(R.id.btnDim);
        btnEdit = findViewById(R.id.btnEdit);
        tvStatus = findViewById(R.id.tvStatus);
        tvLog = findViewById(R.id.tvLog);
        tvZoom = findViewById(R.id.tvZoom);
        logScroll = findViewById(R.id.logScroll);
        seekZoom = findViewById(R.id.seekZoom);

        previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        previewView.setScaleType(PreviewView.ScaleType.FIT_CENTER);

        recognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
        cfg = AppConfig.load(this);
        monitor = new ShipMonitor(this, cfg.monitor);

        overlay.setImageAspect(cfg.isPortrait() ? 9f / 16f : 16f / 9f);
        overlay.setRois(cfg.mapRoi, cfg.kwRoi);
        overlay.setListener((box, r) -> {
            AppConfig.saveRoi(this, box == RoiOverlayView.BOX_MAP ? AppConfig.KEY_MAP_ROI : AppConfig.KEY_KW_ROI,
                    cfg.isPortrait(), r);
            reloadConfig();
        });

        btnStart.setOnClickListener(v -> toggleMonitoring());
        findViewById(R.id.btnToken).setOnClickListener(v -> showTokenDialog());
        findViewById(R.id.btnTest).setOnClickListener(v -> {
            if (cfg.token.isEmpty()) { showTokenDialog(); return; }
            push("✅ 船務提醒測試", "這是一條測試推送，收到代表 PushPlus 設定正確。\n時間：" + now("yyyy-MM-dd HH:mm:ss"));
        });
        findViewById(R.id.btnAuto).setOnClickListener(v -> {
            autoLocate = true;
            log("🔍 正在自動定位地圖…（請確保遊戲畫面右上角有地圖和「XX据点所属」字樣）");
        });
        btnEdit.setOnClickListener(v -> setEditing(!overlay.isEditing()));
        btnEdit.setOnLongClickListener(v -> {
            AppConfig.resetRois(this, cfg.isPortrait());
            reloadConfig();
            log("已將" + (cfg.isPortrait() ? "豎屏" : "橫屏") + "框位重設為預設值");
            return true;
        });
        findViewById(R.id.btnOnce).setOnClickListener(v -> {
            oneShot = true;
            log("正在識別一次（全畫面）…");
        });
        findViewById(R.id.btnDebug).setOnClickListener(v -> showDebugDialog());
        findViewById(R.id.btnOrient).setOnClickListener(v -> showOrientationDialog());
        btnDim.setOnClickListener(v -> toggleDim());
        findViewById(R.id.btnSettings).setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));

        seekZoom.setProgress(Math.round(cfg.zoom * 100));
        tvZoom.setText("變焦 " + seekZoom.getProgress() + "%");
        seekZoom.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                tvZoom.setText("變焦 " + p + "%");
                if (camera != null) camera.getCameraControl().setLinearZoom(p / 100f);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) { AppConfig.saveZoom(MainActivity.this, s.getProgress() / 100f); }
        });

        // 點擊預覽畫面對焦
        previewView.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_UP && camera != null) {
                MeteringPoint p = previewView.getMeteringPointFactory().createPoint(e.getX(), e.getY());
                camera.getCameraControl().startFocusAndMetering(new FocusMeteringAction.Builder(p).build());
                v.performClick();
            }
            return true;
        });

        log("App 啟動，Android " + Build.VERSION.RELEASE + "（API " + Build.VERSION.SDK_INT + "）");
        log("拍攝方向：" + orientationLabel(cfg.orientation));
        updateStatus(null);

        if (!hasCameraPermission()) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }
        if (cfg.token.isEmpty()) {
            log("⚠ 尚未設定 PushPlus token");
            if (savedInstanceState == null) showTokenDialog();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        main.removeCallbacks(backgroundAlert);
    }

    @Override
    protected void onResume() {
        super.onResume();
        reloadConfig();
        if (applyOrientation(cfg.orientation)) return;   // 方向變了，Activity 會重建
        if (hasCameraPermission() && (camera == null || !cfg.resolution.equals(boundResolution))) bindCamera();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (monitoring && !isChangingConfigurations()) main.postDelayed(backgroundAlert, BG_ALERT_DELAY_MS);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        main.removeCallbacks(backgroundAlert);
        recognizer.close();
        analysisExecutor.shutdown();
        netExecutor.shutdown();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA) {
            if (hasCameraPermission()) bindCamera();
            else log("❌ 未授予攝像頭權限，App 無法工作");
        }
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void reloadConfig() {
        cfg = AppConfig.load(this);
        monitor.setSettings(cfg.monitor);
        overlay.setRois(cfg.mapRoi, cfg.kwRoi);
    }

    // =========================== 方向 / Token / 框位 ===========================

    private static int orientationConst(String o) {
        if ("portrait".equals(o)) return ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        if ("reverse_landscape".equals(o)) return ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
        if ("reverse_portrait".equals(o)) return ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT;
        return ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
    }

    private String orientationLabel(String o) {
        String[] v = getResources().getStringArray(R.array.orient_values);
        String[] e = getResources().getStringArray(R.array.orient_entries);
        for (int i = 0; i < v.length; i++) if (v[i].equals(o)) return e[i];
        return o;
    }

    /** @return true 表示方向有改變（Activity 將重建） */
    private boolean applyOrientation(String o) {
        int want = orientationConst(o);
        if (getRequestedOrientation() != want) {
            setRequestedOrientation(want);
            return true;
        }
        return false;
    }

    private void showOrientationDialog() {
        if (monitoring) {
            Toast.makeText(this, "請先停止監控再切換方向", Toast.LENGTH_SHORT).show();
            return;
        }
        final String[] values = getResources().getStringArray(R.array.orient_values);
        int cur = 0;
        for (int i = 0; i < values.length; i++) if (values[i].equals(cfg.orientation)) cur = i;
        new AlertDialog.Builder(this)
                .setTitle("拍攝方向（橫屏、豎屏各自保存一套框位）")
                .setSingleChoiceItems(R.array.orient_entries, cur, (d, which) -> {
                    d.dismiss();
                    AppConfig.putString(this, AppConfig.KEY_ORIENTATION, values[which]);
                    reloadConfig();
                    applyOrientation(values[which]);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showTokenDialog() {
        final EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        et.setHint("貼上 32 位 token");
        et.setText(cfg.token);
        et.setSelection(et.getText().length());
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        android.widget.FrameLayout box = new android.widget.FrameLayout(this);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(et);
        new AlertDialog.Builder(this)
                .setTitle("設定 PushPlus Token")
                .setMessage("1. 電腦或手機瀏覽器打開 www.pushplus.plus，用微信掃碼登入\n"
                        + "2. 點上方「發送消息 → 一對一消息」\n"
                        + "3. 複製頁面上的 token，貼到下面")
                .setView(box)
                .setPositiveButton("保存並測試", (d, w) -> {
                    String t = et.getText().toString().replaceAll("\\s+", "");
                    AppConfig.putString(this, AppConfig.KEY_TOKEN, t);
                    reloadConfig();
                    if (t.isEmpty()) {
                        log("⚠ token 已清空");
                    } else {
                        log("token 已保存（" + t.length() + " 位），發送測試推送…");
                        push("✅ 船務提醒測試", "token 設定成功！之後的提醒都會推送到這裡。\n時間：" + now("yyyy-MM-dd HH:mm:ss"));
                    }
                })
                .setNegativeButton("稍後", null)
                .show();
    }

    private void setEditing(boolean on) {
        if (on && monitoring) {
            Toast.makeText(this, "監控中也可調整，調整後立即生效", Toast.LENGTH_SHORT).show();
        }
        overlay.setEditing(on);
        btnEdit.setText(on ? "✔ 完成調整" : "調整框位");
        if (on) log("調整框位：點框選中、拖動移動、拖白色圓點縮放、框外拖動重畫。長按此按鈕可恢復預設");
        else log("框位已保存｜地圖區 " + cfg.mapRoi + "｜關鍵字區 " + cfg.kwRoi);
    }

    // =========================== 攝像頭 ===========================

    private void bindCamera() {
        final ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();
                provider.unbindAll();

                Size target = cfg.resolutionSize();
                int rotation = getWindowManager().getDefaultDisplay().getRotation();

                ResolutionSelector previewSel = new ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                        .build();
                ResolutionSelector analysisSel = new ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(new ResolutionStrategy(target,
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                        .build();

                Preview preview = new Preview.Builder()
                        .setResolutionSelector(previewSel)
                        .setTargetRotation(rotation)
                        .build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setResolutionSelector(analysisSel)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .setTargetRotation(rotation)
                        .build();
                analysis.setAnalyzer(analysisExecutor, this::analyze);

                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis);
                camera.getCameraControl().setLinearZoom(seekZoom.getProgress() / 100f);
                boundResolution = cfg.resolution;
                String got = "";
                ResolutionInfo ri = analysis.getResolutionInfo();
                if (ri != null) {
                    Size rs = ri.getResolution();
                    boolean swap = ri.getRotationDegrees() % 180 != 0;
                    float w = swap ? rs.getHeight() : rs.getWidth(), h = swap ? rs.getWidth() : rs.getHeight();
                    overlay.setImageAspect(w / h);
                    got = "，實際 " + (int) w + "x" + (int) h;
                }
                log("攝像頭已啟動（目標 " + cfg.resolution + got + "）");
            } catch (Exception e) {
                log("❌ 攝像頭啟動失敗：" + e);
                Log.e(TAG, "bindCamera", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    /** 每幀都會進來，但只按設定間隔真正處理一次。 */
    private void analyze(@NonNull ImageProxy image) {
        final long now = System.currentTimeMillis();
        boolean due = monitoring && now - lastProcessAt >= cfg.intervalMs;
        if (busy || !(due || oneShot || autoLocate)) {
            image.close();
            return;
        }
        busy = true;
        lastProcessAt = now;
        final boolean isOneShot = oneShot;
        final boolean isAutoLocate = autoLocate;
        oneShot = false;
        autoLocate = false;

        Bitmap frame;
        try {
            Bitmap bmp = image.toBitmap();
            int rot = image.getImageInfo().getRotationDegrees();
            if (rot != 0) {
                Matrix m = new Matrix();
                m.postRotate(rot);
                Bitmap r = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
                if (r != bmp) bmp.recycle();
                bmp = r;
            }
            frame = bmp;
        } catch (Throwable t) {
            log("❌ 取圖失敗：" + t);
            busy = false;
            return;
        } finally {
            image.close();
        }

        final Bitmap f = frame;
        final AppConfig c = cfg;
        final float[] sig = mapSignature(f, c.mapRoi);

        // 只對「地圖區 ∪ 關鍵字區」做 OCR：更快、更省電，也不會被框外的聊天文字干擾
        final Rect crop = (isOneShot || isAutoLocate) ? null : ocrCrop(f.getWidth(), f.getHeight(), c);
        final Bitmap ocrBmp;
        try {
            ocrBmp = crop == null ? f : Bitmap.createBitmap(f, crop.left, crop.top, crop.width(), crop.height());
        } catch (Throwable t) {
            log("❌ 裁切失敗：" + t);
            f.recycle();
            busy = false;
            return;
        }
        try {
            recognizer.process(InputImage.fromBitmap(ocrBmp, 0))
                    .addOnSuccessListener(analysisExecutor, text -> handleText(now, f, crop, text, sig, c, isOneShot, isAutoLocate))
                    .addOnFailureListener(analysisExecutor, e -> log("❌ OCR 失敗：" + e.getMessage()))
                    .addOnCompleteListener(analysisExecutor, t -> {
                        if (ocrBmp != f) ocrBmp.recycle();
                        f.recycle();
                        busy = false;
                    });
        } catch (Throwable t) {
            log("❌ OCR 異常：" + t);
            if (ocrBmp != f) ocrBmp.recycle();
            f.recycle();
            busy = false;
        }
    }

    /** 兩個框的聯集（外擴 3%）；聯集已接近全畫面時回傳 null（=不裁切）。 */
    private static Rect ocrCrop(int w, int h, AppConfig c) {
        float pad = 0.03f;
        float l = Math.max(0f, Math.min(c.mapRoi.left, c.kwRoi.left) - pad);
        float t = Math.max(0f, Math.min(c.mapRoi.top, c.kwRoi.top) - pad);
        float r = Math.min(1f, Math.max(c.mapRoi.right, c.kwRoi.right) + pad);
        float b = Math.min(1f, Math.max(c.mapRoi.bottom, c.kwRoi.bottom) + pad);
        if ((r - l) * (b - t) > 0.85f) return null;
        Rect rc = new Rect(Math.round(l * w), Math.round(t * h), Math.round(r * w), Math.round(b * h));
        if (rc.width() < 32 || rc.height() < 32) return null;
        return rc;
    }

    private void handleText(long now, Bitmap frame, Rect crop, Text text, float[] sig, AppConfig c,
                            boolean isOneShot, boolean isAutoLocate) {
        final int w = frame.getWidth(), h = frame.getHeight();
        final int ox = crop == null ? 0 : crop.left, oy = crop == null ? 0 : crop.top;
        final List<OcrLine> lines = new ArrayList<>();
        final List<NormRect> boxes = new ArrayList<>();
        for (Text.TextBlock b : text.getTextBlocks()) {
            for (Text.Line l : b.getLines()) {
                Rect r = l.getBoundingBox();
                if (r == null) continue;
                OcrLine ol = new OcrLine(l.getText(), (r.left + ox) / (float) w, (r.top + oy) / (float) h,
                        (r.right + ox) / (float) w, (r.bottom + oy) / (float) h);
                lines.add(ol);
                boxes.add(new NormRect(ol.left, ol.top, ol.right, ol.bottom));
            }
        }

        if (isAutoLocate) {
            final NormRect found = ScreenParser.autoLocateMap(lines, w / (float) h);
            main.post(() -> {
                if (found == null) {
                    log("❌ 自動定位失敗：畫面中找不到「XX据点所属」。請確認地圖在畫面內、字夠清楚（可拉近變焦或點畫面對焦），或手動「調整框位」");
                } else {
                    AppConfig.saveRoi(this, AppConfig.KEY_MAP_ROI, cfg.isPortrait(), found);
                    reloadConfig();
                    log("✅ 已自動定位地圖區：" + found + "，位置識別為：" + ScreenParser.extractLocation(lines, found));
                }
                overlay.setOcrBoxes(boxes);
            });
            buildDebugBitmap(frame, lines, c, monitor.inspect(lines, sig));
            return;
        }

        final ShipMonitor.FrameResult res = monitoring ? monitor.onFrame(now, lines, sig) : monitor.inspect(lines, sig);
        buildDebugBitmap(frame, lines, c, res);

        main.post(() -> {
            overlay.setImageAspect(w / (float) h);
            overlay.setOcrBoxes(boxes);
            updateStatus(res);
            if (isOneShot) {
                StringBuilder sb = new StringBuilder();
                sb.append("識別結果 ").append(w).append("x").append(h).append("，共 ").append(lines.size()).append(" 行\n");
                sb.append("位置：").append(res.location == null ? "（未識別到）" : res.location).append("\n");
                sb.append("命中關鍵字：").append(res.hits.isEmpty() ? "無" : res.hits.toString()).append("\n");
                int shown = 0;
                for (OcrLine l : lines) {
                    if (shown++ >= 15) { sb.append("…\n"); break; }
                    sb.append(" · ").append(l.text).append("\n");
                }
                log(sb.toString().trim());
                showDebugDialog();
            }
        });
    }

    /** 從地圖區生成 32x32 灰度指紋，用於和上一次畫面比較。 */
    private static float[] mapSignature(Bitmap frame, NormRect roi) {
        try {
            int w = frame.getWidth(), h = frame.getHeight();
            int x = Math.round(roi.left * w), y = Math.round(roi.top * h);
            int cw = Math.min(w - x, Math.round(roi.width() * w));
            int ch = Math.min(h - y, Math.round(roi.height() * h));
            if (cw < 8 || ch < 8) return null;
            Bitmap crop = Bitmap.createBitmap(frame, x, y, cw, ch);
            Bitmap small = Bitmap.createScaledBitmap(crop, Signature.SIZE, Signature.SIZE, true);
            int[] px = new int[Signature.SIZE * Signature.SIZE];
            small.getPixels(px, 0, Signature.SIZE, 0, 0, Signature.SIZE, Signature.SIZE);
            if (small != crop && small != frame) small.recycle();
            if (crop != frame) crop.recycle();
            return Signature.fromArgb(px);
        } catch (Throwable t) {
            return null;
        }
    }

    private void buildDebugBitmap(Bitmap frame, List<OcrLine> lines, AppConfig c, ShipMonitor.FrameResult res) {
        try {
            float scale = Math.min(1f, 1280f / frame.getWidth());
            int dw = Math.round(frame.getWidth() * scale), dh = Math.round(frame.getHeight() * scale);
            Bitmap out = Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(out);
            cv.drawBitmap(frame, null, new RectF(0, 0, dw, dh), new Paint(Paint.FILTER_BITMAP_FLAG));
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2);
            p.setColor(Color.GREEN);
            for (OcrLine l : lines) cv.drawRect(l.left * dw, l.top * dh, l.right * dw, l.bottom * dh, p);
            p.setStrokeWidth(4);
            p.setColor(Color.rgb(255, 152, 0));
            cv.drawRect(c.kwRoi.left * dw, c.kwRoi.top * dh, c.kwRoi.right * dw, c.kwRoi.bottom * dh, p);
            p.setColor(Color.rgb(33, 150, 243));
            cv.drawRect(c.mapRoi.left * dw, c.mapRoi.top * dh, c.mapRoi.right * dw, c.mapRoi.bottom * dh, p);
            Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
            tp.setColor(Color.YELLOW);
            tp.setTextSize(28);
            tp.setShadowLayer(4, 2, 2, Color.BLACK);
            cv.drawText(now("HH:mm:ss") + "  位置：" + (res.location == null ? "?" : res.location)
                    + "  命中：" + (res.hits.isEmpty() ? "無" : res.hits.toString()), 12, dh - 16, tp);
            synchronized (debugLock) {
                if (debugBitmap != null) debugBitmap.recycle();
                debugBitmap = out;
            }
        } catch (Throwable ignore) {
            // 老手機記憶體不足時放棄除錯圖
        }
    }

    private void showDebugDialog() {
        Bitmap copy = null;
        synchronized (debugLock) {
            if (debugBitmap != null) copy = debugBitmap.copy(Bitmap.Config.ARGB_8888, false);
        }
        if (copy == null) {
            Toast.makeText(this, "尚無識別結果，請先點「識別一次」", Toast.LENGTH_SHORT).show();
            return;
        }
        final Bitmap shown = copy;
        ImageView iv = new ImageView(this);
        iv.setAdjustViewBounds(true);
        iv.setImageBitmap(shown);
        new AlertDialog.Builder(this)
                .setTitle("上次識別畫面（綠=文字 藍=地圖區 橙=關鍵字區）")
                .setView(iv)
                .setPositiveButton("關閉", null)
                .setOnDismissListener(d -> shown.recycle())
                .show();
    }

    // =========================== 控制 ===========================

    private void toggleMonitoring() {
        if (!monitoring) {
            if (!hasCameraPermission()) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
                return;
            }
            if (cfg.token.isEmpty()) Toast.makeText(this, "尚未設定 PushPlus token，將只記錄不推送", Toast.LENGTH_LONG).show();
            monitor.reset();
            lastProcessAt = 0;
            monitorStartAt = System.currentTimeMillis();
            monitoring = true;
            btnStart.setText("■ 停止監控");
            log("▶ 開始監控：每 " + cfg.intervalMs / 1000 + " 秒識別一次，規則 " + cfg.monitor.rules.size() + " 條");
        } else {
            monitoring = false;
            btnStart.setText("▶ 開始監控");
            log("■ 已停止監控（運行了 " + TimeFmt.duration(System.currentTimeMillis() - monitorStartAt) + "）");
        }
        updateStatus(null);
    }

    private void toggleDim() {
        dimmed = !dimmed;
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = dimmed ? 0.01f : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        getWindow().setAttributes(lp);
        btnDim.setText(dimmed ? "恢復亮度" : "螢幕調暗（省電）");
    }

    private void updateStatus(ShipMonitor.FrameResult r) {
        StringBuilder sb = new StringBuilder();
        sb.append(monitoring ? "● 監控中" : "○ 未監控");
        if (r != null) {
            sb.append("\n位置：").append(r.knownLocation == null ? "?" : r.knownLocation);
            sb.append("\n文字：").append(r.totalChars).append(" 字").append(r.textOk ? "" : "（過少）");
            sb.append("\n命中：").append(r.hits.isEmpty() ? "無" : r.hits.toString());
            if (r.diff >= 0 && r.diff < 1e6)
                sb.append(String.format(Locale.US, "\n差異：%.1f / 閾值 %.1f", r.diff, cfg.monitor.diffThreshold));
            if (r.stillMs >= 0) sb.append("\n靜止：").append(TimeFmt.duration(r.stillMs));
            sb.append("\n更新：").append(now("HH:mm:ss"));
        }
        tvStatus.setText(sb.toString());
    }

    // =========================== Sink（推送 / 日誌） ===========================

    @Override
    public void push(final String title, final String content) {
        log("📤 " + content.replace('\n', ' '));
        final AppConfig c = cfg;
        if (c.token.isEmpty()) {
            log("⚠ 未設定 PushPlus token，略過推送");
            return;
        }
        netExecutor.execute(() -> {
            String err = PushPlusClient.send(c.token, c.topic, title,
                    content + "\n\n—— 船務提醒 " + now("MM-dd HH:mm:ss"));
            log(err == null ? "✅ 推送成功" : "❌ 推送失敗：" + err);
        });
    }

    @Override
    public void log(final String msg) {
        Log.i(TAG, msg);
        final String line = now("HH:mm:ss") + " " + msg;
        main.post(() -> {
            logLines.add(line);
            while (logLines.size() > MAX_LOG_LINES) logLines.removeFirst();
            StringBuilder sb = new StringBuilder();
            for (String s : logLines) sb.append(s).append('\n');
            tvLog.setText(sb);
            logScroll.post(() -> logScroll.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }

    private static String now(String pattern) {
        return new SimpleDateFormat(pattern, Locale.getDefault()).format(new Date());
    }
}
