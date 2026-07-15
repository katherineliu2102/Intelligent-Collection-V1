package com.collection.channel.strategy;

import com.collection.common.dto.ExhaustionResult;
import com.collection.common.enums.Stage;
import com.collection.common.model.CaseInfo;
import com.collection.common.model.ContactPlan;
import com.collection.common.model.ContextSnapshot;
import com.collection.common.spi.ExhaustionPolicy;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Phase 1 简化版 ExhaustionPolicy —— 内存计数器追踪续建次数 + Stage 升档路径。
 *
 * <p>主架构临时代写，推进 L4a-全测试。编排同事回来后替换为生产实现（可能读 DB 统计历史计划数）。
 *
 * <p>决策逻辑：
<<<<<<< HEAD
 *
 * <ol>
 *   <li>续建次数 < max-rebuild-count → REBUILD
 *   <li>超限 + stage 可升档（S1→S2, S2→S3, S3→S4）→ ESCALATE
 *   <li>S4 或无法升档 → COMPLETE
=======
 * <ol>
 *   <li>续建次数 < max-rebuild-count → REBUILD</li>
 *   <li>超限 + stage 可升档（S1→S2, S2→S3, S3→S4）→ ESCALATE</li>
 *   <li>S4 或无法升档 → COMPLETE</li>
>>>>>>> origin/ca_branch
 * </ol>
 *
 * <p>内存计数器按 {@code caseId:stage} 维度追踪，重启清零（Phase 1 可接受）。
 */
@Primary
@Component
public class DefaultExhaustionPolicy implements ExhaustionPolicy {

    private static final Logger log = LoggerFactory.getLogger(DefaultExhaustionPolicy.class);

    @Value("${engine.plan.max-rebuild-count:2}")
    private int maxRebuildCount;

<<<<<<< HEAD
    private final ConcurrentHashMap<String, AtomicInteger> rebuildCounter =
            new ConcurrentHashMap<>();
=======
    private final ConcurrentHashMap<String, AtomicInteger> rebuildCounter = new ConcurrentHashMap<>();
>>>>>>> origin/ca_branch

    @Override
    public ExhaustionResult handle(ContactPlan plan, CaseInfo caseInfo, ContextSnapshot snapshot) {
        Stage stage = plan.getStage();
        String key = plan.getCaseId() + ":" + (stage != null ? stage.name() : "UNKNOWN");

        AtomicInteger counter = rebuildCounter.computeIfAbsent(key, k -> new AtomicInteger(0));
        int current = counter.get();

        if (current < maxRebuildCount) {
            counter.incrementAndGet();
            String reason = "rebuild #" + (current + 1) + "/" + maxRebuildCount;
            log.info("[DefaultExhaustionPolicy] {} → REBUILD ({})", key, reason);
            return ExhaustionResult.rebuild("default-rebuild", reason);
        }

        Stage next = nextStage(stage);
        if (next != null) {
            log.info("[DefaultExhaustionPolicy] {} rebuild exhausted, ESCALATE → {}", key, next);
            return ExhaustionResult.escalate(next, "rebuild limit reached, escalate from " + stage);
        }

        log.info("[DefaultExhaustionPolicy] {} fully exhausted (S4 or no escalation path)", key);
        return ExhaustionResult.complete("max rebuild & escalation exhausted at " + stage);
    }

    private static Stage nextStage(Stage current) {
        if (current == null) {
            return null;
        }
        switch (current) {
            case S0:
                return Stage.S1;
            case S1:
                return Stage.S2;
            case S2:
                return Stage.S3;
            case S3:
                return Stage.S4;
            case S4:
            default:
                return null;
        }
    }
}
