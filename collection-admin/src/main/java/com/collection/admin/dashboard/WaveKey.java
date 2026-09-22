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

    /** {@code YYYY-MM-DD}；无法解析时 {@code null}。 */
    public static String dateIso(String waveKey) {
        if (waveKey == null || waveKey.length() < 8 || "UNKNOWN".equals(waveKey)) {
            return null;
        }
        String day = waveKey.substring(0, 8);
        if (!day.chars().allMatch(Character::isDigit)) {
            return null;
        }
        return day.substring(0, 4) + "-" + day.substring(4, 6) + "-" + day.substring(6, 8);
    }

    /** 展示用 {@code 2026-09-08 09:15}；无法解析时返回原文；{@code UNKNOWN} 显示未分波次。 */
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

    /**
     * 从 PHT 触发时刻归到五波槽（互不重叠，约 ±15 分钟）。口径见迭代文档 2026-09-11。
     *
     * <p>0915=09:00–09:29；1130=11:15–11:44；1430=14:15–14:44；1615=16:00–16:29；1840=18:25–18:54。
     */
    public static String slotHhmmFromTrigger(int hour, int minute) {
        int minutes = hour * 60 + minute;
        if (minutes >= 9 * 60 && minutes <= 9 * 60 + 29) {
            return "0915";
        }
        if (minutes >= 11 * 60 + 15 && minutes <= 11 * 60 + 44) {
            return "1130";
        }
        if (minutes >= 14 * 60 + 15 && minutes <= 14 * 60 + 44) {
            return "1430";
        }
        if (minutes >= 16 * 60 && minutes <= 16 * 60 + 29) {
            return "1615";
        }
        if (minutes >= 18 * 60 + 25 && minutes <= 18 * 60 + 54) {
            return "1840";
        }
        return null;
    }
}
