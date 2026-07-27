package com.collection.admin.web.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ScriptTemplateValidatorTest {

    @Test
    void smsAcceptsAllowedVarsWithinLimit() {
        String body =
                "MOCASA Collections: {name}, overdue {dpd} days. Settle PHP {amount}. Pay: {repaymentUrl}";
        ScriptTemplateValidator.ValidationResult r =
                ScriptTemplateValidator.validate("SMS", null, body);
        assertFalse(r.hasErrors(), () -> r.getErrors().toString());
    }

    @Test
    void smsRejectsUnknownPlaceholder() {
        String body = "Hi {userName}, pay {amount} via {repaymentUrl}";
        ScriptTemplateValidator.ValidationResult r =
                ScriptTemplateValidator.validate("SMS", null, body);
        assertTrue(r.hasErrors());
        assertTrue(
                r.getErrors().stream().anyMatch(e -> "UNKNOWN_VAR".equals(e.getCode())),
                () -> r.getErrors().toString());
    }

    @Test
    void smsRequiresAmountAndRepaymentUrl() {
        ScriptTemplateValidator.ValidationResult r =
                ScriptTemplateValidator.validate("SMS", null, "Hello {name}, please pay soon.");
        assertTrue(r.hasErrors());
        assertTrue(r.getErrors().stream().anyMatch(e -> "MISSING_VAR".equals(e.getCode())));
    }

    @Test
    void smsRejectsOverlongBody() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 310; i++) {
            sb.append('a');
        }
        sb.append(" {amount} {repaymentUrl}");
        ScriptTemplateValidator.ValidationResult r =
                ScriptTemplateValidator.validate("SMS", null, sb.toString());
        assertTrue(r.hasErrors());
        assertTrue(r.getErrors().stream().anyMatch(e -> "MAX_LENGTH".equals(e.getCode())));
    }

    @Test
    void pushRejectsBothEmpty() {
        ScriptTemplateValidator.ValidationResult r =
                ScriptTemplateValidator.validate("PUSH", "  ", "");
        assertTrue(r.hasErrors());
        assertEquals("REQUIRED", r.getErrors().get(0).getCode());
    }

    @Test
    void pushAcceptsShortTitleAndBody() {
        ScriptTemplateValidator.ValidationResult r =
                ScriptTemplateValidator.validate(
                        "PUSH", "Overdue: PHP {amount}", "{name}, tap to settle.");
        assertFalse(r.hasErrors(), () -> r.getErrors().toString());
        assertTrue(r.getPreview().containsKey("bodyRendered"));
    }
}
