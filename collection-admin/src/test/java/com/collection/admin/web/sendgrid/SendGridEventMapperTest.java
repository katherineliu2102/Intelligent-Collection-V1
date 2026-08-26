package com.collection.admin.web.sendgrid;

import static org.assertj.core.api.Assertions.assertThat;

import com.collection.common.enums.ContactResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** Email 对接说明 §5 的事件映射，含与该表有意偏离的两项。 */
class SendGridEventMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private JsonNode event(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private SendGridEventMapper.Action map(String json) {
        return SendGridEventMapper.map(event(json));
    }

    @Test
    void mapsUpgradeChainEvents() {
        assertThat(map("{\"event\":\"delivered\"}").getResult()).isEqualTo(ContactResult.DELIVERED);
        assertThat(map("{\"event\":\"open\"}").getResult()).isEqualTo(ContactResult.READ);
        assertThat(map("{\"event\":\"click\"}").getResult()).isEqualTo(ContactResult.CLICKED);
        assertThat(map("{\"event\":\"open\"}").isOverride()).isFalse();
        assertThat(map("{\"event\":\"open\"}").getSuppressionReason()).isNull();
    }

    @Test
    void hardBounceOverridesResultAndSuppresses() {
        SendGridEventMapper.Action action =
                map("{\"event\":\"bounce\",\"type\":\"bounce\",\"reason\":\"550 no such user\"}");

        assertThat(action.getResult()).isEqualTo(ContactResult.REJECTED);
        assertThat(action.isOverride()).isTrue();
        assertThat(action.getSuppressionReason()).isEqualTo("HARD_BOUNCE");
    }

    @Test
    void blockedBounceOverridesResultWithoutSuppressing() {
        // blocked 是对方临时拒收；拉黑会永久失去一个有效地址。
        SendGridEventMapper.Action action =
                map("{\"event\":\"bounce\",\"type\":\"blocked\",\"reason\":\"421 try later\"}");

        assertThat(action.getResult()).isEqualTo(ContactResult.REJECTED);
        assertThat(action.isOverride()).isTrue();
        assertThat(action.getSuppressionReason()).isNull();
    }

    @Test
    void droppedOverridesResultAndSuppresses() {
        SendGridEventMapper.Action action = map("{\"event\":\"dropped\"}");

        assertThat(action.getResult()).isEqualTo(ContactResult.REJECTED);
        assertThat(action.isOverride()).isTrue();
        assertThat(action.getSuppressionReason()).isEqualTo("DROPPED");
    }

    @Test
    void postDeliveryComplaintsSuppressWithoutRewritingResult() {
        // 投诉与退订发生在送达之后，改写成 REJECTED 会让「已送达」消失，送达率取证随之失真。
        SendGridEventMapper.Action spam = map("{\"event\":\"spamreport\"}");
        assertThat(spam.getResult()).isNull();
        assertThat(spam.getSuppressionReason()).isEqualTo("SPAM_REPORT");

        SendGridEventMapper.Action unsub = map("{\"event\":\"unsubscribe\"}");
        assertThat(unsub.getResult()).isNull();
        assertThat(unsub.getSuppressionReason()).isEqualTo("UNSUBSCRIBE");

        assertThat(map("{\"event\":\"group_unsubscribe\"}").getSuppressionReason())
                .isEqualTo("UNSUBSCRIBE");
    }

    @Test
    void ignoresNonTerminalAndUnknownEvents() {
        assertThat(map("{\"event\":\"processed\"}").isIgnored()).isTrue();
        assertThat(map("{\"event\":\"deferred\"}").isIgnored()).isTrue();
        assertThat(map("{\"event\":\"group_resubscribe\"}").isIgnored()).isTrue();
        assertThat(map("{\"event\":\"something_new\"}").isIgnored()).isTrue();
        assertThat(map("{}").isIgnored()).isTrue();
    }

    @Test
    void detailFallsBackFromReasonToType() {
        assertThat(SendGridEventMapper.detail(event("{\"reason\":\"550 nope\"}")))
                .isEqualTo("550 nope");
        assertThat(SendGridEventMapper.detail(event("{\"type\":\"bounce\"}"))).isEqualTo("bounce");
        assertThat(SendGridEventMapper.detail(event("{}"))).isNull();
    }
}
