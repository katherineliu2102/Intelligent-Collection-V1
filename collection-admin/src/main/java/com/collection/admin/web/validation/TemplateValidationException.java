package com.collection.admin.web.validation;

import java.util.List;

/** 文案模板静态校验失败（保存前拦截）。 */
public class TemplateValidationException extends RuntimeException {

    private final List<ScriptTemplateValidator.Issue> errors;
    private final List<ScriptTemplateValidator.Issue> warnings;

    public TemplateValidationException(
            List<ScriptTemplateValidator.Issue> errors,
            List<ScriptTemplateValidator.Issue> warnings) {
        super(buildMessage(errors));
        this.errors = errors;
        this.warnings = warnings;
    }

    private static String buildMessage(List<ScriptTemplateValidator.Issue> errors) {
        if (errors == null || errors.isEmpty()) {
            return "Template validation failed";
        }
        return errors.get(0).getMessage();
    }

    public List<ScriptTemplateValidator.Issue> getErrors() {
        return errors;
    }

    public List<ScriptTemplateValidator.Issue> getWarnings() {
        return warnings;
    }
}
