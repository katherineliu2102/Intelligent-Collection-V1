package com.collection.admin.web;

import com.collection.common.enums.Stage;
import com.collection.common.service.CaseService;
import com.collection.ingestion.IngestionService;
import com.collection.service.impl.MockCaseService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.Map;

/**
 * 链路自测触发入口（Phase 1）。通过发布领域事件驱动核心引擎全链路。
 *
 * <p>这些 mock 端点仅用于无上游 PubSub 时验证链路；生产由 collection-ingestion 真实消费替代。
 */
@RestController
@RequestMapping("/mock")
public class MockTriggerController {

    @Resource
    private IngestionService ingestionService;
    @Resource
    private CaseService caseService;

    /** 注入新案件，触发建计划 → 步骤执行全链路。 */
    @PostMapping("/ingest")
    public Map<String, Object> ingest(@RequestParam Long caseId,
                                      @RequestParam(required = false) Long userId,
                                      @RequestParam(required = false) Stage stage) {
        ingestionService.ingestCase(caseId, userId, stage);
        return ok("CASE_INGESTED published, caseId=" + caseId);
    }

    /** 模拟还款到账：标记 mock 案件已还款 + 发布 REPAYMENT_RECEIVED（应取消该用户活跃计划）。 */
    @PostMapping("/repayment")
    public Map<String, Object> repayment(@RequestParam Long userId,
                                         @RequestParam(required = false) Long caseId) {
        if (caseId != null && caseService instanceof MockCaseService) {
            ((MockCaseService) caseService).markRepaid(caseId);
        }
        ingestionService.repayment(userId);
        return ok("REPAYMENT_RECEIVED published, userId=" + userId);
    }

    /** 模拟阶段变更：取消旧阶段计划 + 创建新阶段计划。 */
    @PostMapping("/stage-changed")
    public Map<String, Object> stageChanged(@RequestParam Long caseId, @RequestParam Stage stage) {
        ingestionService.changeStage(caseId, stage);
        return ok("STAGE_CHANGED published, caseId=" + caseId + " stage=" + stage);
    }

    /** 模拟 PTP 到期。 */
    @PostMapping("/ptp-expired")
    public Map<String, Object> ptpExpired(@RequestParam Long caseId, @RequestParam Long ptpId) {
        ingestionService.ptpExpired(caseId, ptpId);
        return ok("PTP_EXPIRED published, caseId=" + caseId);
    }

    private Map<String, Object> ok(String msg) {
        Map<String, Object> m = new HashMap<>();
        m.put("ok", true);
        m.put("message", msg);
        return m;
    }
}
