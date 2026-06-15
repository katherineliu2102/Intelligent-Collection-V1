package com.collection.channel.strategy;

/**
 * 文案变量载体。{@code {name}/{amount}/{dpd}} 由 {@link DefaultStepResolver} 从 ContextSnapshot 构建。
 *
 * <p>{@code amount} 已格式化为展示字符串（千分位，两位小数），避免文案层再处理 BigDecimal。
 */
public class ScriptVars {

    private final String name;
    private final String amount;
    private final int dpd;

    public ScriptVars(String name, String amount, int dpd) {
        this.name = name;
        this.amount = amount;
        this.dpd = dpd;
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
}
