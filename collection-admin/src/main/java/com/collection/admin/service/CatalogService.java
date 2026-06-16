package com.collection.admin.service;

import com.collection.channel.config.ChannelProperties;
import com.collection.common.email.EmailMilestoneScriptSlots;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 策略/渠道目录聚合：静态 catalog 元数据 + 运行时 {@link ChannelProperties} + 文案草稿。
 */
@Service
public class CatalogService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<Map<String, Object>>() {};

    @Resource
    private ChannelProperties channelProperties;

    @Resource
    private ObjectMapper objectMapper;

    private Map<String, Object> metadata = new LinkedHashMap<>();
    private Map<String, Object> scriptDrafts = new LinkedHashMap<>();

    @PostConstruct
    void loadMetadata() throws Exception {
        try (InputStream in = new ClassPathResource("catalog/catalog-metadata.json").getInputStream()) {
            metadata = objectMapper.readValue(in, MAP_TYPE);
        }
        try (InputStream in = new ClassPathResource("catalog/script-drafts.json").getInputStream()) {
            scriptDrafts = objectMapper.readValue(in, MAP_TYPE);
        }
    }

    public Map<String, Object> overview() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generatedAt", Instant.now().toString());
        out.put("phase", "Phase 1");
        out.put("title", metadata.get("title"));
        out.put("subtitle", metadata.get("subtitle"));
        out.put("paradigm", metadata.get("paradigm"));
        out.put("touchWindow", metadata.get("touchWindow"));
        out.put("ceaseRule", metadata.get("ceaseRule"));
        out.put("summary", buildSummary());
        out.put("runtime", buildRuntime());
        out.put("stages", metadata.get("stages"));
        out.put("channels", enrichChannels());
        out.put("templates", buildTemplates());
        out.put("samples", scriptDrafts.get("samples"));
        out.put("spi", metadata.get("spi"));
        out.put("configIndex", metadata.get("configIndex"));
        out.put("quickLinks", metadata.get("quickLinks"));
        return out;
    }

    public Map<String, Object> templateDetail(String slot) {
        Map<String, Object> detail = findTemplateRow(slot);
        if (detail == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>(detail);
        out.put("samples", scriptDrafts.get("samples"));
        if ("EMAIL".equals(detail.get("channel"))) {
            out.put("hasHtmlPreview", hasEmailHtml(slot));
            out.put("previewUrl", "/catalog/template/" + slot + "/preview");
        }
        return out;
    }

    public String emailPreviewHtml(String slot) throws Exception {
        Map<String, Object> emailDraft = emailDraft(slot);
        if (emailDraft == null) {
            return null;
        }
        String htmlFile = String.valueOf(emailDraft.get("htmlFile"));
        ClassPathResource res = new ClassPathResource("catalog/email-templates/" + htmlFile);
        if (!res.exists()) {
            return null;
        }
        String html;
        try (InputStream in = res.getInputStream()) {
            html = StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        }
        html = stripHtmlComment(html);
        return renderEmailHtml(html, sampleMap());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> findTemplateRow(String slot) {
        Map<String, Object> templates = buildTemplates();
        for (String key : new String[]{"email", "sms", "push"}) {
            List<Map<String, Object>> rows = (List<Map<String, Object>>) templates.get(key);
            if (rows == null) {
                continue;
            }
            for (Map<String, Object> row : rows) {
                if (slot.equals(row.get("slot"))) {
                    return row;
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildSummary() {
        Map<String, Object> s = new LinkedHashMap<>();
        List<Map<String, Object>> stages = (List<Map<String, Object>>) metadata.get("stages");
        List<Map<String, Object>> channels = (List<Map<String, Object>>) metadata.get("channels");
        s.put("stagesCount", stages != null ? stages.size() : 0);
        s.put("channelsLive", countChannelsByPhase(channels, "LIVE"));
        s.put("emailActiveCount", EmailMilestoneScriptSlots.PHASE1_ACTIVE.size());
        s.put("emailConfiguredCount", channelProperties.getSendgrid().getTemplates().size());
        s.put("smsConfiguredCount", channelProperties.getScripts().getSms().size());
        s.put("pushConfiguredCount", channelProperties.getScripts().getPush().size());
        return s;
    }

    private int countChannelsByPhase(List<Map<String, Object>> channels, String phase) {
        if (channels == null) {
            return 0;
        }
        int n = 0;
        for (Map<String, Object> c : channels) {
            if (phase.equals(c.get("phase1"))) {
                n++;
            }
        }
        return n;
    }

    private Map<String, Object> buildRuntime() {
        Map<String, Object> r = new LinkedHashMap<>();
        ChannelProperties.Debug debug = channelProperties.getDebug();
        Map<String, Object> debugMap = new LinkedHashMap<>();
        debugMap.put("singleStep", emptyToDash(debug.getSingleStep()));
        debugMap.put("legacyThreeStep", debug.isLegacyThreeStep());
        r.put("debug", debugMap);

        Map<String, Object> connectivity = new LinkedHashMap<>();
        connectivity.put("sendGrid", channelProperties.isSendGridConfigured());
        connectivity.put("notification", channelProperties.isNotificationConfigured());
        connectivity.put("notificationTest", channelProperties.isNotificationTestConfigured());
        connectivity.put("lthVoice", !isEmpty(channelProperties.getLth().getVoice().getUrl()));
        connectivity.put("callbackBaseUrl", !isEmpty(channelProperties.getCallback().getBaseUrl()));
        r.put("connectivity", connectivity);

        ChannelProperties.Compliance c = channelProperties.getCompliance();
        Map<String, Object> compliance = new LinkedHashMap<>();
        compliance.put("timezone", c.getTimezone());
        compliance.put("touchWindow", c.getTouchWindowStart() + " – " + c.getTouchWindowEnd());
        compliance.put("quietHours", c.getQuietHoursStart() + " – " + c.getQuietHoursEnd());
        compliance.put("dailyLimit", c.getDailyLimit());
        r.put("compliance", compliance);

        ChannelProperties.SendGrid sg = channelProperties.getSendgrid();
        Map<String, Object> sendgrid = new LinkedHashMap<>();
        sendgrid.put("fromEmail", emptyToDash(sg.getFromEmail()));
        sendgrid.put("fromName", sg.getFromName());
        sendgrid.put("templateCount", sg.getTemplates().size());
        r.put("sendgrid", sendgrid);

        ChannelProperties.Notification n = channelProperties.getNotification();
        Map<String, Object> notification = new LinkedHashMap<>();
        notification.put("baseUrl", emptyToDash(n.getBaseUrl()));
        notification.put("appCode", n.getAppCode());
        notification.put("smsTestMode", n.isSmsTestMode());
        notification.put("pushSyncMode", n.isPushSyncMode());
        r.put("notification", notification);

        return r;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> enrichChannels() {
        List<Map<String, Object>> channels = (List<Map<String, Object>>) metadata.get("channels");
        if (channels == null) {
            return new ArrayList<>();
        }
        List<Map<String, Object>> enriched = new ArrayList<>();
        for (Map<String, Object> ch : channels) {
            Map<String, Object> row = new LinkedHashMap<>(ch);
            String type = String.valueOf(ch.get("type"));
            row.put("configured", isChannelConfigured(type));
            enriched.add(row);
        }
        return enriched;
    }

    private boolean isChannelConfigured(String type) {
        switch (type) {
            case "SMS":
            case "PUSH":
                return channelProperties.isNotificationConfigured()
                        || channelProperties.isNotificationTestConfigured();
            case "EMAIL":
                return channelProperties.isSendGridConfigured();
            case "AI_CALL":
            case "TTS":
                return !isEmpty(channelProperties.getLth().getVoice().getUrl());
            default:
                return false;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildTemplates() {
        Map<String, Object> templates = new LinkedHashMap<>();
        List<Map<String, Object>> scriptSlots = (List<Map<String, Object>>) metadata.get("scriptSlots");

        List<Map<String, Object>> emailRows = new ArrayList<>();
        for (String slot : EmailMilestoneScriptSlots.PHASE1_ACTIVE) {
            emailRows.add(buildEmailRow(slotRow(slot, "EMAIL"), slot));
        }
        if (scriptSlots != null) {
            for (Map<String, Object> meta : scriptSlots) {
                if (!"EMAIL".equals(meta.get("channel"))) {
                    continue;
                }
                String slot = String.valueOf(meta.get("slot"));
                if (EmailMilestoneScriptSlots.isPhase1Active(slot)) {
                    continue;
                }
                emailRows.add(buildEmailRow(new LinkedHashMap<>(meta), slot));
            }
        }
        templates.put("email", emailRows);

        List<Map<String, Object>> smsRows = new ArrayList<>();
        if (scriptSlots != null) {
            for (Map<String, Object> meta : scriptSlots) {
                if (!"SMS".equals(meta.get("channel"))) {
                    continue;
                }
                smsRows.add(buildSmsRow(new LinkedHashMap<>(meta), String.valueOf(meta.get("slot"))));
            }
        }
        templates.put("sms", smsRows);

        List<Map<String, Object>> pushRows = new ArrayList<>();
        if (scriptSlots != null) {
            for (Map<String, Object> meta : scriptSlots) {
                if (!"PUSH".equals(meta.get("channel"))) {
                    continue;
                }
                pushRows.add(buildPushRow(new LinkedHashMap<>(meta), String.valueOf(meta.get("slot"))));
            }
        }
        templates.put("push", pushRows);

        return templates;
    }

    private Map<String, Object> buildEmailRow(Map<String, Object> row, String slot) {
        String tid = channelProperties.getSendgrid().getTemplates().get(slot);
        row.put("templateId", maskTemplateId(tid));
        row.put("templateIdFull", emptyToDash(tid));
        row.put("configured", tid != null && !tid.isEmpty());
        boolean configured = tid != null && !tid.isEmpty();

        Map<String, Object> draft = emailDraft(slot);
        if (draft != null) {
            row.put("subject", draft.get("subject"));
            row.put("preheader", draft.get("preheader"));
            row.put("variables", draft.get("variables"));
            row.put("hasHtmlPreview", hasEmailHtml(slot));
            row.put("viewable", true);
            row.put("contentSource", configured ? "RUNTIME+DRAFT" : "DRAFT");
        } else {
            row.put("viewable", false);
        }
        return row;
    }

    private Map<String, Object> buildSmsRow(Map<String, Object> row, String slot) {
        String runtime = channelProperties.getScripts().getSms().get(slot);
        Map<String, Object> draft = smsDraft(slot);
        String body = !isEmpty(runtime) ? runtime : draftBody(draft);
        boolean fromRuntime = !isEmpty(runtime);

        row.put("configured", fromRuntime);
        row.put("body", body);
        row.put("bodyRendered", renderBraceVars(body, sampleMap()));
        row.put("tone", draft != null ? draft.get("tone") : null);
        row.put("viewable", !isEmpty(body));
        row.put("contentSource", fromRuntime ? "RUNTIME" : (!isEmpty(body) ? "DRAFT" : "NONE"));
        row.put("preview", truncate(body, 80));
        return row;
    }

    private Map<String, Object> buildPushRow(Map<String, Object> row, String slot) {
        ChannelProperties.PushScript ps = channelProperties.getScripts().getPush().get(slot);
        Map<String, Object> draft = pushDraft(slot);
        String title = ps != null && !isEmpty(ps.getTitle()) ? ps.getTitle() : draftTitle(draft);
        String body = ps != null && !isEmpty(ps.getBody()) ? ps.getBody() : draftBody(draft);
        boolean fromRuntime = ps != null && (!isEmpty(ps.getTitle()) || !isEmpty(ps.getBody()));

        Map<String, String> samples = sampleMap();
        row.put("configured", fromRuntime);
        row.put("title", title);
        row.put("body", body);
        row.put("titleRendered", renderBraceVars(title, samples));
        row.put("bodyRendered", renderBraceVars(body, samples));
        row.put("viewable", !isEmpty(title) || !isEmpty(body));
        row.put("contentSource", fromRuntime ? "RUNTIME" : (!isEmpty(body) ? "DRAFT" : "NONE"));
        row.put("preview", truncate(body, 80));
        row.put("dataExample", pushDataExample(slot, samples));
        return row;
    }

    private Map<String, String> pushDataExample(String slot, Map<String, String> samples) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("scene", "collection");
        data.put("case_id", "1001");
        data.put("script_slot", slot);
        data.put("deep_link", samples.get("payment_link"));
        return data;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> slotRow(String slot, String channel) {
        List<Map<String, Object>> scriptSlots = (List<Map<String, Object>>) metadata.get("scriptSlots");
        if (scriptSlots != null) {
            for (Map<String, Object> meta : scriptSlots) {
                if (slot.equals(meta.get("slot"))) {
                    return new LinkedHashMap<>(meta);
                }
            }
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("slot", slot);
        row.put("channel", channel);
        return row;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> smsDraft(String slot) {
        Map<String, Object> sms = (Map<String, Object>) scriptDrafts.get("sms");
        if (sms == null) {
            return null;
        }
        Object d = sms.get(slot);
        return d instanceof Map ? (Map<String, Object>) d : null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> pushDraft(String slot) {
        Map<String, Object> push = (Map<String, Object>) scriptDrafts.get("push");
        if (push == null) {
            return null;
        }
        Object d = push.get(slot);
        return d instanceof Map ? (Map<String, Object>) d : null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> emailDraft(String slot) {
        Map<String, Object> email = (Map<String, Object>) scriptDrafts.get("email");
        if (email == null) {
            return null;
        }
        Object d = email.get(slot);
        return d instanceof Map ? (Map<String, Object>) d : null;
    }

    private boolean hasEmailHtml(String slot) {
        Map<String, Object> draft = emailDraft(slot);
        if (draft == null || draft.get("htmlFile") == null) {
            return false;
        }
        return new ClassPathResource("catalog/email-templates/" + draft.get("htmlFile")).exists();
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> sampleMap() {
        Object samples = scriptDrafts.get("samples");
        if (!(samples instanceof Map)) {
            return Collections.emptyMap();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : ((Map<String, Object>) samples).entrySet()) {
            out.put(e.getKey(), String.valueOf(e.getValue()));
        }
        return out;
    }

    private static String renderBraceVars(String template, Map<String, String> vars) {
        if (isEmpty(template) || vars == null) {
            return template;
        }
        String out = template;
        for (Map.Entry<String, String> e : vars.entrySet()) {
            out = out.replace("{" + e.getKey() + "}", e.getValue());
        }
        return out;
    }

    private static String renderEmailHtml(String html, Map<String, String> vars) {
        String out = html;
        for (Map.Entry<String, String> e : vars.entrySet()) {
            out = out.replace("{{" + e.getKey() + "}}", e.getValue());
        }
        return out;
    }

    private static String stripHtmlComment(String html) {
        return html.replaceFirst("(?s)<!--.*?-->", "").trim();
    }

    private static String draftBody(Map<String, Object> draft) {
        return draft == null || draft.get("body") == null ? "" : String.valueOf(draft.get("body"));
    }

    private static String draftTitle(Map<String, Object> draft) {
        return draft == null || draft.get("title") == null ? "" : String.valueOf(draft.get("title"));
    }

    private static String maskTemplateId(String id) {
        if (id == null || id.isEmpty()) {
            return "—";
        }
        if (id.length() <= 12) {
            return id;
        }
        return id.substring(0, 10) + "…";
    }

    private static String truncate(String s, int max) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static String emptyToDash(String s) {
        return isEmpty(s) ? "—" : s;
    }

    private static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }
}
