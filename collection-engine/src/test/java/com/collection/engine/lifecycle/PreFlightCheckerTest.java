package com.collection.engine.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.collection.common.model.CaseInfo;
import com.collection.common.service.CaseService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * PreFlightChecker 系统级守卫分支单测（核心引擎规格 §3.1②、§5）。全 mock，不连库。 覆盖测试矩阵 #5a-5e：案件不存在 / 已还款 / 冻结 / 存活 /
 * 读失败 fail-close。
 */
@ExtendWith(MockitoExtension.class)
class PreFlightCheckerTest {

    private static final long CASE_ID = 1002L;

    @Mock private CaseService caseService;
    @InjectMocks private PreFlightChecker preFlightChecker;

    private CaseInfo alive() {
        CaseInfo info = new CaseInfo();
        info.setCaseId(CASE_ID);
        info.setRepaid(false);
        info.setFrozen(false);
        return info;
    }

    @Test
    @DisplayName("#5a 案件不存在(null) → false，静默退出")
    void caseNotFound_returnsFalse() {
        when(caseService.getCaseInfo(CASE_ID)).thenReturn(null);
        assertThat(preFlightChecker.check(CASE_ID)).isFalse();
    }

    @Test
    @DisplayName("#5b 已还款 → false")
    void repaid_returnsFalse() {
        CaseInfo info = alive();
        info.setRepaid(true);
        when(caseService.getCaseInfo(CASE_ID)).thenReturn(info);
        assertThat(preFlightChecker.check(CASE_ID)).isFalse();
    }

    @Test
    @DisplayName("#5c 冻结 → false")
    void frozen_returnsFalse() {
        CaseInfo info = alive();
        info.setFrozen(true);
        when(caseService.getCaseInfo(CASE_ID)).thenReturn(info);
        assertThat(preFlightChecker.check(CASE_ID)).isFalse();
    }

    @Test
    @DisplayName("#5d 案件存活（未还款/未冻结）→ true，可触达")
    void alive_returnsTrue() {
        when(caseService.getCaseInfo(CASE_ID)).thenReturn(alive());
        assertThat(preFlightChecker.check(CASE_ID)).isTrue();
    }

    @Test
    @DisplayName("#5e 读取失败(DB 不可达) → fail-close → false")
    void readFailure_failCloseReturnsFalse() {
        when(caseService.getCaseInfo(CASE_ID)).thenThrow(new RuntimeException("MySQL down"));
        assertThat(preFlightChecker.check(CASE_ID)).isFalse();
    }
}
