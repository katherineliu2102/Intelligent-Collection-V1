package com.collection.channel.strategy;

/**
 * 文案变量载体。{@code {name}/{amount}/{dpd}/{repaymentUrl}} 由 {@link ScriptLibrary#buildVars} 从
 * ContextSnapshot 构建。
 *
 * <p>{@code amount} 已格式化为展示字符串（千分位，两位小数），避免文案层再处理 BigDecimal。
 */
public class ScriptVars {

    private final String name;
    private final String amount;
    private final int dpd;
    private final String repaymentUrl;

    public ScriptVars(String name, String amount, int dpd) {
        this(name, amount, dpd, null);
    }

    public ScriptVars(String name, String amount, int dpd, String repaymentUrl) {
        this.name = name;
        this.amount = amount;
        this.dpd = dpd;
        this.repaymentUrl = repaymentUrl;
    }

    public String getName() {
        return name;
    }

    public String getAmount() {
        return amount;
    }

    public int getDpd() {
        return dpd;
    }

    public String getRepaymentUrl() {
        return repaymentUrl;
    }
}
