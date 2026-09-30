package com.shipalert;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Size;

import androidx.preference.PreferenceManager;

import com.shipalert.core.KeywordRule;
import com.shipalert.core.NormRect;
import com.shipalert.core.ShipMonitor;

/** 從 SharedPreferences 讀取所有設定。 */
public class AppConfig {
    public static final String KEY_MAP_ROI = "map_roi";
    public static final String KEY_KW_ROI = "kw_roi";
    public static final String KEY_ZOOM = "zoom";
    public static final String KEY_TOKEN = "pp_token";
    public static final String KEY_ORIENTATION = "orientation";

    /** 豎屏拍攝時的預設框（電腦螢幕大約佔畫面中間 1/3） */
    public static final NormRect DEFAULT_MAP_PORT = new NormRect(0.68f, 0.32f, 1.0f, 0.47f);
    public static final NormRect DEFAULT_KW_PORT = new NormRect(0.0f, 0.28f, 1.0f, 0.72f);

    public String token;
    public String topic;
    public long intervalMs;
    public String resolution;
    public float zoom;
    public String orientation;
    public NormRect mapRoi;
    public NormRect kwRoi;
    public ShipMonitor.Settings monitor;

    public static AppConfig load(Context ctx) {
        SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(ctx);
        AppConfig c = new AppConfig();
        c.token = p.getString(KEY_TOKEN, "").trim();
        c.orientation = p.getString(KEY_ORIENTATION, "landscape");
        c.topic = p.getString("pp_topic", "").trim();
        c.intervalMs = Math.max(3, getInt(p, "interval_sec", 10)) * 1000L;
        c.resolution = p.getString("resolution", "1920x1080");
        c.zoom = p.getFloat(KEY_ZOOM, 0f);
        boolean port = c.isPortrait();
        c.mapRoi = NormRect.parse(p.getString(roiKey(KEY_MAP_ROI, port), null), port ? DEFAULT_MAP_PORT : NormRect.DEFAULT_MAP);
        c.kwRoi = NormRect.parse(p.getString(roiKey(KEY_KW_ROI, port), null), port ? DEFAULT_KW_PORT : NormRect.FULL);

        ShipMonitor.Settings s = new ShipMonitor.Settings();
        s.rules = KeywordRule.parseAll(p.getString("rules", ctx.getString(R.string.default_rules)));
        s.mapRoi = c.mapRoi;
        s.kwRoi = c.kwRoi;
        s.confirmFrames = Math.max(1, getInt(p, "confirm_frames", 2));
        s.cooldownMs = Math.max(0, getInt(p, "cooldown_min", 10)) * 60_000L;
        s.stayEnabled = p.getBoolean("stay_enabled", true);
        s.stayThresholdMs = Math.max(1, getInt(p, "stay_min", 10)) * 60_000L;
        s.stayRepeatMs = Math.max(0, getInt(p, "stay_repeat_min", 30)) * 60_000L;
        s.diffThreshold = getFloat(p, "diff_threshold", 8f);
        s.leaveNotify = p.getBoolean("leave_notify", true);
        s.noTextMs = Math.max(0, getInt(p, "notext_min", 5)) * 60_000L;
        c.monitor = s;
        return c;
    }

    public Size resolutionSize() {
        try {
            String[] a = resolution.split("x");
            return new Size(Integer.parseInt(a[0].trim()), Integer.parseInt(a[1].trim()));
        } catch (Exception e) {
            return new Size(1920, 1080);
        }
    }

    public boolean isPortrait() { return orientation != null && orientation.contains("portrait"); }

    /** 橫屏、豎屏各自保存一套框 */
    public static String roiKey(String base, boolean portrait) { return base + (portrait ? "_port" : "_land"); }

    public static void saveRoi(Context ctx, String base, boolean portrait, NormRect r) {
        PreferenceManager.getDefaultSharedPreferences(ctx).edit().putString(roiKey(base, portrait), r.serialize()).apply();
    }

    public static void resetRois(Context ctx, boolean portrait) {
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
                .remove(roiKey(KEY_MAP_ROI, portrait)).remove(roiKey(KEY_KW_ROI, portrait)).apply();
    }

    public static void putString(Context ctx, String key, String v) {
        PreferenceManager.getDefaultSharedPreferences(ctx).edit().putString(key, v).apply();
    }

    public static void saveZoom(Context ctx, float z) {
        PreferenceManager.getDefaultSharedPreferences(ctx).edit().putFloat(KEY_ZOOM, z).apply();
    }

    private static int getInt(SharedPreferences p, String k, int def) {
        try { return Integer.parseInt(p.getString(k, String.valueOf(def)).trim()); }
        catch (Exception e) { return def; }
    }

    private static float getFloat(SharedPreferences p, String k, float def) {
        try { return Float.parseFloat(p.getString(k, String.valueOf(def)).trim()); }
        catch (Exception e) { return def; }
    }
}
