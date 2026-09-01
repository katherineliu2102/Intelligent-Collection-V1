package com.collection.common.repository;

import com.collection.common.enums.ContactResult;
import com.collection.common.model.ContactRecord;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 触达时间线持久层。对应基础设施规范 §5 writeTimeline / getContactHistory。 表 t_contact_timeline（跨模块共写）。实现位于
 * collection-service。
 */
public interface TimelineRepository {

    void writeTimeline(ContactRecord record);

    /** 近期触达历史（按时间倒序），用于 ExecutionContext.recentTimeline。 */
    List<ContactRecord> getContactHistory(Long userId, int limit);

    /** 按菲律宾业务窗口取历史；默认实现保留旧 Mock 兼容，生产实现必须在 SQL 过滤。 */
    default List<ContactRecord> getContactHistory(
            Long userId, LocalDateTime fromInclusive, int limit) {
        return getContactHistory(userId, limit);
    }

    /**
     * 案件维近期触达历史（按时间倒序），用于补齐当前阶段的决策窗口。
     *
     * <p>默认空集合兼容 Phase 1 Mock；生产实现必须在 SQL 按 {@code caseId} 与时间窗过滤。
     */
    default List<ContactRecord> getContactHistoryByCase(
            Long caseId, LocalDateTime fromInclusive, int limit) {
        return java.util.Collections.emptyList();
    }

    /**
     * 供应商事件把结果沿升级链前进：DELIVERED &lt; READ &lt; CLICKED &lt; REPLIED。
     *
     * <p>只升不降由 SQL 谓词保证而非先读后写：同一封邮件的 open 与 click 可能在同一批 webhook 里乱序抵达，
     * 应用侧比较会让后写的低阶结果盖掉高阶结果。终态行（REJECTED / FAILED 等）不接受升级—— 这比 {@link
     * com.collection.common.enums.ContactResult#canUpgradeFrom} 严格：后者按 priority 比较， 终态 priority=0
     * 反而会被任意链上结果盖掉，与枚举自身「终态不参与升级链」的声明相悖。
     *
     * @return 实际更新的行数；0 表示 {@code attemptKey} 不存在或按升级链被拒
     */
    default int upgradeResult(
            String attemptKey,
            ContactResult result,
            String providerMsgId,
            String providerCallback) {
        return 0;
    }

    /**
     * 供应商事件无条件改写结果，用于「未送达」类事实（hard bounce / dropped / 投诉 / 退订）。
     *
     * <p>必须能盖掉 DELIVERED：SendGrid 的 202 只证明请求被受理，hard bounce 才是投递结论。 若沿用升级链，退信行会永远停在 DELIVERED，对账与
     * bounce 率取证都会读出一次不存在的成功触达。
     *
     * @return 实际更新的行数；0 表示 {@code attemptKey} 不存在
     */
    default int overrideResult(
            String attemptKey,
            ContactResult result,
            String providerMsgId,
            String providerCallback) {
        return 0;
    }
}
