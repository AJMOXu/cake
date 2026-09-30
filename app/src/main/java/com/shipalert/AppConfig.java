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

    public String token;
    public String topic;
    public long intervalMs;
    public String resolution;
    public float zoom;
    public NormRect mapRoi;
    public NormRect kwRoi;
    public ShipMonitor.Settings monitor;

    public static AppConfig load(Context ctx) {
        SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(ctx);
        AppConfig c = new AppConfig();
        c.token = p.getString("pp_token", "").trim();
        c.topic = p.getString("pp_topic", "").trim();
        c.intervalMs = Math.max(3, getInt(p, "interval_sec", 10)) * 1000L;
        c.resolution = p.getString("resolution", "1920x1080");
        c.zoom = p.getFloat(KEY_ZOOM, 0f);
        c.mapRoi = NormRect.parse(p.getString(KEY_MAP_ROI, null), NormRect.DEFAULT_MAP);
        c.kwRoi = NormRect.parse(p.getString(KEY_KW_ROI, null), NormRect.FULL);

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

    public static void saveRoi(Context ctx, String key, NormRect r) {
        PreferenceManager.getDefaultSharedPreferences(ctx).edit().putString(key, r.serialize()).apply();
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
