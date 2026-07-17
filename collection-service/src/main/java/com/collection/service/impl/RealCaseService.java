package com.collection.service.impl;

import com.collection.common.enums.Stage;
import com.collection.common.model.CaseContext;
import com.collection.common.model.CaseInfo;
import com.collection.common.model.ContactHistory;
import com.collection.common.model.ContextSnapshot;
import com.collection.common.model.UserProfile;
import com.collection.common.service.CaseService;
import com.collection.service.mapper.CollectionCaseMapper;
import com.collection.service.mapper.CollectionCaseRow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import javax.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * 真实 CaseService —— 映射旧库 t_collection → CaseInfo / CaseContext / ContextSnapshot。
 *
 * <p>仅在配置 {@code collection.case-service=real} 时生效（{@code @Primary} 顶掉 {@link MockCaseService}），
 * 默认关闭，保证纯 Mock 单测 / CI 不受影响。用于 Phase 1 真实数据链路测试（SMS / EMAIL）。
 *
 * <p><b>决策 B（2026-06-29）降级</b>：快照主链路已改为 {@code CASE_INGESTED} 事件 payload 自带 （引擎 {@code
 * PlanLifecycleManager} 据 payload 组装，运行时不读旧库 {@code t_collection}）。本类 {@link #getContextSnapshot} /
 * {@link #getCaseInfo} 仅作<b>可选对账 / 兜底</b>（payload 缺失时引擎才降级调用）， <b>不再是主快照来源</b>。{@link #isRepaid} 仍为
 * {@code PreFlightChecker} 的实时还款守卫，属可接受的运行时只读耦合。
 *
 * <p>映射约定：
 *
 * <ul>
 *   <li>caseId = loan_id（数字串）；t_collection.id 是 hex 串不可用。
 *   <li>Stage 不存列，由 dpd 经 {@link Stage#fromDpd(int)} 推导；dpd = overdue_days（正数=已逾期天数）。
 *       边界已对齐编排规格：S2[4,15]、S3[16,30]、S4[31+]。
 *   <li>penaltyAmount = t_collection.overdue（罚息金额）。
 *   <li>phone 归一化为 E.164 +63；email 脏值（空 / "0"）置 null（EMAIL 渠道走 Guard SKIP）。
 *   <li>PUSH 的 jpushToken = t_user_extend.ji_guang_token（按 user_id 查，null 时 PushAdapter 自动 fallback
 *       SMS）。
 *   <li>repaid = full_repay_time 非空或 total_not_paid &lt;= 0；frozen 无来源，固定 false。
 * </ul>
 */
@Service
@Primary
@ConditionalOnProperty(prefix = "collection", name = "case-service", havingValue = "real")
public class RealCaseService implements CaseService {

    private static final Logger log = LoggerFactory.getLogger(RealCaseService.class);

    @Resource private CollectionCaseMapper caseMapper;
    /** 还款深链模板，{caseId} 占位。 */
    @Value("${collection.repayment-url-template:https://app.mocasa.test/repay/{caseId}}")
    private String repaymentUrlTemplate;

    @Override
    public CaseInfo getCaseInfo(Long caseId) {
        CollectionCaseRow row = require(caseId);
        CaseInfo info = new CaseInfo();
        info.setCaseId(caseId);
        info.setUserId(parseUserId(row, caseId));
        int dpd = row.getOverdueDays() == null ? 0 : row.getOverdueDays();
        info.setDpd(dpd);
        info.setStage(Stage.fromDpd(dpd));
        info.setProduct(row.getAppName());
        info.setCaseStatus(row.getCollecitonStatus());
        info.setTotalOutstanding(row.getTotalNotPaid());
        info.setDueDate(row.getRepaymentDate());
        info.setRepaid(isRepaidRow(row));
        return info;
    }

    @Override
    public CaseContext buildContext(Long caseId) {
        CollectionCaseRow row = require(caseId);
        CaseContext ctx = new CaseContext();
        ctx.setCaseId(caseId);
        ctx.setUserId(parseUserId(row, caseId));
        int dpd = row.getOverdueDays() == null ? 0 : row.getOverdueDays();
        ctx.setDpd(dpd);
        ctx.setStage(Stage.fromDpd(dpd));
        ctx.setProduct(row.getAppName());
        ctx.setTotalOutstanding(row.getTotalNotPaid());
        ctx.setPenaltyAmount(row.getOverdue());
        ctx.setDueDate(row.getRepaymentDate());
        ctx.setCaseStatus(row.getCollecitonStatus());
        ctx.setRepaymentUrl(repaymentUrlTemplate.replace("{caseId}", String.valueOf(caseId)));
        ctx.setStrategyTone("STANDARD");
        ctx.setComplaintFrozen(false);
        // D+91 完全停催：collectionStatus=CEASED，PlanFactory.shouldRejectPlan 据此拒建计划（双保险，对齐 seed
        // 99000005）
        ctx.setCollectionStatus(dpd >= 91 ? "CEASED" : "ACTIVE");
        return ctx;
    }

    @Override
    public ContactHistory buildContactHistory(Long userId, Long caseId) {
        // Phase 1 真实链路：频控以 t_contact_timeline 实时为准（引擎 ContextAssembler 另行加载），
        // 快照内 contactHistory 给最小集即可。
        ContactHistory h = new ContactHistory();
        h.setStageEntryDate(LocalDate.now());
        return h;
    }

    @Override
    public ContextSnapshot getContextSnapshot(Long caseId) {
        CollectionCaseRow row = require(caseId);
        ContextSnapshot snapshot = new ContextSnapshot();
        snapshot.setCaseContext(buildContext(caseId));
        snapshot.setContactHistory(buildContactHistory(parseUserId(row, caseId), caseId));

        UserProfile profile = new UserProfile();
        profile.setUserId(parseUserId(row, caseId));
        UserProfile.BasicInfo basic = new UserProfile.BasicInfo();
        basic.setName(row.getRealName());
        basic.setPrimaryPhone(normalizePhone(row.getPhone()));
        basic.setEmail(cleanEmail(row.getEmail()));
        basic.setLanguage("en");
        profile.setBasic(basic);
        UserProfile.DeviceInfo device = new UserProfile.DeviceInfo();
        // ji_guang_token from t_user_extend; null → PushAdapter fallback to SMS / push-test-token
        // override.
        // 防御：t_user_extend 可能尚未建表/无数据，查询失败不应阻断整个快照组装。
        try {
            String jiGuangToken = caseMapper.selectJiGuangToken(row.getUserId());
            if (jiGuangToken != null && !jiGuangToken.trim().isEmpty()) {
                device.setJpushToken(jiGuangToken.trim());
            }
        } catch (Exception e) {
            log.warn(
                    "[RealCaseService] selectJiGuangToken failed (t_user_extend missing?) userId={}: {}",
                    row.getUserId(),
                    e.getMessage());
        }
        profile.setDevice(device);
        snapshot.setUserProfile(profile);

        snapshot.setSnapshotTime(LocalDateTime.now());
        snapshot.setSnapshotVersion("real-v1");
        return snapshot;
    }

    @Override
    public boolean isRepaid(Long caseId) {
        CollectionCaseRow row = caseMapper.selectByLoanId(String.valueOf(caseId));
        return row != null && isRepaidRow(row);
    }

    // ───────────────────────── helpers ─────────────────────────

    private CollectionCaseRow require(Long caseId) {
        CollectionCaseRow row = caseMapper.selectByLoanId(String.valueOf(caseId));
        if (row == null) {
            throw new IllegalStateException("t_collection 无 loan_id=" + caseId + " 的案件");
        }
        return row;
    }

    private boolean isRepaidRow(CollectionCaseRow row) {
        if (row.getFullRepayTime() != null) {
            return true;
        }
        return row.getTotalNotPaid() != null
                && row.getTotalNotPaid().compareTo(BigDecimal.ZERO) <= 0;
    }

    private Long parseUserId(CollectionCaseRow row, Long fallback) {
        try {
            return row.getUserId() == null ? fallback : Long.parseLong(row.getUserId().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** 归一化为 E.164 +63（菲律宾）：去空格/连字符；本地 09xx/9xx → +639xx；已 + 开头则保留。 */
    private String normalizePhone(String raw) {
        if (raw == null) {
            return null;
        }
        String p = raw.replaceAll("[\\s-]", "");
        if (p.isEmpty()) {
            return null;
        }
        if (p.startsWith("+")) {
            return p;
        }
        if (p.startsWith("0")) {
            p = p.substring(1);
        }
        if (p.startsWith("63")) {
            return "+" + p;
        }
        return "+63" + p;
    }

    /** 清洗脏邮箱：null / 空 / "0" → null（EMAIL 渠道由 Guard SKIP 处理）。 */
    private String cleanEmail(String raw) {
        if (raw == null) {
            return null;
        }
        String e = raw.trim();
        if (e.isEmpty() || "0".equals(e) || !e.contains("@")) {
            return null;
        }
        return e;
    }
}
