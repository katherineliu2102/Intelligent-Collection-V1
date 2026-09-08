package com.collection.admin.dashboard;

/** AI Call 未接通分桶（设计文档 §5.1.6 D）。BUSY / NO_ANSWER 严禁计入 FAILED 率。 */
public final class AiCallFailureClassifier {

    private AiCallFailureClassifier() {}

    public static boolean isSnr(String lineReason) {
        return "VOICEMAIL".equals(lineReason) || "CALL_SCREENING".equals(lineReason);
    }

    /** 客户侧忙，不算 FAILED。 */
    public static boolean isBusy(String finalFailureReason) {
        return "BUSY".equals(finalFailureReason);
    }

    /** 客户侧未接，不算 FAILED。 */
    public static boolean isNoAnswer(String finalFailureReason) {
        return "NO_ANSWER".equals(finalFailureReason);
    }

    /**
     * 供应商侧失败：媒体协商 + 其他 FAILED。未接通且不是 BUSY/NO_ANSWER 即计入。
     *
     * <p>接通（含 SNR）不进 FAILED。
     */
    public static boolean isFailed(boolean answered, String finalFailureReason, String lineReason) {
        if (answered) {
            return false;
        }
        if (isSnr(lineReason)) {
            return false;
        }
        if (isBusy(finalFailureReason) || isNoAnswer(finalFailureReason)) {
            return false;
        }
        return finalFailureReason != null && !finalFailureReason.isEmpty();
    }

    public static String bucket(boolean answered, String finalFailureReason, String lineReason) {
        if (isSnr(lineReason)) {
            return "SNR";
        }
        if (answered) {
            return "ANSWERED";
        }
        if (isBusy(finalFailureReason)) {
            return "BUSY";
        }
        if (isNoAnswer(finalFailureReason)) {
            return "NO_ANSWER";
        }
        if (isFailed(false, finalFailureReason, lineReason)) {
            return "FAILED";
        }
        return "OTHER";
    }
}
