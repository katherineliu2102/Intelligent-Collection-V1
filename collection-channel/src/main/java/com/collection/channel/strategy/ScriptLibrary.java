package com.collection.channel.strategy;

import com.collection.channel.config.ChannelProperties;
import com.collection.common.enums.Stage;
import com.collection.common.model.CaseContext;
import com.collection.common.model.ContextSnapshot;
import java.math.BigDecimal;
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

    @Resource private ConfigTemplateProvider templateProvider;

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
                amount = formatAmount(resolveAmount(ctx));
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

    /**
     * S0（D-3~D0）用 {@code upcomingAmount}（可空，不回退逾期总额）；S1+ 用快照 {@code totalOutstanding}（外部
     * overdueAmount）。
     */
    static BigDecimal resolveAmount(CaseContext ctx) {
        if (ctx == null) {
            return null;
        }
        if (isS0(ctx)) {
            return ctx.getUpcomingAmount();
        }
        return ctx.getTotalOutstanding();
    }

    static boolean isS0(CaseContext ctx) {
        if (ctx.getStage() == Stage.S0) {
            return true;
        }
        return ctx.getStage() == null && ctx.getDpd() <= 0;
    }

    private static String formatAmount(BigDecimal value) {
        if (value == null) {
            return "";
        }
        return String.format(Locale.US, "%,.2f", value);
    }

    /** 渲染 SMS 正文；未配置该槽返回 {@code null}。 */
    public String renderSms(String scriptSlot, ScriptVars vars) {
        if (StringUtils.isBlank(scriptSlot)) {
            return null;
        }
        String tpl = templateProvider != null ? templateProvider.getSms(scriptSlot) : null;
        if (StringUtils.isBlank(tpl)) {
            tpl = channelProperties.getScripts().getSms().get(scriptSlot);
        }
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
        ChannelProperties.PushScript ps =
                templateProvider != null ? templateProvider.getPush(scriptSlot) : null;
        if (ps == null) {
            ps = channelProperties.getScripts().getPush().get(scriptSlot);
        }
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

    /** 该槽位在 DB 或 YAML 中是否有 SMS 正文。供 Resolver 与 Pilot 启动闸门在发送前判定文案完备性。 */
    public boolean hasSms(String scriptSlot) {
        if (StringUtils.isBlank(scriptSlot)) {
            return false;
        }
        String tpl = templateProvider != null ? templateProvider.getSms(scriptSlot) : null;
        if (StringUtils.isBlank(tpl)) {
            tpl = channelProperties.getScripts().getSms().get(scriptSlot);
        }
        return StringUtils.isNotBlank(tpl);
    }

    /**
     * 该槽位在 DB 或 YAML 中是否配齐 Push 的 title 与 body。
     *
     * <p>title 缺失时 Resolver 会退到硬编码的英文标题，那同样是一条没经过审校、也不随 stage 变化的通知栏文案，故与 body 一样按缺配处理。
     */
    public boolean hasPush(String scriptSlot) {
        if (StringUtils.isBlank(scriptSlot)) {
            return false;
        }
        ChannelProperties.PushScript ps =
                templateProvider != null ? templateProvider.getPush(scriptSlot) : null;
        if (ps == null) {
            ps = channelProperties.getScripts().getPush().get(scriptSlot);
        }
        return ps != null
                && StringUtils.isNotBlank(ps.getTitle())
                && StringUtils.isNotBlank(ps.getBody());
    }

    /** repaymentUrl 缺失时的兜底深链。 */
    public String defaultDeepLink() {
        return channelProperties.getScripts().getPushDefaultDeepLink();
    }

    /**
     * 变量注入 + name 缺失时的标点/空格清理。 例："MOCASA Collections: {name}, ..." 当 name 为空 → "MOCASA Collections:
     * ..."。公开以便后台文案校验复用同一套注入逻辑。
     */
    public static String inject(String template, ScriptVars vars) {
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
