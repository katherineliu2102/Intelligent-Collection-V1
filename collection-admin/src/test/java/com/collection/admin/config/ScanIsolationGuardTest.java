package com.collection.admin.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** 扫描隔离闸门：空案件名单在受控 profile 下必须拒绝启动，全量需显式声明。 */
class ScanIsolationGuardTest {

    private static ScanProperties props(Long... whitelist) {
        ScanProperties props = new ScanProperties();
        props.setCaseIdWhitelist(Arrays.asList(whitelist));
        return props;
    }

    private static MockEnvironment profile(String... profiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        return env;
    }

    @Test
    @DisplayName("local profile 空名单 → 拒绝启动")
    void emptyWhitelistUnderLocalProfileIsRejected() {
        ScanIsolationGuard guard = new ScanIsolationGuard(props(), profile("local"));

        IllegalStateException e = assertThrows(IllegalStateException.class, guard::validate);
        assertTrue(e.getMessage().contains("case-id-whitelist"), e.getMessage());
    }

    @Test
    @DisplayName("pilot 空名单告警放行：范围跟订阅与库内计划")
    void emptyWhitelistUnderPilotProfileIsAllowed() {
        ScanIsolationGuard guard = new ScanIsolationGuard(props(), profile("pilot"));

        assertDoesNotThrow(guard::validate);
    }

    @Test
    @DisplayName("pilot 上 null 名单与空列表等价，同样放行")
    void nullWhitelistOnPilotIsAllowed() {
        ScanProperties props = props();
        props.setCaseIdWhitelist(null);

        assertDoesNotThrow(new ScanIsolationGuard(props, profile("pilot"))::validate);
    }

    @Test
    @DisplayName("pilot 非空名单 → 放行")
    void whitelistedPilotPasses() {
        ScanIsolationGuard guard = new ScanIsolationGuard(props(94999L, 94102L), profile("pilot"));

        assertDoesNotThrow(guard::validate);
    }

    @Test
    @DisplayName("allow-full-scan=true 时空名单放行，供 T6 全量切换")
    void explicitFullScanIsAllowed() {
        ScanProperties props = props();
        props.setAllowFullScan(true);

        assertDoesNotThrow(new ScanIsolationGuard(props, profile("pilot"))::validate);
    }

    @Test
    @DisplayName("未激活受控 profile 时不拦截，避免影响单测与工具进程")
    void unguardedProfileIsNotChecked() {
        ScanIsolationGuard guard = new ScanIsolationGuard(props(), profile("l3-it"));

        assertDoesNotThrow(guard::validate);
    }
}
