package com.collection.admin.web;

import com.collection.engine.lifecycle.PlanLifecycleManager;
import com.collection.engine.lifecycle.PlanLifecycleManager.StrategyRebuildResult;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 策略刷新：取消 S1–S4 活跃计划并按当前模板重建。仅 SYSTEM_ADMIN。
 *
 * <p>口径：{@code docs/channel/MOCASA催收系统升级_Phase1_迭代_AI_Call五波与日限_20260911.md}。默认窗口 ≥19:00 PHT 且次日
 * 03:35 日切前；错过则 {@code force=true}。分页先按启动时 {@code MAX(id)} 冻结再取消重建，避免新计划 id 更大被扫第二遍。
 */
@RestController
@RequestMapping("/ops/plans")
public class PlanStrategyRebuildController {

    static final String CONFIRM_TOKEN = "FIVE_WAVES_20260911";
    private static final ZoneId PHT = ZoneId.of("Asia/Manila");
    private static final int MAX_LIMIT = 5000;
    private static final int PAGE_SIZE = 200;

    private final PlanLifecycleManager planLifecycleManager;

    public PlanStrategyRebuildController(PlanLifecycleManager planLifecycleManager) {
        this.planLifecycleManager = planLifecycleManager;
    }

    @PostMapping("/rebuild-strategy")
    public ResponseEntity<Map<String, Object>> rebuildStrategy(
            @RequestParam(required = false) String confirm,
            @RequestParam(defaultValue = "false") boolean dryRun,
            @RequestParam(defaultValue = "false") boolean force,
            @RequestParam(defaultValue = "5000") int limit,
            @RequestParam(defaultValue = "0") long afterId,
            @RequestParam(defaultValue = "0") long maxId) {
        if (!CONFIRM_TOKEN.equals(confirm)) {
            return ResponseEntity.badRequest()
                    .body(
                            ApiResponse.failure(
                                    "CONFIRMATION_REQUIRED", "confirm 必须等于 " + CONFIRM_TOKEN));
        }
        LocalDateTime now = LocalDateTime.now(PHT);
        if (!force && !inRebuildWindow(now)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(
                            ApiResponse.failure(
                                    "OUTSIDE_REBUILD_WINDOW",
                                    "须在 ≥19:00 PHT 且次日 03:35 日切前重建，当前 "
                                            + now
                                            + "。紧急覆盖传 force=true"));
        }
        int cap = Math.max(1, Math.min(limit, MAX_LIMIT));
        long cursor = Math.max(0L, afterId);
        Long freeze;
        if (maxId > 0) {
            freeze = maxId;
        } else {
            freeze = planLifecycleManager.maxActiveS1ToS4PlanId();
        }
        List<Long> batch = new ArrayList<>();
        if (freeze != null && freeze > 0) {
            while (batch.size() < cap) {
                int page = Math.min(PAGE_SIZE, cap - batch.size());
                List<Long> ids =
                        planLifecycleManager.listActiveS1ToS4PlanIds(cursor, freeze, page);
                if (ids == null || ids.isEmpty()) {
                    break;
                }
                batch.addAll(ids);
                cursor = ids.get(ids.size() - 1);
                if (ids.size() < page) {
                    break;
                }
            }
        }
        int scanned = 0;
        int rebuilt = 0;
        int wouldRebuild = 0;
        int skipped = 0;
        int failed = 0;
        List<Long> executing = new ArrayList<>();
        List<Map<String, Object>> samples = new ArrayList<>();
        for (Long planId : batch) {
            scanned++;
            StrategyRebuildResult result = planLifecycleManager.rebuildStrategyPlan(planId, dryRun);
            if (samples.size() < 20) {
                samples.add(result.toMap());
            }
            String outcome = result.getOutcome();
            if ("REBUILT".equals(outcome)) {
                rebuilt++;
            } else if ("WOULD_REBUILD".equals(outcome)) {
                wouldRebuild++;
            } else if ("FAILED".equals(outcome)) {
                failed++;
            } else {
                skipped++;
                if ("SKIPPED_EXECUTING_AI".equals(result.getReason()) && executing.size() < 50) {
                    executing.add(planId);
                }
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("dryRun", dryRun);
        data.put("nowPht", now.toString());
        data.put("scanned", scanned);
        data.put("rebuilt", rebuilt);
        data.put("wouldRebuild", wouldRebuild);
        data.put("skipped", skipped);
        data.put("failed", failed);
        data.put("executingPlanIds", executing);
        data.put("frozenMaxId", freeze);
        data.put("nextAfterId", cursor);
        data.put("samples", samples);
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    /** ≥19:00 当日，或次日 00:00–03:34（日切前）。 */
    static boolean inRebuildWindow(LocalDateTime nowPht) {
        if (nowPht == null) {
            return false;
        }
        int hour = nowPht.getHour();
        int minute = nowPht.getMinute();
        return hour >= 19 || hour < 3 || (hour == 3 && minute < 35);
    }
}
