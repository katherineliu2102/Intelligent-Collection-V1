package com.collection.channel.strategy;

import static org.junit.jupiter.api.Assertions.*;

import com.collection.common.enums.Stage;
import com.collection.common.model.CaseContext;
import com.collection.common.model.CaseInfo;
import com.collection.common.model.ContextSnapshot;
import org.junit.jupiter.api.Test;

class MockPlanFactoryGuardTest {

    @Test
    void rejectCeasedCaseStatus() {
        CaseInfo info = new CaseInfo();
        info.setCaseStatus("CEASED");
        assertTrue(MockPlanFactory.shouldRejectPlan(info, null));
    }

    @Test
    void rejectDpd91() {
        CaseInfo info = new CaseInfo();
        info.setCaseId(90091L);
        info.setCaseStatus("ACTIVE");
        ContextSnapshot snapshot = new ContextSnapshot();
        CaseContext ctx = new CaseContext();
        ctx.setDpd(91);
        ctx.setCollectionStatus("CEASED");
        snapshot.setCaseContext(ctx);
        assertTrue(MockPlanFactory.shouldRejectPlan(info, snapshot));
    }

    @Test
    void allowNormalCase() {
        CaseInfo info = new CaseInfo();
        info.setCaseId(91001L);
        info.setCaseStatus("OVERDUE");
        ContextSnapshot snapshot = new ContextSnapshot();
        CaseContext ctx = new CaseContext();
        ctx.setDpd(5);
        ctx.setCollectionStatus("ACTIVE");
        snapshot.setCaseContext(ctx);
        assertFalse(MockPlanFactory.shouldRejectPlan(info, snapshot));
    }

    @Test
    void rejectNullCaseInfo() {
        assertTrue(MockPlanFactory.shouldRejectPlan(null, new ContextSnapshot()));
    }

    /**
     * 提前还清后 dpd 为负（距下一个未还 dueDate 的天数）。超过 3 天时数仓把 stage 置 null， 表示尚未进入催收窗口——此时必须拒建，否则 {@link
     * Stage#fromDpd(int)} 的 S0 兜底会让 已还清的客户被建出计划。
     */
    @Test
    void rejectCaseNotYetInCollectionWindow() {
        CaseInfo info = new CaseInfo();
        info.setCaseId(91002L);
        info.setCaseStatus("ACTIVE");
        ContextSnapshot snapshot = new ContextSnapshot();
        CaseContext ctx = new CaseContext();
        ctx.setDpd(-5);
        ctx.setCollectionStatus("ACTIVE");
        snapshot.setCaseContext(ctx);
        assertTrue(MockPlanFactory.shouldRejectPlan(info, snapshot));
    }

    /**
     * 数仓新口径（2026-08-24）：无尾款时 dpd=0、stage=null。dpd=0 落在 S0 的 [-3,0] 区间内， 既躲过 D+91 上界也躲过 dpd&lt;-3
     * 下界，只有结清状态本身拦得住——否则已还清客户会被建出 S0 计划。
     */
    @Test
    void rejectSettledCaseWhoseDpdIsZeroUnderNewContract() {
        CaseInfo info = new CaseInfo();
        info.setCaseId(91004L);
        info.setCaseStatus("SETTLED");
        ContextSnapshot snapshot = new ContextSnapshot();
        CaseContext ctx = new CaseContext();
        ctx.setDpd(0);
        ctx.setCollectionStatus("SETTLED");
        snapshot.setCaseContext(ctx);
        assertTrue(MockPlanFactory.shouldRejectPlan(info, snapshot));
    }

    /** 快照口径为 SETTLED 但 caseInfo 状态滞后时，仍须拒建。 */
    @Test
    void rejectSettledFromSnapshotEvenWhenCaseStatusLags() {
        CaseInfo info = new CaseInfo();
        info.setCaseId(91005L);
        info.setCaseStatus("IN_COLLECTION");
        ContextSnapshot snapshot = new ContextSnapshot();
        CaseContext ctx = new CaseContext();
        ctx.setDpd(0);
        ctx.setCollectionStatus("SETTLED");
        snapshot.setCaseContext(ctx);
        assertTrue(MockPlanFactory.shouldRejectPlan(info, snapshot));
    }

    /** {@code isRepaid} 是结清的另一路信号（CaseService 由 collection_status 派生），同样须拒建。 */
    @Test
    void rejectRepaidCase() {
        CaseInfo info = new CaseInfo();
        info.setCaseId(91006L);
        info.setCaseStatus("IN_COLLECTION");
        info.setRepaid(true);
        assertTrue(MockPlanFactory.shouldRejectPlan(info, null));
    }

    /** dpd=0 且未结清 = 今日到期，属 S0 提醒窗口，不得被结清闸门误伤。 */
    @Test
    void allowDueTodayCaseThatIsNotSettled() {
        CaseInfo info = new CaseInfo();
        info.setCaseId(91007L);
        info.setCaseStatus("IN_COLLECTION");
        ContextSnapshot snapshot = new ContextSnapshot();
        CaseContext ctx = new CaseContext();
        ctx.setDpd(0);
        ctx.setCollectionStatus("IN_COLLECTION");
        snapshot.setCaseContext(ctx);
        assertFalse(MockPlanFactory.shouldRejectPlan(info, snapshot));
    }

    /** D-3 起属于 S0 提醒窗口，是正常建计划区间，不得被下界闸门误伤。 */
    @Test
    void allowCaseOnTheS0WindowBoundary() {
        CaseInfo info = new CaseInfo();
        info.setCaseId(91003L);
        info.setCaseStatus("ACTIVE");
        ContextSnapshot snapshot = new ContextSnapshot();
        CaseContext ctx = new CaseContext();
        ctx.setDpd(Stage.S0.getMinDpd());
        ctx.setCollectionStatus("ACTIVE");
        snapshot.setCaseContext(ctx);
        assertFalse(MockPlanFactory.shouldRejectPlan(info, snapshot));
    }
}
