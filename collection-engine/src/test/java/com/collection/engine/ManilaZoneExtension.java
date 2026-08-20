package com.collection.engine;

import java.util.TimeZone;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * 全局测试扩展：把引擎单测 JVM 的默认时区固定为 Asia/Manila (PHT, UTC+8)。
 *
 * <p>引擎生产代码用 {@code LocalDateTime.now(ZoneId.of("Asia/Manila"))} 计算步骤触发 / 异步超时时间， 而单测用 {@code
 * LocalDateTime.now()}（JVM 默认时区）做断言与到期扫描。 若运行环境默认时区不是马尼拉（如 CI 为 UTC），两者相差 8 小时，会导致退避 / 超时值错乱（240s 变
 * 29040s）、 步骤触发时间被误判为"未来"而永不到期（PENDING 卡死）。
 *
 * <p>此处用运行时 {@link TimeZone#setDefault} 覆盖，优先级高于任何 {@code -Duser.timezone} JVM 参数， 确保无论 CI
 * 运行于何种时区，引擎单测都与生产（马尼拉业务时区）一致、时区无关可复现。
 */
public class ManilaZoneExtension implements BeforeAllCallback {

    private static final TimeZone MANILA = TimeZone.getTimeZone("Asia/Manila");

    @Override
    public void beforeAll(ExtensionContext context) {
        TimeZone.setDefault(MANILA);
    }
}
