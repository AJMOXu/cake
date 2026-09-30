package com.shipalert.core;

public final class TimeFmt {
    private TimeFmt() {}

    public static String duration(long ms) {
        long sec = Math.max(0, ms / 1000);
        long h = sec / 3600, m = (sec % 3600) / 60, s = sec % 60;
        if (h > 0) return h + " 小時 " + m + " 分鐘";
        if (m > 0) return m + " 分鐘";
        return s + " 秒";
    }
}
