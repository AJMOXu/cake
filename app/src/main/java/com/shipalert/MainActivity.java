package com.shipalert;

import android.Manifest;
import android.content.Intent;
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
import android.util.Log;
import android.util.Size;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.Button;
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
    private Button btnStart, btnDim;
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
        setContentView(R.layout.activity_main);
        PreferenceManager.setDefaultValues(this, R.xml.prefs, false);

        previewView = findViewById(R.id.previewView);
        overlay = findViewById(R.id.overlay);
        btnStart = findViewById(R.id.btnStart);
        btnDim = findViewById(R.id.btnDim);
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

        overlay.setRois(cfg.mapRoi, cfg.kwRoi);
        overlay.setListener((mode, r) -> {
            AppConfig.saveRoi(this, mode == RoiOverlayView.MODE_MAP ? AppConfig.KEY_MAP_ROI : AppConfig.KEY_KW_ROI, r);
            reloadConfig();
            log((mode == RoiOverlayView.MODE_MAP ? "地圖區" : "關鍵字區") + "已更新：" + r);
        });

        btnStart.setOnClickListener(v -> toggleMonitoring());
        findViewById(R.id.btnOnce).setOnClickListener(v -> {
            oneShot = true;
            log("正在識別一次…");
        });
        findViewById(R.id.btnMapRoi).setOnClickListener(v -> overlay.startSelect(RoiOverlayView.MODE_MAP));
        findViewById(R.id.btnKwRoi).setOnClickListener(v -> overlay.startSelect(RoiOverlayView.MODE_KW));
        findViewById(R.id.btnKwRoi).setOnLongClickListener(v -> {
            AppConfig.saveRoi(this, AppConfig.KEY_KW_ROI, NormRect.FULL);
            reloadConfig();
            log("關鍵字區已重設為全畫面");
            return true;
        });
        findViewById(R.id.btnDebug).setOnClickListener(v -> showDebugDialog());
        findViewById(R.id.btnTest).setOnClickListener(v -> push("✅ 船務提醒測試",
                "這是一條測試推送，收到代表 PushPlus 設定正確。\n時間：" + now("yyyy-MM-dd HH:mm:ss")));
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
        if (cfg.token.isEmpty()) log("⚠ 尚未設定 PushPlus token，請到「設定」填寫");
        updateStatus(null);

        if (!hasCameraPermission()) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
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
                log("攝像頭已啟動（目標解析度 " + cfg.resolution + "）");
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
        if (busy || !(due || oneShot)) {
            image.close();
            return;
        }
        busy = true;
        lastProcessAt = now;
        final boolean isOneShot = oneShot;
        oneShot = false;

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
        try {
            recognizer.process(InputImage.fromBitmap(f, 0))
                    .addOnSuccessListener(analysisExecutor, text -> handleText(now, f, text, sig, c, isOneShot))
                    .addOnFailureListener(analysisExecutor, e -> log("❌ OCR 失敗：" + e.getMessage()))
                    .addOnCompleteListener(analysisExecutor, t -> {
                        f.recycle();
                        busy = false;
                    });
        } catch (Throwable t) {
            log("❌ OCR 異常：" + t);
            f.recycle();
            busy = false;
        }
    }

    private void handleText(long now, Bitmap frame, Text text, float[] sig, AppConfig c, boolean isOneShot) {
        final int w = frame.getWidth(), h = frame.getHeight();
        final List<OcrLine> lines = new ArrayList<>();
        final List<NormRect> boxes = new ArrayList<>();
        for (Text.TextBlock b : text.getTextBlocks()) {
            for (Text.Line l : b.getLines()) {
                Rect r = l.getBoundingBox();
                if (r == null) continue;
                OcrLine ol = new OcrLine(l.getText(), r.left / (float) w, r.top / (float) h,
                        r.right / (float) w, r.bottom / (float) h);
                lines.add(ol);
                boxes.add(new NormRect(ol.left, ol.top, ol.right, ol.bottom));
            }
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
