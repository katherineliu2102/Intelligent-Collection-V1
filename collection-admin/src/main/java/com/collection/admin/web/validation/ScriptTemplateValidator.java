package com.collection.admin.web.validation;

import com.collection.channel.strategy.ScriptLibrary;
import com.collection.channel.strategy.ScriptVars;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;

/**
 * SMS/Push 文案热更新静态校验（P0）。
 *
 * <p>变量白名单与 {@link ScriptLibrary} 对齐；字数按模板硬上限 + 样例渲染双阈值。
 */
public final class ScriptTemplateValidator {

    public static final Set<String> ALLOWED_VARS =
            Collections.unmodifiableSet(
                    new LinkedHashSet<>(Arrays.asList("name", "amount", "dpd", "repaymentUrl")));

    /** 样例变量：偏长姓名/金额，用于估渲染后长度。 */
    public static final ScriptVars SAMPLE_VARS =
            new ScriptVars("Juan Dela Cruz", "999,999.99", 999, "https://mocasa.com/s/4cTu");

    public static final int SMS_BODY_MAX = 300;
    public static final int SMS_RENDERED_WARN = 160;
    public static final int SMS_RENDERED_SOFT = 320;
    public static final int SMS_RENDERED_MAX = 400;

    public static final int PUSH_TITLE_MAX = 40;
    public static final int PUSH_BODY_MAX = 120;
    public static final int PUSH_TITLE_RENDERED_MAX = 60;
    public static final int PUSH_BODY_RENDERED_MAX = 180;

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z][a-zA-Z0-9_]*)\\}");

    private ScriptTemplateValidator() {}

    public static ValidationResult validate(String channel, String title, String body) {
        String ch = channel == null ? "" : channel.trim().toUpperCase(Locale.ROOT);
        List<Issue> errors = new ArrayList<>();
        List<Issue> warnings = new ArrayList<>();
        Map<String, Object> preview = new LinkedHashMap<>();

        if (!"SMS".equals(ch) && !"PUSH".equals(ch)) {
            // Email 等不在本校验器范围
            return ValidationResult.ok(preview);
        }

        if ("SMS".equals(ch)) {
            validateField(
                    "body",
                    body,
                    SMS_BODY_MAX,
                    SMS_RENDERED_MAX,
                    SMS_RENDERED_SOFT,
                    SMS_RENDERED_WARN,
                    true,
                    Arrays.asList("amount", "repaymentUrl"),
                    errors,
                    warnings,
                    preview);
        } else {
            if (StringUtils.isBlank(title) && StringUtils.isBlank(body)) {
                errors.add(
                        Issue.error(
                                "body", "REQUIRED", "Push title and body cannot both be empty"));
            }
            validateField(
                    "title",
                    title,
                    PUSH_TITLE_MAX,
                    PUSH_TITLE_RENDERED_MAX,
                    PUSH_TITLE_RENDERED_MAX,
                    PUSH_TITLE_MAX,
                    false,
                    Collections.emptyList(),
                    errors,
                    warnings,
                    preview);
            validateField(
                    "body",
                    body,
                    PUSH_BODY_MAX,
                    PUSH_BODY_RENDERED_MAX,
                    PUSH_BODY_RENDERED_MAX,
                    PUSH_BODY_MAX,
                    false,
                    Collections.emptyList(),
                    errors,
                    warnings,
                    preview);
        }

        return new ValidationResult(errors, warnings, preview);
    }

    private static void validateField(
            String field,
            String text,
            int templateMax,
            int renderedMax,
            int renderedSoft,
            int renderedWarn,
            boolean required,
            List<String> requiredVars,
            List<Issue> errors,
            List<Issue> warnings,
            Map<String, Object> preview) {
        if (text == null || StringUtils.isBlank(text)) {
            if (required) {
                errors.add(Issue.error(field, "REQUIRED", field + " is required"));
            }
            return;
        }

        Set<String> found = extractPlaceholders(text);
        for (String var : found) {
            if (!ALLOWED_VARS.contains(var)) {
                errors.add(
                        Issue.error(
                                field,
                                "UNKNOWN_VAR",
                                "Unknown placeholder: {"
                                        + var
                                        + "}. Allowed: "
                                        + String.join(", ", ALLOWED_VARS)));
            }
        }
        for (String need : requiredVars) {
            if (!found.contains(need)) {
                errors.add(
                        Issue.error(
                                field,
                                "MISSING_VAR",
                                "Missing required placeholder: {" + need + "}"));
            }
        }

        int len = text.length();
        if (len > templateMax) {
            errors.add(
                    Issue.error(
                            field,
                            "MAX_LENGTH",
                            field
                                    + " exceeds "
                                    + templateMax
                                    + " characters (current: "
                                    + len
                                    + ")"));
        }

        String rendered = ScriptLibrary.inject(text, SAMPLE_VARS);
        preview.put(field + "Rendered", rendered);
        preview.put(field + "Length", len);
        preview.put(field + "RenderedLength", rendered.length());

        if (rendered.length() > renderedMax) {
            errors.add(
                    Issue.error(
                            field,
                            "RENDERED_MAX_LENGTH",
                            field
                                    + " rendered length exceeds "
                                    + renderedMax
                                    + " (sample: "
                                    + rendered.length()
                                    + ")"));
        } else if (rendered.length() > renderedSoft) {
            warnings.add(
                    Issue.warn(
                            field,
                            "RENDERED_SOFT_LENGTH",
                            field
                                    + " rendered length is "
                                    + rendered.length()
                                    + " (> "
                                    + renderedSoft
                                    + "); may cost multiple SMS segments"));
        } else if (rendered.length() > renderedWarn) {
            warnings.add(
                    Issue.warn(
                            field,
                            "SEGMENT_WARN",
                            field
                                    + " rendered length is "
                                    + rendered.length()
                                    + " (> "
                                    + renderedWarn
                                    + " GSM segment); expect multi-segment cost"));
        }
    }

    public static Set<String> extractPlaceholders(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) {
            return out;
        }
        Matcher m = PLACEHOLDER.matcher(text);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    public static final class Issue {
        private final String field;
        private final String code;
        private final String message;
        private final String severity;

        private Issue(String field, String code, String message, String severity) {
            this.field = field;
            this.code = code;
            this.message = message;
            this.severity = severity;
        }

        public static Issue error(String field, String code, String message) {
            return new Issue(field, code, message, "ERROR");
        }

        public static Issue warn(String field, String code, String message) {
            return new Issue(field, code, message, "WARN");
        }

        public Map<String, Object> toMap() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("field", field);
            row.put("code", code);
            row.put("message", message);
            row.put("severity", severity);
            return row;
        }

        public String getField() {
            return field;
        }

        public String getCode() {
            return code;
        }

        public String getMessage() {
            return message;
        }

        public String getSeverity() {
            return severity;
        }
    }

    public static final class ValidationResult {
        private final List<Issue> errors;
        private final List<Issue> warnings;
        private final Map<String, Object> preview;

        public ValidationResult(
                List<Issue> errors, List<Issue> warnings, Map<String, Object> preview) {
            this.errors = errors == null ? Collections.emptyList() : errors;
            this.warnings = warnings == null ? Collections.emptyList() : warnings;
            this.preview = preview == null ? Collections.emptyMap() : preview;
        }

        public static ValidationResult ok(Map<String, Object> preview) {
            return new ValidationResult(Collections.emptyList(), Collections.emptyList(), preview);
        }

        public boolean hasErrors() {
            return !errors.isEmpty();
        }

        public List<Issue> getErrors() {
            return errors;
        }

        public List<Issue> getWarnings() {
            return warnings;
        }

        public Map<String, Object> getPreview() {
            return preview;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("valid", !hasErrors());
            List<Map<String, Object>> errMaps = new ArrayList<>();
            for (Issue e : errors) {
                errMaps.add(e.toMap());
            }
            List<Map<String, Object>> warnMaps = new ArrayList<>();
            for (Issue w : warnings) {
                warnMaps.add(w.toMap());
            }
            out.put("errors", errMaps);
            out.put("warnings", warnMaps);
            out.put("preview", preview);
            out.put("allowedVars", new ArrayList<>(ALLOWED_VARS));
            out.put("limits", limitsMap());
            return out;
        }

        private static Map<String, Object> limitsMap() {
            Map<String, Object> limits = new LinkedHashMap<>();
            limits.put("smsBodyMax", SMS_BODY_MAX);
            limits.put("smsRenderedWarn", SMS_RENDERED_WARN);
            limits.put("smsRenderedSoft", SMS_RENDERED_SOFT);
            limits.put("smsRenderedMax", SMS_RENDERED_MAX);
            limits.put("pushTitleMax", PUSH_TITLE_MAX);
            limits.put("pushBodyMax", PUSH_BODY_MAX);
            return limits;
        }
    }
}
