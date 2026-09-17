package com.collection.admin.aicall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.collection.admin.aicall.AiCallScriptParser.Parsed;
import org.junit.jupiter.api.Test;

class AiCallScriptParserTest {

    @Test
    void conversationHistoryMarksBorrowerTurn() {
        Parsed p =
                AiCallScriptParser.parse(
                        "{\"conversation_history\":["
                                + "{\"role\":\"assistant\",\"content\":\"Si Maria po ito.\"},"
                                + "{\"role\":\"user\",\"content\":\"Opo.\"}]}");
        assertThat(p.turnCount).isEqualTo(2);
        assertThat(p.hasBorrowerTurn).isTrue();
        assertThat(p.transcriptText).contains("[assistant]").contains("[user]").contains("Opo");
    }

    @Test
    void assistantOnlyIsNotBorrower() {
        Parsed p =
                AiCallScriptParser.parse(
                        "{\"conversation_history\":["
                                + "{\"role\":\"assistant\",\"text\":\"Hello\"},"
                                + "{\"speaker\":\"agent\",\"message\":\"Follow up\"}]}");
        assertThat(p.turnCount).isEqualTo(2);
        assertThat(p.hasBorrowerTurn).isFalse();
    }

    @Test
    void messagesKeyAndBlankRoleNotBorrower() {
        Parsed p =
                AiCallScriptParser.parse(
                        "{\"messages\":[{\"content\":\"only assistant leftover\"}]}");
        assertThat(p.turnCount).isEqualTo(1);
        assertThat(p.hasBorrowerTurn).isFalse();
    }

    @Test
    void emptyArrayIsEmptyStatusShape() {
        Parsed p = AiCallScriptParser.parse("{\"conversation_history\":[]}");
        assertThat(p.turnCount).isEqualTo(0);
        assertThat(p.hasBorrowerTurn).isFalse();
        assertThat(p.transcriptText).isNull();
    }

    @Test
    void invalidJsonThrows() {
        assertThatThrownBy(() -> AiCallScriptParser.parse("{not json"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void isBorrowerTreatsUnknownRoleAsBorrower() {
        assertThat(AiCallScriptParser.isBorrower("customer")).isTrue();
        assertThat(AiCallScriptParser.isBorrower("ASSISTANT")).isFalse();
        assertThat(AiCallScriptParser.isBorrower("")).isFalse();
        assertThat(AiCallScriptParser.isBorrower(null)).isFalse();
    }
}
