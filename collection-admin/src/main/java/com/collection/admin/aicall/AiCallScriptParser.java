package com.collection.admin.aicall;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;

/**
 * 把 Facade {@code script_url} JSON 收成轮次统计 + 扁平文本。
 *
 * <p>供应商 schema 未写死：兼容 {@code conversation_history} / {@code transcript} / {@code messages} /
 * {@code dialog} / {@code turns}。借款人轮次：{@code role}/{@code speaker}/{@code from} 规范化后不在助手集合内，且有文本。
 */
public final class AiCallScriptParser {

    private static final Set<String> ASSISTANT_ROLES =
            new HashSet<String>(
                    Arrays.asList("assistant", "agent", "bot", "system", "ai", "tool", "operator"));

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AiCallScriptParser() {}

    public static Parsed parse(String raw) {
        if (StringUtils.isBlank(raw)) {
            return Parsed.empty(raw);
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new IllegalArgumentException("script json not parseable", e);
        }
        JsonNode hist = historyNode(root);
        if (hist == null || !hist.isArray() || hist.size() == 0) {
            return Parsed.empty(raw);
        }
        StringBuilder text = new StringBuilder();
        int turns = 0;
        boolean borrower = false;
        for (JsonNode turn : hist) {
            String role = roleOf(turn);
            String line = textOf(turn);
            if (StringUtils.isBlank(line) && !(turn != null && turn.isTextual())) {
                continue;
            }
            if (turn != null && turn.isTextual()) {
                line = turn.asText();
            }
            if (StringUtils.isBlank(line)) {
                continue;
            }
            turns++;
            if (isBorrower(role)) {
                borrower = true;
            }
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append('[').append(role == null || role.isEmpty() ? "?" : role).append("] ");
            text.append(line.trim());
        }
        if (turns == 0) {
            return Parsed.empty(raw);
        }
        return new Parsed(raw, text.toString(), turns, borrower);
    }

    static boolean isBorrower(String role) {
        if (StringUtils.isBlank(role)) {
            return false;
        }
        return !ASSISTANT_ROLES.contains(role.trim().toLowerCase(Locale.ROOT));
    }

    private static JsonNode historyNode(JsonNode root) {
        if (root == null || root.isNull()) {
            return null;
        }
        if (root.isArray()) {
            return root;
        }
        String[] keys = {"conversation_history", "transcript", "messages", "dialog", "turns"};
        for (int i = 0; i < keys.length; i++) {
            JsonNode n = root.get(keys[i]);
            if (n != null && n.isArray()) {
                return n;
            }
        }
        return null;
    }

    private static String roleOf(JsonNode turn) {
        if (turn == null || !turn.isObject()) {
            return "";
        }
        String[] keys = {"role", "speaker", "from"};
        for (int i = 0; i < keys.length; i++) {
            JsonNode n = turn.get(keys[i]);
            if (n != null && n.isTextual() && StringUtils.isNotBlank(n.asText())) {
                return n.asText().trim();
            }
        }
        return "";
    }

    private static String textOf(JsonNode turn) {
        if (turn == null) {
            return "";
        }
        if (turn.isTextual()) {
            return turn.asText();
        }
        if (!turn.isObject()) {
            return "";
        }
        String[] keys = {"content", "text", "message"};
        for (int i = 0; i < keys.length; i++) {
            JsonNode n = turn.get(keys[i]);
            if (n != null && n.isTextual() && StringUtils.isNotBlank(n.asText())) {
                return n.asText();
            }
        }
        return "";
    }

    public static final class Parsed {
        public final String scriptJson;
        public final String transcriptText;
        public final int turnCount;
        public final boolean hasBorrowerTurn;

        Parsed(String scriptJson, String transcriptText, int turnCount, boolean hasBorrowerTurn) {
            this.scriptJson = scriptJson;
            this.transcriptText = transcriptText;
            this.turnCount = turnCount;
            this.hasBorrowerTurn = hasBorrowerTurn;
        }

        static Parsed empty(String raw) {
            return new Parsed(raw, null, 0, false);
        }
    }
}
