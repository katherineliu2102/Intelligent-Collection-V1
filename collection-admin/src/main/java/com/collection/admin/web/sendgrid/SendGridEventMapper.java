package com.collection.admin.web.sendgrid;

import com.collection.common.enums.ContactResult;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.lang3.StringUtils;

/**
 * SendGrid 事件 → timeline 结果与抑制动作。对应 Email 对接说明 §5。
 *
 * <p><b>与 §5 表格的一处有意偏离</b>：{@code spamreport} 与 {@code unsubscribe} 只写抑制名单，不改写 timeline 结果。
 * 这两类事件发生在投递成功 <i>之后</i>（收件人先收到才能投诉或退订），把该行改成 REJECTED 会 让「已送达」这个事实消失，bounce
 * 率与送达率取证随之失真。需要停发的语义由抑制名单承担， 不必靠伪造投递结论来表达。
 */
final class SendGridEventMapper {

    private SendGridEventMapper() {}

    static final String REASON_HARD_BOUNCE = "HARD_BOUNCE";
    static final String REASON_DROPPED = "DROPPED";
    static final String REASON_SPAM_REPORT = "SPAM_REPORT";
    static final String REASON_UNSUBSCRIBE = "UNSUBSCRIBE";

    /** 一条事件要落的动作；两项都为空即忽略该事件。 */
    static final class Action {
        static final Action IGNORE = new Action(null, false, null);

        private final ContactResult result;
        private final boolean override;
        private final String suppressionReason;

        private Action(ContactResult result, boolean override, String suppressionReason) {
            this.result = result;
            this.override = override;
            this.suppressionReason = suppressionReason;
        }

        ContactResult getResult() {
            return result;
        }

        /** true：无条件改写（未送达类事实）；false：仅沿升级链前进。 */
        boolean isOverride() {
            return override;
        }

        String getSuppressionReason() {
            return suppressionReason;
        }

        boolean isIgnored() {
            return result == null && suppressionReason == null;
        }
    }

    private static Action upgrade(ContactResult result) {
        return new Action(result, false, null);
    }

    private static Action notDelivered(ContactResult result, String suppressionReason) {
        return new Action(result, true, suppressionReason);
    }

    private static Action suppressOnly(String suppressionReason) {
        return new Action(null, false, suppressionReason);
    }

    /** @param event 单条事件对象；{@code custom_args} 已由 SendGrid 平铺为顶层字段 */
    static Action map(JsonNode event) {
        String type = event.path("event").asText("");
        switch (type) {
            case "delivered":
                return upgrade(ContactResult.DELIVERED);
            case "open":
                return upgrade(ContactResult.READ);
            case "click":
                return upgrade(ContactResult.CLICKED);
            case "bounce":
                // SendGrid 用 type 区分：bounce=对方永久拒收（地址不存在等），blocked=对方临时拒收
                // （灰名单、容量）。只有前者才该进抑制名单，把 blocked 也拉黑会永久失去一个有效地址。
                return "blocked".equals(event.path("type").asText(""))
                        ? notDelivered(ContactResult.REJECTED, null)
                        : notDelivered(ContactResult.REJECTED, REASON_HARD_BOUNCE);
            case "dropped":
                // SendGrid 在自家 suppression list 命中时直接 drop，不出网。此时本地必须同步记一笔，
                // 否则每个里程碑都会重复撞上同一次 drop。
                return notDelivered(ContactResult.REJECTED, REASON_DROPPED);
            case "spamreport":
                return suppressOnly(REASON_SPAM_REPORT);
            case "unsubscribe":
            case "group_unsubscribe":
                return suppressOnly(REASON_UNSUBSCRIBE);
            default:
                // processed / deferred / group_resubscribe 及未知事件：不改状态。
                return Action.IGNORE;
        }
    }

    /** 供应商给出的原因原文，截断到抑制表的列宽。 */
    static String detail(JsonNode event) {
        String reason = event.path("reason").asText("");
        if (StringUtils.isBlank(reason)) {
            reason = event.path("type").asText("");
        }
        if (StringUtils.isBlank(reason)) {
            return null;
        }
        return reason.length() > 512 ? reason.substring(0, 512) : reason;
    }
}
