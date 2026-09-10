package com.collection.admin.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AiCallFailureClassifierTest {

    @Test
    void busyAndNoAnswerAreNotFailed() {
        assertThat(AiCallFailureClassifier.isFailed(false, "BUSY", null)).isFalse();
        assertThat(AiCallFailureClassifier.isFailed(false, "NO_ANSWER", null)).isFalse();
        assertThat(AiCallFailureClassifier.bucket(false, "BUSY", null)).isEqualTo("BUSY");
        assertThat(AiCallFailureClassifier.bucket(false, "NO_ANSWER", null)).isEqualTo("NO_ANSWER");
    }

    @Test
    void calleeDeclineAndInvalidNumberAreNotFailed() {
        assertThat(AiCallFailureClassifier.isFailed(false, "DECLINE", null)).isFalse();
        assertThat(AiCallFailureClassifier.isFailed(false, "INVALID_NUMBER", null)).isFalse();
        assertThat(AiCallFailureClassifier.isFailed(false, "TEMP_UNAVAILABLE", null)).isFalse();
        assertThat(AiCallFailureClassifier.bucket(false, "DECLINE", null))
                .isEqualTo("CALLEE_OTHER");
        assertThat(AiCallFailureClassifier.resolveFailureClass(null, "DECLINE"))
                .isEqualTo("callee");
    }

    @Test
    void webhookCalleeClassWinsOverReason() {
        assertThat(AiCallFailureClassifier.isFailed(false, "callee", "FORBIDDEN", null)).isFalse();
        assertThat(AiCallFailureClassifier.isFailed(false, "network", "DECLINE", null)).isTrue();
    }

    @Test
    void mediaNegotiationIsFailed() {
        assertThat(AiCallFailureClassifier.isFailed(false, "MEDIA_NEGOTIATION_FAILED", null))
                .isTrue();
        assertThat(AiCallFailureClassifier.bucket(false, "MEDIA_NEGOTIATION_FAILED", null))
                .isEqualTo("FAILED");
    }

    @Test
    void unknownReasonDefaultsToOurSystemFailed() {
        assertThat(AiCallFailureClassifier.resolveFailureClass(null, "BRAND_NEW_CODE"))
                .isEqualTo("our_system");
        assertThat(AiCallFailureClassifier.isFailed(false, "BRAND_NEW_CODE", null)).isTrue();
    }

    @Test
    void answeredAndMailboxAreNotFailed() {
        assertThat(AiCallFailureClassifier.isFailed(true, null, "NORMAL")).isFalse();
        assertThat(AiCallFailureClassifier.isFailed(true, null, "VOICEMAIL")).isFalse();
        assertThat(AiCallFailureClassifier.bucket(true, null, "VOICEMAIL")).isEqualTo("MAILBOX");
        assertThat(AiCallFailureClassifier.bucket(false, "FORBIDDEN", "CALL_SCREENING"))
                .isEqualTo("MAILBOX");
        assertThat(AiCallFailureClassifier.bucket(true, null, "NORMAL")).isEqualTo("OTHER");
        assertThat(AiCallFailureClassifier.bucket(true, null, "NORMAL", "human", Boolean.TRUE))
                .isEqualTo("ANSWERED");
        assertThat(AiCallFailureClassifier.bucket(true, null, "NORMAL", "human", Boolean.FALSE))
                .isEqualTo("HUMAN_NO_SPEECH");
        assertThat(AiCallFailureClassifier.bucket(true, null, "VOICEMAIL"))
                .isNotEqualTo("ANSWERED");
    }

    @Test
    void connectKindSplitsHumanMailboxUnrecognized() {
        assertThat(AiCallFailureClassifier.connectKind(null, "NORMAL")).isEqualTo("unrecognized");
        assertThat(AiCallFailureClassifier.connectKind("human", "NORMAL")).isEqualTo("human");
        assertThat(AiCallFailureClassifier.connectKind("voicemail", null)).isEqualTo("mailbox");
        assertThat(AiCallFailureClassifier.connectKindSql("s", "unrecognized"))
                .contains("party<>'human'");
    }

    @Test
    void emptyReasonIsNotFailed() {
        assertThat(AiCallFailureClassifier.isFailed(false, null, null)).isFalse();
        assertThat(AiCallFailureClassifier.bucket(false, null, null)).isEqualTo("OTHER");
    }
}
