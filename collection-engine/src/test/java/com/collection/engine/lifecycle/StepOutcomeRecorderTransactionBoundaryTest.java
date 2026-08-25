package com.collection.engine.lifecycle;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

/**
 * 守护 {@link StepOutcomeRecorder} 的事务边界不变式。
 *
 * <p>为何需要一条反射不变式测试而不是行为测试：普通单测直接 new 对象调用，没有 Spring 代理， {@code Propagation.MANDATORY}
 * 不会被强制，因此「漏标 @Transactional」这类缺陷在单测里完全隐身， 只有真实容器里才会炸。2026-08-21 的 L4a 首跑就踩了这个坑—— {@code
 * recordTerminal} 的两个便捷重载没带 {@code @Transactional}，而它们自调用带注解的核心重载时绕过了 CGLIB 代理，
 * 于是整条七步管线（非事务上下文）在发件箱入箱处抛 {@code IllegalTransactionStateException}： 步骤状态与 timeline
 * 各自单独提交，STEP_COMPLETED 永不发出，计划全部停摆。
 */
class StepOutcomeRecorderTransactionBoundaryTest {

    /** 只读方法不写库也不入箱，无需事务。 */
    private static final List<String> NON_WRITING_METHODS = Arrays.asList("prepareAudit");

    @Test
    @DisplayName("所有写入型 public 入口都必须带 @Transactional，否则发件箱入箱会脱离事务")
    void everyPublicWriteEntryPointIsTransactional() {
        List<String> missing = new ArrayList<>();
        for (Method method : StepOutcomeRecorder.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers())
                    || method.isSynthetic()
                    || NON_WRITING_METHODS.contains(method.getName())) {
                continue;
            }
            if (!method.isAnnotationPresent(Transactional.class)) {
                missing.add(signature(method));
            }
        }
        assertTrue(
                missing.isEmpty(),
                "以下入口缺 @Transactional，被非事务的七步管线调用时会在发件箱入箱处抛 "
                        + "IllegalTransactionStateException，并把计划留在停摆态："
                        + missing);
    }

    private static String signature(Method method) {
        StringBuilder text = new StringBuilder(method.getName()).append('(');
        Class<?>[] params = method.getParameterTypes();
        for (int i = 0; i < params.length; i++) {
            text.append(i == 0 ? "" : ", ").append(params[i].getSimpleName());
        }
        return text.append(')').toString();
    }
}
