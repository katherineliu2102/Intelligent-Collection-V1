package com.collection.admin.dashboard;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI Call 波次键。{@code batch_id} 形如 {@code mocasa-YYYYMMDD-HHMM-N}，波次 = {@code YYYYMMDD-HHMM}（设计文档
 * §5.1.6 B）。
 */
public final class WaveKey {

    private static final Pattern BATCH = Pattern.compile("(\\d{8})-(\\d{4})(?:-\\d+)?");

    private WaveKey() {}

    /** @return {@code YYYYMMDD-HHMM}，无法解析时 {@code null} */
    public static String fromBatchId(String batchId) {
        if (batchId == null || batchId.isEmpty()) {
            return null;
        }
        Matcher m = BATCH.matcher(batchId);
        if (!m.find()) {
            return null;
        }
        return m.group(1) + "-" + m.group(2);
    }

    /**
     * 展示用 {@code 2026-09-08  09:15}；无法解析时返回原文；{@code UNKNOWN} 显示未分波次。
     */
    public static String formatDisplay(String waveKey) {
        if (waveKey == null || waveKey.isEmpty()) {
            return null;
        }
        if ("UNKNOWN".equals(waveKey)) {
            return "未分波次";
        }
        if (waveKey.length() == 13 && waveKey.charAt(8) == '-') {
            String day = waveKey.substring(0, 8);
            String hhmm = waveKey.substring(9);
            if (hhmm.length() == 4) {
                return day.substring(0, 4)
                        + "-"
                        + day.substring(4, 6)
                        + "-"
                        + day.substring(6, 8)
                        + "  "
                        + hhmm.substring(0, 2)
                        + ":"
                        + hhmm.substring(2);
            }
        }
        return waveKey;
    }

    /** 抑制键用的槽位 {@code HHMM}（如 {@code 0915}）。 */
    public static String slotHhmm(String waveKey) {
        if (waveKey == null || waveKey.length() < 13) {
            return null;
        }
        int dash = waveKey.indexOf('-');
        if (dash < 0 || dash + 1 >= waveKey.length()) {
            return null;
        }
        return waveKey.substring(dash + 1);
    }

    /** 从 PHT 触发时刻归到 09:15 / 14:30 槽（不硬编码档位，只认当日两波钟点）。 */
    public static String slotHhmmFromTrigger(int hour, int minute) {
        if (hour == 9 || (hour == 8 && minute >= 45) || (hour == 10 && minute <= 15)) {
            return "0915";
        }
        if ((hour == 14 && minute >= 15) || (hour == 15 && minute <= 15)) {
            return "1430";
        }
        return null;
    }
}
