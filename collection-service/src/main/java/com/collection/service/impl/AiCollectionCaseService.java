package com.collection.service.impl;

import com.collection.common.enums.Stage;
import com.collection.common.model.CaseContext;
import com.collection.common.model.CaseInfo;
import com.collection.common.model.ContactHistory;
import com.collection.common.model.ContextSnapshot;
import com.collection.common.model.UserProfile;
import com.collection.common.repository.OwnerReconcileRepository;
import com.collection.common.service.CaseService;
import com.collection.service.mapper.AiCollectionCaseMapper;
import com.collection.service.mapper.AiCollectionCaseRow;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import javax.annotation.Resource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/** t_ai_collection 的运行时 CaseService；启用 collection.case-service=ai。 */
@Service
@Primary
@ConditionalOnProperty(prefix = "collection", name = "case-service", havingValue = "ai")
public class AiCollectionCaseService implements CaseService {

    @Resource private AiCollectionCaseMapper mapper;

    @Autowired(required = false)
    private OwnerReconcileRepository ownerReconcileRepository;

    private static final ZoneId PHT = ZoneId.of("Asia/Manila");

    // 默认值不能落在 .test 域：pilot/生产漏配时它会被渲染进真实短信正文。
    @Value("${collection.repayment-url-template:https://app.mocasa.com/repay/{caseId}}")
    private String repaymentUrlTemplate;

    @Override
    public CaseInfo getCaseInfo(Long caseId) {
        AiCollectionCaseRow row = require(caseId);
        CaseInfo info = new CaseInfo();
        info.setCaseId(caseId);
        info.setUserId(row.getUserId());
        info.setDpd(row.getDpd());
        info.setStage(stage(row));
        info.setProduct(row.getProduct());
        info.setCaseStatus(row.getCollectionStatus());
        info.setOverdueAmount(row.getOverdueAmount());
        info.setTotalOutstanding(row.getTotalOutstanding());
        info.setPenaltyAmount(row.getPenaltyAmount());
        info.setUpcomingAmount(row.getUpcomingAmount());
        info.setDueDate(row.getDueDate());
        info.setNextDueDate(row.getNextDueDate());
        info.setRepaid(isSettled(row));
        info.setOwnerDate(row.getOwnerDate());
        return info;
    }

    @Override
    public CaseContext buildContext(Long caseId) {
        AiCollectionCaseRow row = require(caseId);
        CaseContext context = new CaseContext();
        context.setCaseId(caseId);
        context.setUserId(row.getUserId());
        context.setDpd(row.getDpd());
        context.setStage(stage(row));
        context.setProduct(row.getProduct());
        context.setOverdueAmount(row.getOverdueAmount());
        context.setTotalOutstanding(row.getTotalOutstanding());
        context.setPenaltyAmount(row.getPenaltyAmount());
        context.setUpcomingAmount(row.getUpcomingAmount());
        context.setDueDate(row.getDueDate());
        context.setNextDueDate(row.getNextDueDate());
        context.setCaseStatus(row.getCollectionStatus());
        context.setCollectionStatus(
                "CEASED".equalsIgnoreCase(row.getCollectionStatus()) ? "CEASED" : "ACTIVE");
        context.setRepaymentUrl(repaymentUrlTemplate.replace("{caseId}", String.valueOf(caseId)));
        context.setStrategyTone("STANDARD");
        context.setComplaintFrozen(false);
        return context;
    }

    @Override
    public ContactHistory buildContactHistory(Long userId, Long caseId) {
        ContactHistory history = new ContactHistory();
        history.setStageEntryDate(LocalDate.now());
        return history;
    }

    @Override
    public ContextSnapshot getContextSnapshot(Long caseId) {
        AiCollectionCaseRow row = require(caseId);
        ContextSnapshot snapshot = new ContextSnapshot();
        snapshot.setCaseContext(buildContext(caseId));
        snapshot.setContactHistory(buildContactHistory(row.getUserId(), caseId));
        UserProfile profile = new UserProfile();
        profile.setUserId(row.getUserId());
        UserProfile.BasicInfo basic = new UserProfile.BasicInfo();
        basic.setName(row.getBorrowerName());
        basic.setPrimaryPhone(row.getBorrowerPhone());
        basic.setEmail(row.getBorrowerEmail());
        basic.setLanguage(row.getBorrowerLanguage() == null ? "en" : row.getBorrowerLanguage());
        profile.setBasic(basic);
        UserProfile.DeviceInfo device = new UserProfile.DeviceInfo();
        device.setJpushToken(row.getPushToken());
        profile.setDevice(device);
        snapshot.setUserProfile(profile);
        snapshot.setSnapshotTime(LocalDateTime.now());
        snapshot.setSnapshotVersion("ai-" + row.getCaseVersion());
        return snapshot;
    }

    @Override
    public boolean isRepaid(Long caseId) {
        AiCollectionCaseRow row = mapper.selectByCaseId(caseId);
        return row != null && isSettled(row);
    }

    @Override
    public List<Long> findActiveCaseIdsAfter(Long lastCaseId, int limit) {
        return mapper.selectCaseIdsAfter(lastCaseId == null ? 0L : lastCaseId, limit);
    }

    @Override
    public boolean isOwnerReconciledToday() {
        if (ownerReconcileRepository == null) {
            return false;
        }
        return ownerReconcileRepository.completedOn(LocalDate.now(PHT));
    }

    @Override
    public boolean requiresOwnerDate() {
        return true;
    }

    private AiCollectionCaseRow require(Long caseId) {
        AiCollectionCaseRow row = mapper.selectByCaseId(caseId);
        if (row == null) {
            throw new IllegalStateException("t_ai_collection 无 case_id=" + caseId + " 的案件");
        }
        return row;
    }

    /**
     * 投影 stage 列为准；仅在该列为空且案件确已进入催收窗口时，才按 dpd 推导兜底。
     *
     * <p>不能无条件退回 {@link Stage#fromDpd(int)}：数仓口径下 stage 为 null 有两种成因，而 {@code fromDpd} 会把两者都兜成
     * {@code S0}，等于把数仓明确表达的「不属任何催收阶段」静默还原成催收目标：
     *
     * <ol>
     *   <li><b>无尾款</b>（2026-08-24 数仓口径）：{@code dpd=0} 且 stage 为 null。此时 dpd 落在 S0 的 [-3,0] 区间内， 靠
     *       dpd 无从与「今日到期」区分，只能凭 {@code isFullCleared} 派生的 {@code SETTLED} 判定——故结清判断必须排在最前。
     *   <li><b>有尾款但下一个未还 dueDate 超过 3 天</b>：dpd 为负且 &lt; -3，由下界判断拦下。
     * </ol>
     *
     * <p>dpd &gt; 0 仍走 {@code fromDpd} 兜底：此时确有到期未还，stage 缺失属数据质量问题，漏催的代价高于错档。
     */
    private Stage stage(AiCollectionCaseRow row) {
        if (isSettled(row)) {
            return null;
        }
        if (row.getStage() != null) {
            return Stage.valueOf(row.getStage());
        }
        Integer dpd = row.getDpd();
        if (dpd != null && dpd < Stage.S0.getMinDpd()) {
            return null;
        }
        return dpd == null ? null : Stage.fromDpd(dpd);
    }

    private boolean isSettled(AiCollectionCaseRow row) {
        return "SETTLED".equalsIgnoreCase(row.getCollectionStatus());
    }
}
