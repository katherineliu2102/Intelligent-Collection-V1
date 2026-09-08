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
    void mediaNegotiationIsFailed() {
        assertThat(AiCallFailureClassifier.isFailed(false, "MEDIA_NEGOTIATION_FAILED", null))
                .isTrue();
        assertThat(AiCallFailureClassifier.bucket(false, "MEDIA_NEGOTIATION_FAILED", null))
                .isEqualTo("FAILED");
    }

    @Test
    void answeredAndSnrAreNotFailed() {
        assertThat(AiCallFailureClassifier.isFailed(true, null, "NORMAL")).isFalse();
        assertThat(AiCallFailureClassifier.isFailed(true, null, "VOICEMAIL")).isFalse();
        assertThat(AiCallFailureClassifier.bucket(true, null, "VOICEMAIL")).isEqualTo("SNR");
        assertThat(AiCallFailureClassifier.bucket(false, "FORBIDDEN", "CALL_SCREENING"))
                .isEqualTo("SNR");
        assertThat(AiCallFailureClassifier.bucket(true, null, "NORMAL")).isEqualTo("ANSWERED");
        assertThat(AiCallFailureClassifier.bucket(true, null, "VOICEMAIL")).isNotEqualTo("ANSWERED");
    }

    @Test
    void emptyReasonIsNotFailed() {
        assertThat(AiCallFailureClassifier.isFailed(false, null, null)).isFalse();
        assertThat(AiCallFailureClassifier.bucket(false, null, null)).isEqualTo("OTHER");
    }
}
