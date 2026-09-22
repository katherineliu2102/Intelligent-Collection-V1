package com.collection.admin.dashboard;

/**
 * AI Call 未接通分桶。FAILED 仅 {@code network} + {@code our_system}（手册 §9.5）； callee（含 BUSY / NO_ANSWER /
 * DECLINE / INVALID_NUMBER / TEMP_UNAVAILABLE）不计 FAILED。
 *
 * <p>无 {@code failure_class} 的旧行按细码回退；未知新细码归 {@code our_system}。
 */
public final class AiCallFailureClassifier {

    public static final String CLASS_CALLEE = "callee";
    public static final String CLASS_NETWORK = "network";
    public static final String CLASS_OUR_SYSTEM = "our_system";

    private AiCallFailureClassifier() {}

    public static boolean isSnr(String lineReason) {
        return "VOICEMAIL".equals(lineReason) || "CALL_SCREENING".equals(lineReason);
    }

    public static boolean isSnrParty(String party) {
        return "voicemail".equalsIgnoreCase(party) || "call_screening".equalsIgnoreCase(party);
    }

    public static boolean isMailbox(String party, String lineReason) {
        return isSnrParty(party) || isSnr(lineReason);
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
     * 供应商/我方失败：{@code failure_class} 为 network 或 our_system。接通与信箱不计。
     *
     * <p>无 class 时按细码回退；空细码不计 FAILED。
     */
    public static boolean isFailed(boolean answered, String finalFailureReason, String lineReason) {
        return isFailed(answered, null, finalFailureReason, lineReason);
    }

    public static boolean isFailed(
            boolean answered, String failureClass, String finalFailureReason, String lineReason) {
        if (answered) {
            return false;
        }
        if (isSnr(lineReason)) {
            return false;
        }
        String cls = resolveFailureClass(failureClass, finalFailureReason);
        return CLASS_NETWORK.equals(cls) || CLASS_OUR_SYSTEM.equals(cls);
    }

    /** callee 且不是 BUSY/NO_ANSWER（拒接、空号、临时不可用等）。 */
    public static boolean isCalleeOther(
            boolean answered, String failureClass, String finalFailureReason, String lineReason) {
        return isCalleeOther(answered, failureClass, finalFailureReason, lineReason, null);
    }

    public static boolean isCalleeOther(
            boolean answered,
            String failureClass,
            String finalFailureReason,
            String lineReason,
            String party) {
        if (answered || isMailbox(party, lineReason)) {
            return false;
        }
        if (isBusy(finalFailureReason) || isNoAnswer(finalFailureReason)) {
            return false;
        }
        return CLASS_CALLEE.equals(resolveFailureClass(failureClass, finalFailureReason));
    }

    /** 有 webhook class 则信任；否则按手册细码表回退。未知非空细码 → our_system。空细码 → null。 */
    public static String resolveFailureClass(String failureClass, String finalFailureReason) {
        if (failureClass != null && !failureClass.isEmpty()) {
            String c = failureClass.trim().toLowerCase();
            if (CLASS_CALLEE.equals(c) || CLASS_NETWORK.equals(c) || CLASS_OUR_SYSTEM.equals(c)) {
                return c;
            }
        }
        if (finalFailureReason == null || finalFailureReason.isEmpty()) {
            return null;
        }
        switch (finalFailureReason) {
            case "BUSY":
            case "NO_ANSWER":
            case "DECLINE":
            case "INVALID_NUMBER":
            case "TEMP_UNAVAILABLE":
                return CLASS_CALLEE;
            case "MEDIA_NEGOTIATION_FAILED":
            case "SIP_SERVER_ERROR":
            case "FAILED":
            case "FORBIDDEN":
            case "REQUEST_TIMEOUT":
                return CLASS_NETWORK;
            default:
                return CLASS_OUR_SYSTEM;
        }
    }

    public static String connectKind(String party, String lineReason) {
        if (isMailbox(party, lineReason)) {
            return "mailbox";
        }
        if ("human".equalsIgnoreCase(party)) {
            return "human";
        }
        return "unrecognized";
    }

    public static String connectKindLabel(String kind) {
        if ("human".equals(kind)) {
            return "真人";
        }
        if ("mailbox".equals(kind)) {
            return "信箱/筛选";
        }
        if ("unrecognized".equals(kind)) {
            return "未识别对方";
        }
        return "未识别对方";
    }

    /** 明细筛选：human / mailbox / unrecognized。其它值忽略。 */
    public static String connectKindSql(String alias, String kind) {
        String p = alias == null || alias.isEmpty() ? "" : alias + ".";
        String mailbox =
                "("
                        + p
                        + "party IN ('voicemail','call_screening') OR "
                        + p
                        + "line_reason IN ('VOICEMAIL','CALL_SCREENING'))";
        if ("mailbox".equals(kind)) {
            return " AND " + mailbox;
        }
        if ("human".equals(kind)) {
            return " AND " + p + "party='human'";
        }
        if ("unrecognized".equals(kind)) {
            return " AND NOT "
                    + mailbox
                    + " AND ("
                    + p
                    + "party IS NULL OR "
                    + p
                    + "party='' OR "
                    + p
                    + "party<>'human')";
        }
        return "";
    }

    public static String classLabel(String failureClass) {
        if (CLASS_CALLEE.equals(failureClass)) {
            return "对方侧";
        }
        if (CLASS_NETWORK.equals(failureClass)) {
            return "线路/运营商";
        }
        if (CLASS_OUR_SYSTEM.equals(failureClass)) {
            return "我方";
        }
        return "未归类";
    }

    public static String bucket(boolean answered, String finalFailureReason, String lineReason) {
        return bucket(answered, finalFailureReason, lineReason, null, null);
    }

    /**
     * 结构分桶。ANSWERED = {@code party=human} 且有效沟通；信箱/筛选 = MAILBOX；真人未开口 = HUMAN_NO_SPEECH。无 party 时不把
     * {@code was_answered} 记作 ANSWERED。
     */
    public static String bucket(
            boolean answered,
            String finalFailureReason,
            String lineReason,
            String party,
            Boolean effectiveConversation) {
        return bucket(answered, null, finalFailureReason, lineReason, party, effectiveConversation);
    }

    public static String bucket(
            boolean answered,
            String failureClass,
            String finalFailureReason,
            String lineReason,
            String party,
            Boolean effectiveConversation) {
        if (isMailbox(party, lineReason)) {
            return "MAILBOX";
        }
        if ("human".equalsIgnoreCase(party)) {
            if (Boolean.TRUE.equals(effectiveConversation)) {
                return "ANSWERED";
            }
            return "HUMAN_NO_SPEECH";
        }
        if (isBusy(finalFailureReason)) {
            return "BUSY";
        }
        if (isNoAnswer(finalFailureReason)) {
            return "NO_ANSWER";
        }
        if (isCalleeOther(answered, failureClass, finalFailureReason, lineReason)) {
            return "CALLEE_OTHER";
        }
        if (isFailed(answered, failureClass, finalFailureReason, lineReason)) {
            return "FAILED";
        }
        return "OTHER";
    }
}
