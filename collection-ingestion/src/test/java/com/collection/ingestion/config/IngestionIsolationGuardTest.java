package com.collection.ingestion.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** 联调隔离闸门：抢生产订阅与空白名单必须在建立订阅前失败。 */
class IngestionIsolationGuardTest {

    private static IngestionProperties props(String subscription, Long... whitelist) {
        IngestionProperties props = new IngestionProperties();
        props.setEnabled(true);
        props.setProjectId("fintech-all");
        props.setSubscription(subscription);
        props.setLoanIdWhitelist(Arrays.asList(whitelist));
        return props;
    }

    private static MockEnvironment profile(String... profiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        return env;
    }

    @Test
    @DisplayName("local profile 配生产订阅 → 拒绝启动")
    void productionSubscriptionUnderLocalProfileIsRejected() {
        IngestionIsolationGuard guard =
                new IngestionIsolationGuard(
                        props("intelligent-collection-cases-v1-sub", 99000000L), profile("local"));

        IllegalStateException e = assertThrows(IllegalStateException.class, guard::validate);
        assertTrue(e.getMessage().contains("保留订阅"), e.getMessage());
    }

    @Test
    @DisplayName("旧生产订阅名同样在保留清单内")
    void legacyProductionSubscriptionsAreAlsoReserved() {
        for (String reserved :
                Arrays.asList("collection-cases-sub", "collection-cases-ai-v1-sub")) {
            IngestionIsolationGuard guard =
                    new IngestionIsolationGuard(props(reserved, 99000000L), profile("test"));
            assertThrows(IllegalStateException.class, guard::validate, reserved);
        }
    }

    @Test
    @DisplayName("自建隔离订阅 + 非空白名单 → 放行")
    void isolatedSubscriptionWithWhitelistPasses() {
        IngestionIsolationGuard guard =
                new IngestionIsolationGuard(
                        props("intelligent-collection-cases-v1-l4b-sub", 99000000L, 99000001L),
                        profile("local"));

        assertDoesNotThrow(guard::validate);
    }

    @Test
    @DisplayName("空白名单等于放行全部案件 → 拒绝启动")
    void emptyWhitelistUnderLocalProfileIsRejected() {
        IngestionIsolationGuard guard =
                new IngestionIsolationGuard(
                        props("intelligent-collection-cases-test1-sub"), profile("local"));

        IllegalStateException e = assertThrows(IllegalStateException.class, guard::validate);
        assertTrue(e.getMessage().contains("loan-id-whitelist"), e.getMessage());
    }

    @Test
    @DisplayName("null 白名单与空列表等价，同样拒绝")
    void nullWhitelistIsTreatedAsEmpty() {
        IngestionProperties props = props("intelligent-collection-cases-test1-sub");
        props.setLoanIdWhitelist(null);

        assertThrows(
                IllegalStateException.class,
                new IngestionIsolationGuard(props, profile("local"))::validate);
    }

    @Test
    @DisplayName("pilot profile 不受本闸门约束：生产订阅与名单由 PilotReadinessValidator 把关")
    void pilotProfileIsNotGuardedHere() {
        IngestionIsolationGuard guard =
                new IngestionIsolationGuard(
                        props("intelligent-collection-cases-v1-sub"), profile("pilot"));

        assertDoesNotThrow(guard::validate);
    }

    @Test
    @DisplayName("未激活任何 profile 时不拦截，避免影响单测与工具进程")
    void noActiveProfileIsNotGuarded() {
        IngestionIsolationGuard guard =
                new IngestionIsolationGuard(
                        props("intelligent-collection-cases-v1-sub"),
                        profile(Collections.<String>emptyList().toArray(new String[0])));

        assertDoesNotThrow(guard::validate);
    }
}
