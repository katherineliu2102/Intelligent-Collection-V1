package com.collection.channel.strategy;

import com.collection.channel.config.ChannelProperties;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * SMS/Push 文案库 —— 按 {@code scriptSlot} 读取 {@code channel.scripts}（Nacos）并注入变量。
 *
 * <p>对应 [渠道模板清单 §4.1/§5.1/§7]。占位符 {@code {name}/{amount}/{dpd}}。
 * <p>命中不到 scriptSlot 时返回 {@code null}，由 Resolver 走占位兜底。
 */
@Component
public class ScriptLibrary {

    @Resource
    private ChannelProperties channelProperties;

    /** 渲染 SMS 正文；未配置该槽返回 {@code null}。 */
    public String renderSms(String scriptSlot, ScriptVars vars) {
        if (StringUtils.isBlank(scriptSlot)) {
            return null;
        }
        String tpl = channelProperties.getScripts().getSms().get(scriptSlot);
        if (StringUtils.isBlank(tpl)) {
            return null;
        }
        return inject(tpl, vars);
    }

    /** 渲染 Push title/body；未配置该槽返回 {@code null}。 */
    public PushContent renderPush(String scriptSlot, ScriptVars vars) {
        if (StringUtils.isBlank(scriptSlot)) {
            return null;
        }
        ChannelProperties.PushScript ps = channelProperties.getScripts().getPush().get(scriptSlot);
        if (ps == null) {
            return null;
        }
        String title = StringUtils.isNotBlank(ps.getTitle()) ? inject(ps.getTitle(), vars) : null;
        String body = StringUtils.isNotBlank(ps.getBody()) ? inject(ps.getBody(), vars) : null;
        if (title == null && body == null) {
            return null;
        }
        return new PushContent(title, body);
    }

    /** repaymentUrl 缺失时的兜底深链。 */
    public String defaultDeepLink() {
        return channelProperties.getScripts().getPushDefaultDeepLink();
    }

    /**
     * 变量注入 + name 缺失时的标点/空格清理。
     * 例："MOCASA Collections: {name}, ..." 当 name 为空 → "MOCASA Collections: ..."。
     */
    static String inject(String template, ScriptVars vars) {
        String name = vars != null && vars.getName() != null ? vars.getName().trim() : "";
        String amount = vars != null && vars.getAmount() != null ? vars.getAmount() : "";
        String dpd = vars != null ? String.valueOf(vars.getDpd()) : "0";

        String out = template
                .replace("{name}", name)
                .replace("{amount}", amount)
                .replace("{dpd}", dpd);

        // name 为空时清理残留标点：": ," → ": "；行首 ", " → ""
        out = out.replace(": ,", ": ");
        out = out.replaceAll("\\s{2,}", " ");
        out = out.replaceAll("^[,\\s]+", "");
        return out.trim();
    }
}
