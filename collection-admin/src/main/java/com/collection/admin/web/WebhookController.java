package com.collection.admin.web;

import com.collection.common.enums.EventType;
import com.collection.common.event.CollectionEvent;
import com.collection.common.event.CollectionEventBus;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.Map;

/**
 * 渠道回调入口。对应架构设计文档 §1.7：统一接收外部供应商回调，鉴权后发布 CHANNEL_CALLBACK。
 *
 * <p>Phase 1 骨架：跳过鉴权（TODO：Shiro/签名校验），直接转事件。
 * 用于验证电话/人工类（AI_CALL/TTS/HUMAN_CALL）异步回调链路。
 */
@RestController
@RequestMapping("/webhook")
public class WebhookController {

    @Resource
    private CollectionEventBus eventBus;

    /**
     * 渠道供应商回调 → 发布 CHANNEL_CALLBACK。
     *
     * @param result ContactResult 名（ANSWERED / NO_ANSWER / DELIVERED 等）
     */
    @PostMapping("/channel-callback")
    public Map<String, Object> channelCallback(@RequestParam Long planId,
                                               @RequestParam Long stepId,
                                               @RequestParam(required = false) Long caseId,
                                               @RequestParam(defaultValue = "ANSWERED") String result) {
        eventBus.publish(CollectionEvent.of(EventType.CHANNEL_CALLBACK)
                .with(CollectionEvent.PLAN_ID, planId)
                .with(CollectionEvent.STEP_ID, stepId)
                .with(CollectionEvent.CASE_ID, caseId)
                .with("result", result));
        Map<String, Object> m = new HashMap<>();
        m.put("ok", true);
        m.put("message", "CHANNEL_CALLBACK published, planId=" + planId + " stepId=" + stepId);
        return m;
    }
}
