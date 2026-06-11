package com.collection.channel.strategy;

import com.collection.common.enums.Stage;
import com.collection.common.model.CaseInfo;
import com.collection.common.model.ContextSnapshot;
import com.collection.common.model.CaseContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

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
}
