package com.collection.admin.web.facade;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 复刻 Python {@code json.dumps(obj, separators=(",", ":"), sort_keys=True)}（默认 ensure_ascii）。 Facade
 * 手册 §11.3：验签必须用 canonical JSON，不能对原始 body 字节 HMAC。
 */
public final class FacadeCanonicalJson {

    private FacadeCanonicalJson() {}

    public static String dumps(JsonNode node) {
        StringBuilder sb = new StringBuilder();
        write(node, sb);
        return sb.toString();
    }

    private static void write(JsonNode node, StringBuilder sb) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            sb.append("null");
            return;
        }
        if (node.isBoolean()) {
            sb.append(node.booleanValue() ? "true" : "false");
            return;
        }
        if (node.isNumber()) {
            sb.append(node.isIntegralNumber() ? node.bigIntegerValue().toString() : node.asText());
            return;
        }
        if (node.isTextual()) {
            quote(node.textValue(), sb);
            return;
        }
        if (node.isArray()) {
            sb.append('[');
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                write(node.get(i), sb);
            }
            sb.append(']');
            return;
        }
        if (node.isObject()) {
            List<String> keys = new ArrayList<String>();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                keys.add(fields.next().getKey());
            }
            Collections.sort(keys);
            sb.append('{');
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                quote(keys.get(i), sb);
                sb.append(':');
                write(node.get(keys.get(i)), sb);
            }
            sb.append('}');
            return;
        }
        quote(node.asText(), sb);
    }

    private static void quote(String raw, StringBuilder sb) {
        sb.append('"');
        if (raw == null) {
            sb.append('"');
            return;
        }
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20 || c > 0x7E) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }
}
