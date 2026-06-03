package com.collection.common.dto;

import com.collection.common.enums.ExhaustionAction;
import com.collection.common.enums.Stage;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/**
 * 穷尽结果。ExhaustionPolicy.handle() 的输出。对应领域模型 §4.6。
 *
 * <p>字段约束：
 * REBUILD → templateId 必填, targetStage=null；
 * ESCALATE → targetStage 必填, templateId=null；
 * COMPLETE → 二者均 null。
 */
@Getter
@Builder
@AllArgsConstructor
public class ExhaustionResult {

    private final ExhaustionAction action;
    private final Stage targetStage;
    private final String templateId;
    private final String reason;

    public static ExhaustionResult rebuild(String templateId, String reason) {
        return ExhaustionResult.builder().action(ExhaustionAction.REBUILD).templateId(templateId).reason(reason).build();
    }

    public static ExhaustionResult escalate(Stage targetStage, String reason) {
        return ExhaustionResult.builder().action(ExhaustionAction.ESCALATE).targetStage(targetStage).reason(reason).build();
    }

    public static ExhaustionResult complete(String reason) {
        return ExhaustionResult.builder().action(ExhaustionAction.COMPLETE).reason(reason).build();
    }
}
