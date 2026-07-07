package com.collection.channel.strategy;

import com.collection.channel.config.ChannelProperties;
import com.collection.common.model.CaseContext;
import com.collection.common.model.ContextSnapshot;
import java.util.Locale;
import javax.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/**
 * SMS/Push 文案库 —— 按 {@code scriptSlot} 读取 {@code channel.scripts}（Nacos）并注入变量。
 *
 * <p>对应 [渠道模板清单 §4.1/§5.1/§7]。占位符 {@code {name}/{amount}/{dpd}/{repaymentUrl}}。
 *
 * <p>命中不到 scriptSlot 时返回 {@code null}，由 Resolver 走占位兜底。
 */
@Component
public class ScriptLibrary {

    @Resource private ChannelProperties channelProperties;

    /** 从 ContextSnapshot 构建文案变量；repaymentUrl 优先取 caseContext，缺失时用 sms-default-repayment-link。 */
    public ScriptVars buildVars(ContextSnapshot snapshot) {
        String name = null;
        String amount = "0.00";
        int dpd = 0;
        String repaymentUrl = resolveRepaymentUrl(snapshot);
        if (snapshot != null) {
            if (snapshot.getUserProfile() != null && snapshot.getUserProfile().getBasic() != null) {
                name = snapshot.getUserProfile().getBasic().getName();
            }
            if (snapshot.getCaseContext() != null) {
                CaseContext ctx = snapshot.getCaseContext();
                if (ctx.getTotalOutstanding() != null) {
                    amount = String.format(Locale.US, "%,.2f", ctx.getTotalOutstanding());
                }
                dpd = ctx.getDpd();
            }
        }
        return new ScriptVars(name, amount, dpd, repaymentUrl);
    }

    /** repaymentUrl 缺失时的 SMS 兜底短链。 */
    public String defaultSmsRepaymentLink() {
        String link = channelProperties.getScripts().getSmsDefaultRepaymentLink();
        if (StringUtils.isNotBlank(link)) {
            return link;
        }
        return channelProperties.getScripts().getPushDefaultDeepLink();
    }

    private String resolveRepaymentUrl(ContextSnapshot snapshot) {
        if (snapshot != null && snapshot.getCaseContext() != null) {
            String url = snapshot.getCaseContext().getRepaymentUrl();
            if (StringUtils.isNotBlank(url)) {
                return url;
            }
        }
        return defaultSmsRepaymentLink();
    }

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
     * 变量注入 + name 缺失时的标点/空格清理。 例："MOCASA Collections: {name}, ..." 当 name 为空 → "MOCASA Collections:
     * ..."。
     */
    static String inject(String template, ScriptVars vars) {
        String name = vars != null && vars.getName() != null ? vars.getName().trim() : "";
        String amount = vars != null && vars.getAmount() != null ? vars.getAmount() : "";
        String dpd = vars != null ? String.valueOf(vars.getDpd()) : "0";

        String out =
                template.replace("{name}", name)
                        .replace("{amount}", amount)
                        .replace("{dpd}", dpd)
                        .replace(
                                "{repaymentUrl}",
                                vars != null && vars.getRepaymentUrl() != null
                                        ? vars.getRepaymentUrl()
                                        : "");

        // name 为空时清理残留标点：": ," → ": "；行首 ", " → ""
        out = out.replace(": ,", ": ");
        out = out.replaceAll("\\s{2,}", " ");
        out = out.replaceAll("^[,\\s]+", "");
        return out.trim();
    }
}
