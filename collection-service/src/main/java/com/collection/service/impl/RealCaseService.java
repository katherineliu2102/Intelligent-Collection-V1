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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 真实 CaseService —— 映射旧库 t_collection → CaseInfo / CaseContext / ContextSnapshot。
 *
 * <p>仅在配置 {@code collection.case-service=real} 时生效（{@code @Primary} 顶掉 {@link MockCaseService}），
 * 默认关闭，保证纯 Mock 单测 / CI 不受影响。用于 Phase 1 真实数据链路测试（SMS / EMAIL）。
 *
 * <p>映射约定：
 * <ul>
 *   <li>caseId = loan_id（数字串）；t_collection.id 是 hex 串不可用。</li>
 *   <li>Stage 不存列，由 dpd 经 {@link Stage#fromDpd(int)} 推导；dpd = overdue_days（正数=已逾期天数）。</li>
 *   <li>phone 归一化为 E.164 +63；email 脏值（空 / "0"）置 null（EMAIL 渠道走 Guard SKIP）。</li>
 *   <li>PUSH 的 jpushToken 不在 t_collection，留空（PUSH 待 t_user_equipment 接入）。</li>
 *   <li>repaid = full_repay_time 非空或 total_not_paid &lt;= 0；frozen 无来源，固定 false。</li>
 * </ul>
 */
@Service
@Primary
@ConditionalOnProperty(prefix = "collection", name = "case-service", havingValue = "real")
public class RealCaseService implements CaseService {

    @Resource
    private CollectionCaseMapper caseMapper;

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
        info.setFrozen(false);
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
        ctx.setDueDate(row.getRepaymentDate());
        ctx.setCaseStatus(row.getCollecitonStatus());
        ctx.setRepaymentUrl(repaymentUrlTemplate.replace("{caseId}", String.valueOf(caseId)));
        ctx.setStrategyTone("STANDARD");
        ctx.setComplaintFrozen(false);
        ctx.setCollectionStatus("ACTIVE");
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
        // device.jpushToken 暂无来源，PUSH 渠道待 t_user_equipment 接入。
        profile.setDevice(new UserProfile.DeviceInfo());
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
        return row.getTotalNotPaid() != null && row.getTotalNotPaid().compareTo(BigDecimal.ZERO) <= 0;
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
