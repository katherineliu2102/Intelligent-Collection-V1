package com.collection.admin.web;

import com.collection.engine.fault.EngineFaultInjector;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * T5-R3…R6、R13 的故障注入管控面。
 *
 * <p>注入必须能在<b>不重启</b>的前提下武装与解除：重启会清空 PEL 消费状态与 Stream 位点，而这几条用例恰恰要观察 PEL 的连续演进，重启即毁掉证据。
 *
 * <p>挂在 {@code /ops/**} 下由 {@code AdminAuthInterceptor} 强制登录态。开关本身仍受 {@code
 * engine.fault-injection.enabled} 约束——该项默认 false 且只能改配置重启生效，登录态并不足以打开它。
 *
 * <p><b>T4 前必须停用</b>：置 {@code engine.fault-injection.enabled=false} 并确认本接口返回 {@code armed=false}。
 */
@RestController
@RequestMapping("/ops/fault-injection")
public class FaultInjectionController {

    private final EngineFaultInjector injector;

    public FaultInjectionController(EngineFaultInjector injector) {
        this.injector = injector;
    }

    @GetMapping
    public Map<String, Object> status() {
        return ApiResponse.success(injector.status());
    }

    /**
     * 武装一次注入。
     *
     * @param position {@code BEFORE_HANDLER}（业务未执行，留 PEL）或 {@code AFTER_HANDLER}（业务已执行、ACK 前失败）
     * @param eventType 目标事件类型，与 eventId 至少给一个
     * @param eventId 目标事件 ID；造 {@code MAX_DELIVERY_EXCEEDED} 时配合 {@code remaining=-1} 锁定单条
     * @param remaining 剩余失败次数，{@code -1} 为不限次
     */
    @PostMapping("/arm")
    public Map<String, Object> arm(
            @RequestParam String position,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String eventId,
            @RequestParam(defaultValue = "1") long remaining) {
        EngineFaultInjector.Position parsed;
        try {
            parsed = EngineFaultInjector.Position.valueOf(position.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ApiResponse.failure(
                    "INVALID_POSITION", "position 只能是 BEFORE_HANDLER 或 AFTER_HANDLER");
        }
        try {
            return ApiResponse.success(injector.arm(parsed, eventType, eventId, remaining));
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ApiResponse.failure("ARM_REJECTED", e.getMessage());
        }
    }

    @DeleteMapping
    public Map<String, Object> disarm() {
        return ApiResponse.success(injector.disarm());
    }
}
